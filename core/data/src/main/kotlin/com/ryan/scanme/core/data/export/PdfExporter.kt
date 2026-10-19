package com.ryan.scanme.core.data.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.ryan.scanme.core.data.processing.PageProcessor
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Everything needed to re-render one page from its original photo. */
data class PdfPageSource(
    val photo: File,
    val photoRotation: Int,
    val quad: Quad,
    val rotationDegrees: Int,
    val filter: FilterSettings,
    val focalLengthPx: Double?,
)

/** A page already rendered to its final PDF JPEG, stored in the app cache. */
class RenderedPage(val jpeg: File, val width: Int, val height: Int)

@Singleton
class PdfExporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val processor: PageProcessor,
) {
    private val shareDir get() = File(context.cacheDir, "shared").apply { mkdirs() }
    private val pageDir get() = File(context.cacheDir, "pdfpages").apply { mkdirs() }

    /**
     * Renders one page to its final JPEG (~200 DPI on A4, quality 85). Slow part of export (decode, warp,
     * filter, encode): the app runs it in the background as soon as a page is final, so sharing only has
     * to [assemble] the PDF.
     */
    fun renderPage(src: PdfPageSource): RenderedPage {
        val photo = processor.loadPhoto(src.photo, src.photoRotation)
        val page = try {
            processor.rectify(photo, src.quad, src.rotationDegrees, src.focalLengthPx, maxLongSide = EXPORT_LONG_SIDE)
        } finally {
            photo.release()
        }
        val encoded = try {
            processor.encodeJpeg(page, src.filter, maxSide = EXPORT_LONG_SIDE)
        } finally {
            page.release()
        }
        val file = File(pageDir, "${UUID.randomUUID()}.jpg")
        file.writeBytes(encoded.jpeg)
        return RenderedPage(file, encoded.width, encoded.height)
    }

    /**
     * Writes the PDF into the app cache. Fast: no image work.
     *
     * Earlier PDFs are kept for [SHARED_TTL_MS]: the receiving app (WhatsApp) may still be reading one in a
     * background upload long after its share screen closed, and deleting it would stall that upload.
     */
    fun assemble(pages: List<RenderedPage>, name: String): File {
        pruneSharedPdfs()
        val pdfPages = pages.map { p ->
            val (wPt, hPt) = pageSizePt(p.width, p.height)
            PdfImagePage(p.jpeg.readBytes(), p.width, p.height, wPt, hPt)
        }
        var file = File(shareDir, "$name.pdf")
        var n = 2
        while (file.exists()) file = File(shareDir, "$name ($n).pdf").also { n++ }
        // Write to a temp name and rename, so a reader never sees a half-written PDF.
        val tmp = File(shareDir, "${file.name}.tmp")
        tmp.outputStream().buffered().use { JpegPdfWriter.write(pdfPages, it) }
        check(tmp.renameTo(file)) { "Cannot finalize ${file.name}" }
        return file
    }

    /** Removes shared PDFs older than [SHARED_TTL_MS] (and leftover temp files). */
    fun pruneSharedPdfs() {
        val cutoff = System.currentTimeMillis() - SHARED_TTL_MS
        shareDir.listFiles()?.filter { it.name.endsWith(".tmp") || it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** Removes cached page JPEGs no longer referenced by [keep]. */
    fun pruneRenderedPages(keep: Collection<RenderedPage>) {
        val live = keep.map { it.jpeg.name }.toSet()
        pageDir.listFiles()?.filter { it.name !in live }?.forEach { it.delete() }
    }

    /** content:// URI other apps (WhatsApp, Drive, mail) can read. */
    fun shareUri(file: File): Uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    /** Copies the PDF to Download/ScanMe (Android 10+), or the app's Documents folder on older versions. */
    fun saveToDownloads(file: File): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(MediaStore.Downloads.MIME_TYPE, "application/pdf")
                put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ScanMe")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("MediaStore insert failed")
            try {
                resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                    ?: throw IOException("Cannot open $uri")
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            return uri
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
        val target = File(dir, file.name)
        file.copyTo(target, overwrite = true)
        return Uri.fromFile(target)
    }

    /**
     * Page size in points. Pages within 1.5% of A4 or Letter get that exact size (prints 1:1); others keep
     * their own aspect with the short side of A4 (595 pt), so nothing is stretched or letterboxed.
     */
    private fun pageSizePt(width: Int, height: Int): Pair<Double, Double> {
        val ratio = max(width, height).toDouble() / min(width, height)
        val (short, long) = when {
            abs(ratio - sqrt(2.0)) / sqrt(2.0) < 0.015 -> 595.28 to 841.89
            abs(ratio - 11.0 / 8.5) / (11.0 / 8.5) < 0.015 -> 612.0 to 792.0
            else -> 595.28 to 595.28 * ratio
        }
        return if (width <= height) short to long else long to short
    }

    private companion object {
        /** A4 at 200 DPI: sharp on screen and in print, small enough for chat apps. */
        const val EXPORT_LONG_SIDE = 2339

        /** Long enough for any queued chat upload to finish, short enough not to pile up in the cache. */
        const val SHARED_TTL_MS = 24L * 60 * 60 * 1000
    }
}
