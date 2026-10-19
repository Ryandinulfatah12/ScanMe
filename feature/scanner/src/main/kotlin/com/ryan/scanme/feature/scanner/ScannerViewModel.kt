package com.ryan.scanme.feature.scanner

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.camera.core.ImageProxy
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryan.scanme.core.camera.LiveDocumentAnalyzer
import com.ryan.scanme.core.camera.jpegBytes
import com.ryan.scanme.core.data.export.PdfExporter
import com.ryan.scanme.core.data.export.PdfPageSource
import com.ryan.scanme.core.data.export.RenderedPage
import com.ryan.scanme.core.data.processing.PageProcessor
import com.ryan.scanme.core.data.processing.RectifiedPage
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.core.Mat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlin.math.max

/** One captured page. Editing is non-destructive: everything is re-applied from [photo]. */
data class PageDraft(
    val id: String,
    val photo: File,
    /** Clockwise rotation that makes the stored JPEG upright (from CameraX). */
    val photoRotation: Int,
    /** Detected outline, kept so the crop screen can reset to it. */
    val autoQuad: Quad?,
    val quad: Quad,
    /** User rotation of the finished page, multiple of 90. */
    val rotation: Int = 0,
    val preset: FilterPreset = FilterPreset.AUTO_COLOR,
    val focalLengthPx: Double?,
    val thumbnail: Bitmap? = null,
)

sealed interface ScanUiState {
    data object Camera : ScanUiState
    data class Processing(val message: String) : ScanUiState
    data class Review(
        val index: Int,
        val preview: Bitmap,
        val pageWidth: Int,
        val pageHeight: Int,
        val detected: Boolean,
        val rectifyMs: Long,
        val renderMs: Long,
        val busy: String? = null,
        val message: String? = null,
    ) : ScanUiState
    data class Crop(val pageId: String, val photo: Bitmap, val quad: Quad, val autoQuad: Quad?) : ScanUiState
}

/** One-shot request for the UI to start a share Intent. [packageName] null = system chooser. */
data class ShareRequest(val uri: Uri, val packageName: String?)

