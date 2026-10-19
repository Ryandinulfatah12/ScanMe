package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.DebugSink
import com.ryan.scanme.core.imaging.histogram8U
import com.ryan.scanme.core.imaging.longSide
import com.ryan.scanme.core.imaging.percentile
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

enum class FilterPreset { ORIGINAL, AUTO_COLOR, GRAYSCALE, BLACK_WHITE }

/** [intensity] 0..1 blends the filtered page with the plain rectified page. */
data class FilterSettings(val preset: FilterPreset = FilterPreset.AUTO_COLOR, val intensity: Double = 1.0)

/**
 * The four filter presets. All operate on the already rectified page.
 *
 * - ORIGINAL: white balance only (keeps the photo look).
 * - AUTO_COLOR: illumination normalization -> mild CLAHE on L -> levels stretch (ink to black, paper to
 *   white) -> unsharp mask on L -> slight chroma boost. Equivalent to the reference app's
 *   "Lab + L-channel histogram stretch" approach.
 * - GRAYSCALE: AUTO_COLOR's L channel.
 * - BLACK_WHITE: Sauvola on the normalized L, then removal of specks smaller than a dot.
 */
class FilterEngine(
    private val illumination: IlluminationNormalizer = IlluminationNormalizer(),
    private val whiteBalance: WhiteBalance = WhiteBalance(),
    private val sauvola: Sauvola = Sauvola(),
    /** CLAHE clip 1.5: near-identity on blank paper, so it lifts faint text without amplifying texture. */
    private val claheClip: Double = 1.5,
    /** Darkest 0.5% maps to black: robust to a few noisy pixels, still uses the real ink density. */
    private val blackPercentile: Double = 0.005,
    /** Normalized paper sits around L=250; everything above this becomes pure white (L* 100). */
    private val whitePoint: Double = 238.0,
    private val sharpenAmount: Double = 0.6,
    private val chromaBoost: Double = 1.15,
    /** Specks below this fraction of the page area are removed in B&W (24 px on a 12 MP page). */
    private val speckAreaFraction: Double = 2e-6,
    /**
     * In B&W, anything darker than this (8-bit L, ~L* 43) is ink regardless of Sauvola. Sauvola compares to
     * the local mean, so inside a dark area wider than its window (filled logo, bold heading, table header)
     * the local mean is itself dark and the interior turns white. Paper is ~255 after normalization, so
     * this cannot misfire on background.
     */
    private val definiteInkL: Double = 110.0,
) {
    fun apply(page: Mat, settings: FilterSettings, debug: DebugSink? = null): Mat {
        val filtered = when (settings.preset) {
            FilterPreset.ORIGINAL -> whiteBalance.apply(page)
            FilterPreset.AUTO_COLOR -> autoColor(page, debug)
            FilterPreset.GRAYSCALE -> grayscale(page, debug)
            FilterPreset.BLACK_WHITE -> blackWhite(page, debug)
        }
        debug?.stage("filter/${settings.preset.name.lowercase()}", filtered)
        val i = settings.intensity.coerceIn(0.0, 1.0)
        if (i >= 1.0) return filtered
        val out = Mat()
        releasing(filtered) { Core.addWeighted(filtered, i, page, 1.0 - i, 0.0, out) }
        return out
    }

    private fun autoColor(page: Mat, debug: DebugSink?): Mat {
        val lab = enhancedLab(page, debug, sharpen = true)
        val out = Mat()
        releasing(lab) { Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR) }
        return out
    }

    private fun grayscale(page: Mat, debug: DebugSink?): Mat {
        val l = enhancedL(page, debug, sharpen = true)
        val out = Mat()
        releasing(l) { Imgproc.cvtColor(l, out, Imgproc.COLOR_GRAY2BGR) }
        return out
    }

    private fun blackWhite(page: Mat, debug: DebugSink?): Mat {
        // No sharpening before thresholding: unsharp halos turn into false ink around strokes.
        val l = enhancedL(page, debug, sharpen = false)
        val binary = sauvola.binarize(l)
        debug?.stage("filter/sauvola-raw", binary)
        val notDark = Mat()
        releasing(l, notDark) {
            Imgproc.threshold(l, notDark, definiteInkL, 255.0, Imgproc.THRESH_BINARY)
            Core.bitwise_and(binary, notDark, binary)
        }
        removeSpecks(binary, max(4, (binary.total() * speckAreaFraction).roundToInt()))
        val out = Mat()
        releasing(binary) { Imgproc.cvtColor(binary, out, Imgproc.COLOR_GRAY2BGR) }
        return out
    }

    private fun enhancedL(page: Mat, debug: DebugSink?, sharpen: Boolean): Mat {
        val lab = enhancedLab(page, debug, sharpen, chroma = false)
        val ch = mutableListOf<Mat>()
        releasing(lab) { Core.split(lab, ch) }
        ch[1].release()
        ch[2].release()
        return ch[0]
    }

    private fun enhancedLab(page: Mat, debug: DebugSink?, sharpen: Boolean, chroma: Boolean = true): Mat {
        val normalized = illumination.normalize(page, debug)
        val lab = Mat()
        releasing(normalized) { Imgproc.cvtColor(normalized, lab, Imgproc.COLOR_BGR2Lab) }
        val ch = mutableListOf<Mat>()
        Core.split(lab, ch)
        val l = ch[0]

        Imgproc.createCLAHE(claheClip, Size(8.0, 8.0)).apply(l, l)
        stretchLevels(l)
        if (sharpen) unsharp(l)
        debug?.stage("filter/L-enhanced", l)

        if (chroma) {
            // a,b are offset by 128 in 8-bit Lab: x' = 128 + (x - 128) * s
            for (c in listOf(ch[1], ch[2])) c.convertTo(c, -1, chromaBoost, 128.0 * (1 - chromaBoost))
        }
        Core.merge(ch, lab)
        ch.forEach { it.release() }
        return lab
    }

    private fun stretchLevels(l: Mat) {
        val black = percentile(histogram8U(l), blackPercentile).toDouble()
        val white = max(whitePoint, black + 32)
        val lut = Mat(1, 256, CvType.CV_8U)
        val values = ByteArray(256) { v -> ((v - black) * 255.0 / (white - black)).coerceIn(0.0, 255.0).roundToInt().toByte() }
        releasing(lut) {
            lut.put(0, 0, values)
            Core.LUT(l, lut, l)
        }
    }

    /** sigma scales with page size: ~1 px at 1500 px, ~2.7 px at 4000 px, matching stroke width. */
    private fun unsharp(l: Mat) {
        val blurred = Mat()
        releasing(blurred) {
            val sigma = max(1.0, l.longSide() / 1500.0)
            Imgproc.GaussianBlur(l, blurred, Size(0.0, 0.0), sigma)
            Core.addWeighted(l, 1.0 + sharpenAmount, blurred, -sharpenAmount, 0.0, l)
        }
    }

    /** Turns ink components (value 0) smaller than [minArea] px into paper. Bulk arrays: no per-pixel JNI. */
    private fun removeSpecks(binary: Mat, minArea: Int) {
        val ink = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        releasing(ink, labels, stats, centroids) {
            Core.bitwise_not(binary, ink)
            val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
            val area = IntArray(n) { stats.get(it, Imgproc.CC_STAT_AREA)[0].toInt() }
            val drop = BooleanArray(n) { it != 0 && area[it] < minArea }
            if (drop.none { it }) return
            val lab = IntArray(labels.total().toInt())
            labels.get(0, 0, lab)
            val px = ByteArray(binary.total().toInt())
            binary.get(0, 0, px)
            for (i in lab.indices) if (drop[lab[i]]) px[i] = 255.toByte()
            binary.put(0, 0, px)
        }
    }
}
