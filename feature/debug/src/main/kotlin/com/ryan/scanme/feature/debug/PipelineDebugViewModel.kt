package com.ryan.scanme.feature.debug

import android.content.Context
import android.graphics.Bitmap
import android.media.ExifInterface
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ryan.scanme.core.imaging.DebugCollector
import com.ryan.scanme.core.imaging.ProcessRequest
import com.ryan.scanme.core.imaging.ScanPipeline
import com.ryan.scanme.core.imaging.enhance.FilterEngine
import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.enhance.IlluminationNormalizer
import com.ryan.scanme.core.imaging.enhance.Sauvola
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.metrics.QualityMetrics
import com.ryan.scanme.core.imaging.rotatedClockwise
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import javax.inject.Inject
import kotlin.math.max

/**
 * Tunable filter parameters. Defaults mirror the constructor defaults of [FilterEngine],
 * [IlluminationNormalizer] and [Sauvola]; keep them in sync so "Reset" shows what the app ships.
 */
data class DebugParams(
    val preset: FilterPreset = FilterPreset.AUTO_COLOR,
    val claheClip: Double = 1.5,
    val whitePoint: Double = 238.0,
    val sharpenAmount: Double = 0.6,
    val chromaBoost: Double = 1.15,
    val definiteInkL: Double = 110.0,
    val dilateFraction: Double = 0.012,
    val medianFraction: Double = 0.03,
    val objectDarkness: Double = 0.85,
    val sauvolaK: Double = 0.25,
    val sauvolaWindow: Double = 0.02,
) {
    fun filterEngine() = FilterEngine(
        illumination = IlluminationNormalizer(
            dilateFraction = dilateFraction,
            medianFraction = medianFraction,
            objectDarkness = objectDarkness,
        ),
        sauvola = Sauvola(k = sauvolaK, windowFraction = sauvolaWindow),
        claheClip = claheClip,
        whitePoint = whitePoint,
        sharpenAmount = sharpenAmount,
        chromaBoost = chromaBoost,
        definiteInkL = definiteInkL,
    )
}

class StageImage(val name: String, val bitmap: Bitmap, val info: String)

data class DebugUiState(
    val hasImage: Boolean = false,
    val busy: String? = null,
    val error: String? = null,
    val params: DebugParams = DebugParams(),
    val stages: List<StageImage> = emptyList(),
    val timings: List<Pair<String, Long>> = emptyList(),
    val detection: String? = null,
    /** Median L* of the filtered paper (illumination paper mask); AGENTS.md target > 90. */
    val paperLStar: Double? = null,
    /** Fraction of paper pixels below L* 85 (leftover shadow). */
    val paperBelow85: Double? = null,
)

/**
 * Runs the real pipeline on one gallery photo and shows every [DebugCollector] stage.
 * Geometry (decode, detect, rectify) runs once per photo; the filter re-runs on each parameter change.
 */
