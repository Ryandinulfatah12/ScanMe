package com.ryan.scanme.core.data.processing

import android.graphics.Bitmap
import com.ryan.scanme.core.imaging.ProcessRequest
import com.ryan.scanme.core.imaging.ScanPipeline
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.rotatedClockwise
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * A flat, upright page before filtering. Owns native memory: call [release] when done.
 *
 * Kept separately from the filtered output so switching filters does not redo warping.
 */
class RectifiedPage internal constructor(
    internal val image: Mat,
    val rectifyMs: Long,
) {
    val width: Int get() = image.cols()
    val height: Int get() = image.rows()

    fun release() = image.release()
}

/** A JPEG-encoded, filtered page ready for PDF embedding. */
class EncodedPage(val jpeg: ByteArray, val width: Int, val height: Int)

/** Android-facing wrapper around the pure-JVM [ScanPipeline]: files, Bitmaps and JPEG in, Mats hidden. */
@Singleton
class PageProcessor @Inject constructor() {
    private val pipeline = ScanPipeline()

    /** Decodes a captured JPEG and rotates it upright. EXIF is ignored: CameraX reports rotation separately. */
    fun loadPhoto(file: File, rotationDegrees: Int): Mat {
        val raw = Imgcodecs.imread(file.absolutePath, Imgcodecs.IMREAD_COLOR or Imgcodecs.IMREAD_IGNORE_ORIENTATION)
        check(!raw.empty()) { "Cannot decode ${file.name}" }
        if (Math.floorMod(rotationDegrees, 360) == 0) return raw
        return raw.rotatedClockwise(rotationDegrees).also { raw.release() }
    }

    /** Page outline in [photo] (normalized), or null if nothing convincing was found. */
    fun detect(photo: Mat): Quad? = pipeline.detect(photo).best?.takeIf { it.score >= MIN_SCORE }?.quad

    /**
     * Warps the page out of [photo]. With [maxLongSide], the photo is first shrunk (area interpolation) so
     * the page comes out at about that size: warping a 12-50 MP photo only to downscale afterwards wastes
     * most of the work, and shrinking first also avoids aliasing that warping straight to a smaller size causes.
     */
    fun rectify(photo: Mat, quad: Quad, rotationDegrees: Int, focalLengthPx: Double?, maxLongSide: Int? = null): RectifiedPage {
        val start = System.currentTimeMillis()
        val q = quad.toPixels(photo.cols(), photo.rows())
        val pageLongSide = maxOf(q.tl.distanceTo(q.tr), q.bl.distanceTo(q.br), q.tl.distanceTo(q.bl), q.tr.distanceTo(q.br))
        val scale = if (maxLongSide != null && pageLongSide > maxLongSide) maxLongSide / pageLongSide else 1.0
        val source = if (scale < 1.0) {
            Mat().also { Imgproc.resize(photo, it, Size(photo.cols() * scale, photo.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) }
        } else {
            photo
        }
        try {
            val request = ProcessRequest(quad = quad, rotationDegrees = rotationDegrees, focalLengthPx = focalLengthPx?.times(scale))
            return RectifiedPage(pipeline.rectify(source, request), System.currentTimeMillis() - start)
        } finally {
            if (source !== photo) source.release()
        }
    }

    /** Applies the filter and returns a Bitmap, downscaled so the long side is <= [maxSide] if given. */
    fun render(page: RectifiedPage, settings: FilterSettings, maxSide: Int? = null): Bitmap {
        val filtered = filtered(page, settings, maxSide)
        return filtered.toBitmap().also { filtered.release() }
    }

    /**
     * Filtered page as JPEG for PDF export.
     *
     * Defaults: long side <= 2339 px (A4 at 200 DPI: sharp on screen and in print, small enough for chat
     * apps) and quality 85 (no visible artifacts on text; roughly 3-5x smaller than quality 95).
     */
    fun encodeJpeg(page: RectifiedPage, settings: FilterSettings, maxSide: Int = 2339, quality: Int = 85): EncodedPage {
        val filtered = filtered(page, settings, maxSide)
        val buffer = MatOfByte()
        val params = MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, quality)
        try {
            check(Imgcodecs.imencode(".jpg", filtered, buffer, params)) { "JPEG encoding failed" }
            return EncodedPage(buffer.toArray(), filtered.cols(), filtered.rows())
        } finally {
            filtered.release()
            buffer.release()
            params.release()
        }
    }

    /** Any BGR Mat as a Bitmap (e.g. the photo shown on the crop screen), downscaled to [maxSide]. */
    fun toBitmap(bgr: Mat, maxSide: Int? = null): Bitmap {
        val scaled = scaledCopy(bgr, maxSide)
        return scaled.toBitmap().also { scaled.release() }
    }

    private fun filtered(page: RectifiedPage, settings: FilterSettings, maxSide: Int?): Mat {
        val source = scaledCopy(page.image, maxSide)
        return pipeline.filter(source, settings).also { source.release() }
    }

    private fun scaledCopy(src: Mat, maxSide: Int?): Mat {
        val long = max(src.cols(), src.rows())
        if (maxSide == null || long <= maxSide) return src.clone()
        val scale = maxSide.toDouble() / long
        return Mat().also { Imgproc.resize(src, it, Size(src.cols() * scale, src.rows() * scale), 0.0, 0.0, Imgproc.INTER_AREA) }
    }

    private fun Mat.toBitmap(): Bitmap {
        val rgba = Mat()
        Imgproc.cvtColor(this, rgba, Imgproc.COLOR_BGR2RGBA)
        val bitmap = Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(rgba, bitmap)
        rgba.release()
        return bitmap
    }

    private companion object {
        /** Same threshold as live detection: a real page border scores ~0.9+. */
        const val MIN_SCORE = 0.6
    }
}