@HiltViewModel
class ScannerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val processor: PageProcessor,
    private val pdfExporter: PdfExporter,
) : ViewModel() {

    val analyzer = LiveDocumentAnalyzer()

    private val photoDir = File(appContext.cacheDir, "pages").apply { mkdirs() }

    private val _ui = MutableStateFlow<ScanUiState>(ScanUiState.Camera)
    val ui: StateFlow<ScanUiState> = _ui.asStateFlow()

    private val _pages = MutableStateFlow<List<PageDraft>>(emptyList())
    val pages: StateFlow<List<PageDraft>> = _pages.asStateFlow()

    private val _autoCapture = MutableStateFlow(true)
    val autoCapture: StateFlow<Boolean> = _autoCapture.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val shareChannel = Channel<ShareRequest>(Channel.BUFFERED)
    val shareRequests = shareChannel.receiveAsFlow()

    /** Focal length / sensor long side, set once the camera is bound. */
    var focalRatio: Double? = null

    /** Native state of the page being edited; guarded by [lock]. */
    private val lock = Mutex()
    private var photoId: String? = null
    private var photo: Mat? = null
    private var rectified: RectifiedPage? = null

    /**
     * Final PDF JPEG per page id, rendered in the background as soon as a page is shown, so sharing only
     * assembles the PDF. An entry is valid while its [ExportKey] matches the page's current edits.
     */
    private val rendered = ConcurrentHashMap<String, Pair<ExportKey, RenderedPage>>()
    private val prerenderJobs = ConcurrentHashMap<String, Job>()

    /** One page at a time in the background, so pre-rendering never competes with the visible preview. */
    private val prerenderDispatcher = Dispatchers.Default.limitedParallelism(1)

    /** Id of the page a capture should replace ("Ulangi"); null appends. */
    private var replaceId: String? = null
    private var liveQuadAtCapture: Quad? = null
    private var renderJob: Job? = null

    fun setAutoCapture(enabled: Boolean) {
        _autoCapture.value = enabled
    }

    fun clearError() {
        _error.value = null
    }

    // ---- capture -------------------------------------------------------------------------------

    /** Returns false if a capture is already in progress. */
    fun onCaptureStarted(): Boolean {
        if (_ui.value != ScanUiState.Camera) return false
        liveQuadAtCapture = analyzer.detections.value?.quad
        analyzer.paused = true
        analyzer.onCaptured()
        _ui.value = ScanUiState.Processing("Memproses…")
        return true
    }

    fun onPhotoCaptured(image: ImageProxy) {
        viewModelScope.launch {
            try {
                val draft = withContext(Dispatchers.Default) {
                    val id = UUID.randomUUID().toString()
                    val file = File(photoDir, "$id.jpg")
                    val rotation = image.use {
                        file.writeBytes(it.jpegBytes())
                        it.imageInfo.rotationDegrees
                    }
                    lock.withLock {
                        replacePhoto(id, processor.loadPhoto(file, rotation))
                        val p = checkNotNull(photo)
                        val detected = processor.detect(p)
                        PageDraft(
                            id = id,
                            photo = file,
                            photoRotation = rotation,
                            autoQuad = detected,
                            quad = detected ?: liveQuadAtCapture ?: Quad.FULL_FRAME,
                            focalLengthPx = focalRatio?.let { it * max(p.cols(), p.rows()) },
                        )
                    }
                }
                val replacing = replaceId
                replaceId = null
                _pages.update { list ->
                    val i = list.indexOfFirst { it.id == replacing }
                    if (i >= 0) {
                        list[i].photo.delete()
                        forgetRendered(list[i].id)
                        list.toMutableList().apply { set(i, draft) }
                    } else {
                        list + draft
                    }
                }
                showPage(draft.id, reRectify = true)
            } catch (e: Exception) {
                fail("Gagal memproses foto: ${e.message}")
            }
        }
    }

    fun onCaptureFailed(message: String?) = fail("Gagal mengambil foto: $message")

    /** Adds gallery images as pages (appended), then opens the last one. */
    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        analyzer.paused = true
        _ui.value = ScanUiState.Processing("Mengimpor ${uris.size} foto…")
        viewModelScope.launch {
            try {
                val drafts = withContext(Dispatchers.Default) {
                    uris.map { uri ->
                        val id = UUID.randomUUID().toString()
                        val file = File(photoDir, "$id.jpg")
                        appContext.contentResolver.openInputStream(uri)?.use { input ->
                            file.outputStream().use { input.copyTo(it) }
                        } ?: throw IllegalStateException("Tidak bisa membaca $uri")
                        val exif = runCatching { ExifInterface(file.absolutePath) }.getOrNull()
                        val rotation = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270
                            else -> 0
                        }
                        // 35 mm-equivalent focal length: the 36 mm frame width maps to the photo's long side.
                        val focal35 = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0) ?: 0
                        lock.withLock {
                            replacePhoto(id, processor.loadPhoto(file, rotation))
                            val p = checkNotNull(photo)
                            val detected = processor.detect(p)
                            PageDraft(
                                id = id,
                                photo = file,
                                photoRotation = rotation,
                                autoQuad = detected,
                                quad = detected ?: Quad.FULL_FRAME,
                                focalLengthPx = if (focal35 > 0) focal35 / 36.0 * max(p.cols(), p.rows()) else null,
                            )
                        }
                    }
                }
                _pages.update { it + drafts }
                showPage(drafts.last().id, reRectify = true)
            } catch (e: Exception) {
                fail("Gagal mengimpor: ${e.message}")
            }
        }
    }

    /** Called when the photo picker returns nothing while no pages exist: nothing to show. */
    fun onImportCancelled() {
        if (_pages.value.isNotEmpty() && _ui.value == ScanUiState.Camera) backToReview()
    }

    fun clearMessage() {
        _ui.update { (it as? ScanUiState.Review)?.copy(message = null) ?: it }
    }

    /** Drops all pages (user confirmed discarding the session). */
    fun discardAll() {
        renderJob?.cancel()
        _pages.value.forEach {
            it.photo.delete()
            forgetRendered(it.id)
        }
        _pages.value = emptyList()
        _ui.value = ScanUiState.Camera
    }

    /** Camera -> review of the existing pages (button shown while pages exist). */
    fun backToReview() {
        val last = _pages.value.lastOrNull() ?: return
        analyzer.paused = true
        showPage(last.id, reRectify = false)
    }

    fun addPage() {
        replaceId = null
        toCamera()
    }

    fun retake() {
        replaceId = currentPage()?.id
        toCamera()
    }

    // ---- review ---------------------------------------------------------------------------------

    fun openPage(index: Int) {
        _pages.value.getOrNull(index)?.let { showPage(it.id, reRectify = false) }
    }

    fun selectPreset(preset: FilterPreset) {
        val page = currentPage() ?: return
        if (page.preset == preset) return
        updatePage(page.id) { it.copy(preset = preset) }
        showPage(page.id, reRectify = false)
    }

    fun rotate() {
        val page = currentPage() ?: return
        updatePage(page.id) { it.copy(rotation = (it.rotation + 90) % 360) }
        showPage(page.id, reRectify = true)
    }

    fun deletePage() {
        val page = currentPage() ?: return
        val index = _pages.value.indexOfFirst { it.id == page.id }
        page.photo.delete()
        forgetRendered(page.id)
        _pages.update { list -> list.filterNot { it.id == page.id } }
        val remaining = _pages.value
        if (remaining.isEmpty()) toCamera() else showPage(remaining[index.coerceAtMost(remaining.lastIndex)].id, reRectify = false)
    }

    // ---- crop -----------------------------------------------------------------------------------

    fun startCrop() {
        val page = currentPage() ?: return
        busy("Membuka crop…")
        viewModelScope.launch {
            val bitmap = withContext(Dispatchers.Default) {
                lock.withLock {
                    ensurePhoto(page)
                    // Display copy only; the crop itself is applied to the full-resolution photo.
                    processor.toBitmap(checkNotNull(photo), maxSide = 1600)
                }
            }
            _ui.value = ScanUiState.Crop(page.id, bitmap, page.quad, page.autoQuad)
        }
    }

    fun applyCrop(quad: Quad) {
        val crop = _ui.value as? ScanUiState.Crop ?: return
        updatePage(crop.pageId) { it.copy(quad = quad) }
        showPage(crop.pageId, reRectify = true)
    }

    fun cancelCrop() {
        val crop = _ui.value as? ScanUiState.Crop ?: return
        showPage(crop.pageId, reRectify = false)
    }

    // ---- export ---------------------------------------------------------------------------------

    fun savePdf() = exportPdf { file ->
        pdfExporter.saveToDownloads(file)
        "PDF tersimpan di Download/ScanMe/${file.name}"
    }

    fun sharePdf(packageName: String?) = exportPdf { file ->
        shareChannel.send(ShareRequest(pdfExporter.shareUri(file), packageName))
        null
    }

    fun onShareFailed(message: String?) {
        _error.value = "Tidak bisa membuka aplikasi tujuan: $message"
    }

    private fun exportPdf(after: suspend (File) -> String?) {
        val pages = _pages.value
        if (pages.isEmpty()) return
        busy("Menyiapkan PDF (${pages.size} halaman)…")
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.Default) {
                    val name = "Scan_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val start = System.currentTimeMillis()
                    // Reuse background renders; pages not ready yet are rendered now, two at a time.
                    val missing = pages.count { rendered[it.id]?.first != it.exportKey() }
                    val ready = pages.chunked(2).flatMap { chunk ->
                        chunk.map { page -> async { renderedFor(page) } }.awaitAll()
                    }
                    val file = pdfExporter.assemble(ready, name)
                    val ms = System.currentTimeMillis() - start
                    val result = withContext(Dispatchers.IO) { after(file) }
                    val sizeKb = file.length() / 1024
                    listOfNotNull(result, "${pages.size} hal • $sizeKb KB • siap dalam $ms ms ($missing dirender saat ini)").joinToString("\n")
                }
                _ui.update { (it as? ScanUiState.Review)?.copy(busy = null, message = message) ?: it }
            } catch (e: Exception) {
                _ui.update { (it as? ScanUiState.Review)?.copy(busy = null) ?: it }
                _error.value = "Gagal membuat PDF: ${e.message}"
            }
        }
    }

    // ---- background PDF rendering ---------------------------------------------------------------

    /** Edits that change a page's PDF image. */
    private data class ExportKey(val quad: Quad, val rotation: Int, val preset: FilterPreset)

    private fun PageDraft.exportKey() = ExportKey(quad, rotation, preset)

    private fun PageDraft.source() =
        PdfPageSource(photo, photoRotation, quad, rotation, FilterSettings(preset), focalLengthPx)

    /** The page's final JPEG: cached if its edits are unchanged, otherwise rendered now and cached. */
    private fun renderedFor(page: PageDraft): RenderedPage {
        val key = page.exportKey()
        rendered[page.id]?.takeIf { it.first == key }?.let { return it.second }
        val result = pdfExporter.renderPage(page.source())
        rendered[page.id] = key to result
        return result
    }

    /**
     * Renders [id]'s final JPEG in the background. Debounced (0.6 s) so quickly cycling through filters
     * renders only the one the user settles on.
     */
    private fun schedulePrerender(id: String) {
        val page = _pages.value.firstOrNull { it.id == id } ?: return
        if (rendered[id]?.first == page.exportKey()) return
        prerenderJobs.remove(id)?.cancel()
        prerenderJobs[id] = viewModelScope.launch(prerenderDispatcher) {
            delay(600)
            val latest = _pages.value.firstOrNull { it.id == id } ?: return@launch
            runCatching { renderedFor(latest) }
            pdfExporter.pruneRenderedPages(rendered.values.map { it.second })
        }
    }

    private fun forgetRendered(id: String) {
        prerenderJobs.remove(id)?.cancel()
        rendered.remove(id)?.second?.jpeg?.delete()
    }

    // ---- internals ------------------------------------------------------------------------------

    private fun currentPage(): PageDraft? {
        val pages = _pages.value
        return when (val s = _ui.value) {
            is ScanUiState.Review -> pages.getOrNull(s.index)
            is ScanUiState.Crop -> pages.firstOrNull { it.id == s.pageId }
            else -> null
        }
    }

    private fun updatePage(id: String, change: (PageDraft) -> PageDraft) {
        _pages.update { list -> list.map { if (it.id == id) change(it) else it } }
    }

    private fun busy(message: String) {
        _ui.update { (it as? ScanUiState.Review)?.copy(busy = message, message = null) ?: it }
    }

    /** Renders page [id] into the review screen. [reRectify] when its geometry changed. */
    private fun showPage(id: String, reRectify: Boolean) {
        renderJob?.cancel()
        if (_ui.value !is ScanUiState.Review) _ui.value = ScanUiState.Processing("Memproses…")
        renderJob = viewModelScope.launch {
            try {
                val state = withContext(Dispatchers.Default) {
                    lock.withLock {
                        val page = _pages.value.firstOrNull { it.id == id } ?: return@withContext null
                        ensurePhoto(page)
                        if (reRectify || rectified == null) {
                            rectified?.release()
                            rectified = processor.rectify(checkNotNull(photo), page.quad, page.rotation, page.focalLengthPx)
                        }
                        val r = checkNotNull(rectified)
                        val start = System.currentTimeMillis()
                        // Preview at <= 1600 px keeps filter switching fast; export renders from the photo.
                        val preview = processor.render(r, FilterSettings(page.preset), maxSide = 1600)
                        val renderMs = System.currentTimeMillis() - start
                        val thumbScale = 240.0 / max(preview.width, preview.height)
                        val thumb = Bitmap.createScaledBitmap(preview, (preview.width * thumbScale).toInt().coerceAtLeast(1), (preview.height * thumbScale).toInt().coerceAtLeast(1), true)
                        updatePage(id) { it.copy(thumbnail = thumb) }
                        ScanUiState.Review(
                            index = _pages.value.indexOfFirst { it.id == id },
                            preview = preview,
                            pageWidth = r.width,
                            pageHeight = r.height,
                            detected = page.autoQuad != null,
                            rectifyMs = r.rectifyMs,
                            renderMs = renderMs,
                        )
                    }
                } ?: return@launch
                _ui.value = state
                schedulePrerender(id)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                fail("Gagal menampilkan halaman: ${e.message}")
            }
        }
    }

    /** Must hold [lock]. Loads [page]'s photo unless it is already the current one. */
    private fun ensurePhoto(page: PageDraft) {
        if (photoId == page.id && photo != null) return
        replacePhoto(page.id, processor.loadPhoto(page.photo, page.photoRotation))
    }

    /** Must hold [lock]. */
    private fun replacePhoto(id: String, mat: Mat) {
        photo?.release()
        rectified?.release()
        rectified = null
        photo = mat
        photoId = id
    }

    private fun toCamera() {
        renderJob?.cancel()
        _ui.value = ScanUiState.Camera
        analyzer.paused = false
    }

    private fun fail(message: String) {
        _error.value = message
        toCamera()
    }

    override fun onCleared() {
        photo?.release()
        rectified?.release()
        _pages.value.forEach {
            it.photo.delete()
            forgetRendered(it.id)
        }
    }
}