@HiltViewModel
class PipelineDebugViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _ui = MutableStateFlow(DebugUiState())
    val ui: StateFlow<DebugUiState> = _ui.asStateFlow()

    private val pipeline = ScanPipeline()

    /** Native state, guarded by [lock]. */
    private val lock = Mutex()
    private var filterInput: Mat? = null
    private var geometryStages: List<StageImage> = emptyList()
    private var geometryTimings: List<Pair<String, Long>> = emptyList()
    private var filterJob: Job? = null

    fun load(uri: Uri) {
        filterJob?.cancel()
        _ui.update { it.copy(busy = "Memproses foto…", error = null) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.Default) { lock.withLock { runGeometry(uri) } }
                _ui.update { it.copy(hasImage = true) }
                refilter(debounceMs = 0)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Gagal: ${e.message}") }
            }
        }
    }

    fun setParams(params: DebugParams) {
        _ui.update { it.copy(params = params) }
        if (_ui.value.hasImage) refilter(debounceMs = 250)
    }

    private fun runGeometry(uri: Uri) {
        filterInput?.release()
        filterInput = null

        var t = System.currentTimeMillis()
        val bytes = appContext.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Tidak bisa membaca $uri")
        val exif = runCatching { ExifInterface(bytes.inputStream()) }.getOrNull()
        val rotation = when (exif?.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
        val raw = MatOfByte(*bytes).let { buf ->
            Imgcodecs.imdecode(buf, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION).also { buf.release() }
        }
        check(!raw.empty()) { "Format gambar tidak didukung" }
        val photo = if (rotation == 0) raw else raw.rotatedClockwise(rotation).also { raw.release() }
        val decodeMs = System.currentTimeMillis() - t

        val collector = DebugCollector()
        try {
            t = System.currentTimeMillis()
            val detection = pipeline.detect(photo, collector)
            val detectMs = System.currentTimeMillis() - t
            // Same acceptance threshold as PageProcessor.MIN_SCORE.
            val best = detection.best?.takeIf { it.score >= 0.6 }
            val stages = collector.stages.map { it.image.toStage(it.name) }.toMutableList()
            stages += overlay(photo, detection.candidates.map { it.quad }, best?.quad).let { mat ->
                mat.toStage("detect/result").also { mat.release() }
            }
            collector.release()

            // Same focal estimate as gallery import: 35 mm-equivalent focal / 36 mm frame width.
            val focal35 = exif?.getAttributeInt(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, 0) ?: 0
            val focalPx = if (focal35 > 0) focal35 / 36.0 * max(photo.cols(), photo.rows()) else null

            t = System.currentTimeMillis()
            val rectified = pipeline.rectify(photo, ProcessRequest(quad = best?.quad ?: Quad.FULL_FRAME, dewarp = true, focalLengthPx = focalPx), collector)
            val rectifyMs = System.currentTimeMillis() - t
            collector.stages.mapTo(stages) { it.image.toStage(it.name) }

            // Filter runs at the review-screen preview size (ScannerViewModel renders at <= 1600 px).
            filterInput = rectified.scaledTo(PREVIEW_SIDE).also { if (it !== rectified) rectified.release() }

            geometryStages = listOf(photo.toStage("input")) + stages
            geometryTimings = listOf(
                "decode ${photo.cols()}×${photo.rows()}" to decodeMs,
                "detect" to detectMs,
                "rectify ${rectified.cols()}×${rectified.rows()}" to rectifyMs,
            )
            _ui.update {
                it.copy(
                    detection = detection.best?.let { b ->
                        "skor ${"%.2f".format(b.score)} (${b.source.name.lowercase()})" +
                            (if (best == null) " — di bawah 0,6, pakai full frame" else "") +
                            " · ${detection.candidates.size} kandidat"
                    } ?: "tidak ada kandidat — pakai full frame",
                )
            }
        } finally {
            collector.release()
            photo.release()
        }
    }

    private fun refilter(debounceMs: Long) {
        filterJob?.cancel()
        filterJob = viewModelScope.launch {
            delay(debounceMs)
            val params = _ui.value.params
            _ui.update { it.copy(busy = "Filter…") }
            try {
                val result = withContext(Dispatchers.Default) {
                    lock.withLock {
                        val input = filterInput ?: return@withLock null
                        val collector = DebugCollector()
                        try {
                            val t = System.currentTimeMillis()
                            val out = params.filterEngine().apply(input, FilterSettings(params.preset), collector)
                            val filterMs = System.currentTimeMillis() - t
                            val paper = collector.stages.firstOrNull { it.name == "illumination/paper-mask" }?.image
                            val metrics = paper?.let { paperMetrics(out, it) }
                            // Output at full preview size so thin strokes can be inspected when zoomed.
                            val stages = collector.stages.map { it.image.toStage(it.name) } + out.toStage("output", PREVIEW_SIDE)
                            out.release()
                            FilterResult(stages, filterMs, metrics)
                        } finally {
                            collector.release()
                        }
                    }
                } ?: return@launch
                _ui.update {
                    it.copy(
                        busy = null,
                        stages = geometryStages + result.stages,
                        timings = geometryTimings + ("filter ${params.preset.name.lowercase()} @${PREVIEW_SIDE}px" to result.filterMs),
                        paperLStar = result.metrics?.first,
                        paperBelow85 = result.metrics?.second,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _ui.update { it.copy(busy = null, error = "Filter gagal: ${e.message}") }
            }
        }
    }

    private class FilterResult(val stages: List<StageImage>, val filterMs: Long, val metrics: Pair<Double, Double>?)

    /** Median L* and fraction below L* 85 over the paper mask (computed at the normalizer's work size). */
    private fun paperMetrics(out: Mat, paperMask: Mat): Pair<Double, Double> {
        val mask = Mat()
        try {
            Imgproc.resize(paperMask, mask, out.size(), 0.0, 0.0, Imgproc.INTER_NEAREST)
            return QualityMetrics.medianLStar(out, mask) to QualityMetrics.fractionDarkerThan(out, mask, 85.0)
        } finally {
            mask.release()
        }
    }

    /** All candidates in grey, the accepted outline in green, on a <= 1280 px copy. */
    private fun overlay(photo: Mat, candidates: List<Quad>, best: Quad?): Mat {
        val img = photo.scaledTo(1280).let { if (it === photo) photo.clone() else it }
        val thickness = max(2, img.cols() / 300)
        fun draw(q: Quad, color: Scalar, width: Int) {
            val p = q.toPixels(img.cols(), img.rows())
            val poly = MatOfPoint(Point(p.tl.x, p.tl.y), Point(p.tr.x, p.tr.y), Point(p.br.x, p.br.y), Point(p.bl.x, p.bl.y))
            Imgproc.polylines(img, listOf(poly), true, color, width)
            poly.release()
        }
        candidates.forEach { draw(it, Scalar(160.0, 160.0, 160.0), thickness / 2 + 1) }
        best?.let { draw(it, Scalar(80.0, 200.0, 60.0), thickness) }
        return img
    }

    override fun onCleared() {
        filterInput?.release()
    }

    private companion object {
        const val PREVIEW_SIDE = 1600
        const val STAGE_SIDE = 720
    }

    /** Returns this Mat if already small enough, otherwise a new INTER_AREA-shrunk copy. */
    private fun Mat.scaledTo(maxSide: Int): Mat {
        val scale = maxSide.toDouble() / max(cols(), rows())
        if (scale >= 1.0) return this
        return Mat().also { Imgproc.resize(this, it, Size(), scale, scale, Imgproc.INTER_AREA) }
    }

    /** Any stage Mat (gray/BGR/BGRA, any depth) as a small RGBA Bitmap. */
    private fun Mat.toStage(name: String, maxSide: Int = STAGE_SIDE): StageImage {
        val info = "${cols()}×${rows()} · ${channels()}ch" + if (depth() != CvType.CV_8U) " · depth ${depth()}" else ""
        val small = scaledTo(maxSide)
        val u8 = if (small.depth() == CvType.CV_8U) small else Mat().also {
            Core.normalize(small, it, 0.0, 255.0, Core.NORM_MINMAX, CvType.CV_8U)
        }
        val rgba = Mat()
        val code = when (u8.channels()) {
            1 -> Imgproc.COLOR_GRAY2RGBA
            4 -> Imgproc.COLOR_BGRA2RGBA
            else -> Imgproc.COLOR_BGR2RGBA
        }
        Imgproc.cvtColor(u8, rgba, code)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        if (u8 !== small) u8.release()
        if (small !== this) small.release()
        return StageImage(name, bitmap, info)
    }
}
