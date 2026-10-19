package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.DebugSink
import com.ryan.scanme.core.imaging.histogram8U
import com.ryan.scanme.core.imaging.longSide
import com.ryan.scanme.core.imaging.oddAtLeast
import com.ryan.scanme.core.imaging.percentile
import com.ryan.scanme.core.imaging.releasing
import com.ryan.scanme.core.imaging.resizeLongSide
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.min

/**
 * Removes shadows and uneven lighting by dividing the page by an estimate of its blank-paper background.
 *
 * Works in Lab so colour does not shift: L is divided by the background L (brightness), while the a/b
 * background (the paper's colour cast, e.g. warm indoor light) is subtracted, making paper neutral
 * without touching ink and stamp hues.
 *
 * Background estimate, at <= [workLongSide] px (lighting is low-frequency; this keeps it cheap), then
 * upsampled, like the reference app's low-resolution shadow model:
 * 1. Object mask: regions enclosed by sharp edges whose interior is clearly darker than the paper around
 *    them (filled logos, header bars, photos). Shadows have soft boundaries, so they are never enclosed by
 *    sharp edges and stay part of the background. Without this step a dark area larger than the filters
 *    below is mistaken for shadow and washed out to grey.
 * 2. Push-pull fill: object pixels are replaced by values interpolated from the surrounding paper.
 * 3. Dilate L (kernel 1.2% of the long side, wider than body-text strokes): replaces remaining ink by
 *    nearby paper. Then median blur (3%) removes leftover islands and paper texture while keeping soft
 *    shadow gradients.
 *
 * Known limitation: hard-edged shadows (direct sunlight) look like objects and are not removed.
 */
class IlluminationNormalizer(
    private val workLongSide: Int = 1024,
    /** Target paper brightness on the 8-bit L scale (250 ~ L* 98). */
    private val paperL: Double = 250.0,
    private val dilateFraction: Double = 0.012,
    private val medianFraction: Double = 0.03,
    /** An enclosed region darker than this fraction of its surrounding paper is an object, not paper. */
    private val objectDarkness: Double = 0.85,
    /** Regions smaller than this (fraction of the page) are left to the dilate step (letters, counters). */
    private val minObjectFraction: Double = 0.0004,
    /** Regions larger than this are not treated as objects (a frame around the whole page encloses paper). */
    private val maxObjectFraction: Double = 0.3,
) {
    /** Returns a new BGR Mat. */
    fun normalize(bgr: Mat, debug: DebugSink? = null): Mat {
        val lab = Mat()
        Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
        val (small, _) = resizeLongSide(lab, workLongSide)
        val smallCh = mutableListOf<Mat>()
        val ch = mutableListOf<Mat>()
        Core.split(small, smallCh)
        Core.split(lab, ch)
        small.release()

        val valid = paperMask(smallCh[0])
        debug?.stage("illumination/paper-mask", valid)
        releasing(valid) {
            for (i in smallCh.indices) {
                val filled = pushPullFill(smallCh[i], valid)
                smallCh[i].release()
                smallCh[i] = filled
            }
        }

        val side = smallCh[0].longSide()
        val dilateK = oddAtLeast(side * dilateFraction, 5)
        // medianBlur on 8-bit supports any odd size; 255 caps cost on very large inputs.
        val medianK = oddAtLeast(side * medianFraction, 9).coerceAtMost(255)
        val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(dilateK.toDouble(), dilateK.toDouble()))
        releasing(kernel) { Imgproc.dilate(smallCh[0], smallCh[0], kernel) }
        for (c in smallCh) Imgproc.medianBlur(c, c, medianK)

        val full = Size(bgr.cols().toDouble(), bgr.rows().toDouble())
        for (c in smallCh) Imgproc.resize(c, c, full, 0.0, 0.0, Imgproc.INTER_LINEAR)
        debug?.stage("illumination/background-L", smallCh[0])

        // L' = L * paperL / bgL (saturating), a' = a - (bgA - 128), b' = b - (bgB - 128).
        Core.divide(ch[0], smallCh[0], ch[0], paperL)
        Core.addWeighted(ch[1], 1.0, smallCh[1], -1.0, 128.0, ch[1])
        Core.addWeighted(ch[2], 1.0, smallCh[2], -1.0, 128.0, ch[2])

        Core.merge(ch, lab)
        val out = Mat()
        Imgproc.cvtColor(lab, out, Imgproc.COLOR_Lab2BGR)
        (ch + smallCh).forEach { it.release() }
        lab.release()
        debug?.stage("illumination/normalized", out)
        return out
    }

    /**
     * 255 where a pixel may be used as background sample, 0 on sharp edges and inside dark enclosed objects.
     * Thresholds 30/90 on 8-bit L at <= 1024 px: ink and object borders give gradients of 100+, a soft
     * shadow edge stays well under 30.
     */
    private fun paperMask(l: Mat): Mat {
        val w = l.cols()
        val h = l.rows()
        val n = w * h
        val edges = Mat()
        val notEdges = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        return releasing(edges, notEdges, labels, stats, centroids) {
            Imgproc.GaussianBlur(l, edges, Size(3.0, 3.0), 0.0)
            Imgproc.Canny(edges, edges, 30.0, 90.0, 3, true)
            // Close 1 px gaps so object outlines separate their interior from the paper.
            val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            releasing(k) { Imgproc.dilate(edges, edges, k) }
            Core.bitwise_not(edges, notEdges)
            val count = Imgproc.connectedComponentsWithStats(notEdges, labels, stats, centroids, 4, CvType.CV_32S)

            val lab = IntArray(n).also { labels.get(0, 0, it) }
            val lum = ByteArray(n).also { l.get(0, 0, it) }
            val area = IntArray(count) { stats.get(it, Imgproc.CC_STAT_AREA)[0].toInt() }
            // Label 0 = edge pixels. The paper is the largest remaining component.
            val main = (1 until count).maxByOrNull { area[it] } ?: return@releasing Mat(h, w, CvType.CV_8UC1, org.opencv.core.Scalar(255.0))

            val sum = LongArray(count)
            for (i in 0 until n) sum[lab[i]] += lum[i].toInt() and 0xFF
            val mainMask = Mat()
            Core.compare(labels, org.opencv.core.Scalar(main.toDouble()), mainMask, Core.CMP_EQ)
            val paperRef = releasing(mainMask) { percentile(histogram8U(l, mainMask), 0.9).toDouble() }

            val minArea = (minObjectFraction * n).toInt()
            val maxArea = (maxObjectFraction * n).toInt()
            val isObject = BooleanArray(count)
            for (c in 1 until count) {
                if (c == main || area[c] < minArea || area[c] > maxArea) continue
                val mean = sum[c].toDouble() / area[c]
                val ring = ringMean(c, main, stats, lab, lum, w, h) ?: paperRef
                isObject[c] = mean < objectDarkness * ring
            }

            val out = ByteArray(n)
            for (i in 0 until n) {
                val c = lab[i]
                out[i] = if (c == 0 || isObject[c]) 0 else 255.toByte()
            }
            Mat(h, w, CvType.CV_8UC1).also { it.put(0, 0, out) }
        }
    }

    /** Mean L of main-paper pixels in the component's bounding box grown by 25% (at least 8 px). */
    private fun ringMean(c: Int, main: Int, stats: Mat, lab: IntArray, lum: ByteArray, w: Int, h: Int): Double? {
        val x = stats.get(c, Imgproc.CC_STAT_LEFT)[0].toInt()
        val y = stats.get(c, Imgproc.CC_STAT_TOP)[0].toInt()
        val bw = stats.get(c, Imgproc.CC_STAT_WIDTH)[0].toInt()
        val bh = stats.get(c, Imgproc.CC_STAT_HEIGHT)[0].toInt()
        val mx = max(8, bw / 4)
        val my = max(8, bh / 4)
        var s = 0L
        var k = 0
        for (yy in max(0, y - my) until min(h, y + bh + my)) {
            val row = yy * w
            for (xx in max(0, x - mx) until min(w, x + bw + mx)) {
                if (lab[row + xx] == main) {
                    s += lum[row + xx].toInt() and 0xFF
                    k++
                }
            }
        }
        return if (k >= 20) s.toDouble() / k else null
    }

    /**
     * Push-pull interpolation (Gortler et al. 1996): average valid pixels down an image pyramid, then fill
     * each level's holes from the coarser level on the way up. Fills holes of any size in O(n).
     */
    private fun pushPullFill(src: Mat, valid: Mat): Mat {
        val value = Mat()
        val weight = Mat()
        src.convertTo(value, CvType.CV_32F)
        valid.convertTo(weight, CvType.CV_32F, 1.0 / 255.0)
        Core.multiply(value, weight, value) // premultiplied

        val values = mutableListOf(value)
        val weights = mutableListOf(weight)
        while (min(values.last().cols(), values.last().rows()) > 4) {
            val v = Mat()
            val wt = Mat()
            val size = Size(max(1, values.last().cols() / 2).toDouble(), max(1, values.last().rows() / 2).toDouble())
            Imgproc.resize(values.last(), v, size, 0.0, 0.0, Imgproc.INTER_AREA)
            Imgproc.resize(weights.last(), wt, size, 0.0, 0.0, Imgproc.INTER_AREA)
            values += v
            weights += wt
        }

        // Coarsest level (<= 4 px): plain weighted mean of the valid pixels in each cell.
        var estimate = Mat()
        val safeWeight = Mat()
        Core.max(weights.last(), org.opencv.core.Scalar(1e-6), safeWeight)
        Core.divide(values.last(), safeWeight, estimate)
        safeWeight.release()
        for (level in values.size - 2 downTo 0) {
            val up = Mat()
            Imgproc.resize(estimate, up, values[level].size(), 0.0, 0.0, Imgproc.INTER_LINEAR)
            estimate.release()
            // est = v + (1 - w) * up   (v is premultiplied: w * mean_valid)
            val oneMinusW = Mat()
            weights[level].convertTo(oneMinusW, CvType.CV_32F, -1.0, 1.0)
            Core.max(oneMinusW, org.opencv.core.Scalar(0.0), oneMinusW)
            Core.multiply(up, oneMinusW, up)
            estimate = Mat()
            Core.add(values[level], up, estimate)
            up.release()
            oneMinusW.release()
        }
        val out = Mat()
        estimate.convertTo(out, CvType.CV_8U)
        estimate.release()
        (values + weights).forEach { it.release() }
        return out
    }
}
