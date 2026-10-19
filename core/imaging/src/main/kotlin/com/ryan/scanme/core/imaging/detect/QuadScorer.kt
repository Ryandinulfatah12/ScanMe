package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Scores a candidate quad against the image it was found in.
 *
 * Per side, two kinds of evidence (averaged):
 * - Edge support: fraction of samples with an image edge within [searchRadius] px along the side's normal.
 * - Contrast consistency: fraction of samples where the inside of the quad is brighter (or darker) than the
 *   outside, with the same sign along the whole side. A real page border separates two different
 *   surfaces, so the sign holds everywhere; a line that merely runs through text (Hough finds those, since
 *   characters happen to align) alternates randomly and scores ~0.5 or less.
 *
 * Sides are combined as 0.5*mean + 0.5*min, which punishes quads with one unsupported side (typical when a
 * hull swallows background clutter). Angle: 1-|cos| rewards right angles without rejecting tilted shots.
 * Area saturates at 50% of the frame so a big page is not out-scored by the full image border.
 */
class QuadScorer(
    private val edgeWeight: Double = 0.5,
    private val angleWeight: Double = 0.25,
    private val areaWeight: Double = 0.25,
    private val samplesPerSide: Int = 48,
    private val searchRadius: Int = 3,
    /** Inside/outside samples are taken this far (and twice this far) from the side. */
    private val contrastOffset: Double = 3.0,
    /** Minimum inside/outside difference (8-bit, on the blurred frame) to count; white-on-white is ~10. */
    private val minContrast: Int = 4,
) {
    /**
     * Thin Canny edge map; low thresholds because we only test presence, not strength. Also feeds
     * [QuadRefiner] and the line detector. CLAHE first (clip 2, 8x8 tiles) lifts faint borders such as
     * white paper on a light desk, which plain thresholds miss.
     */
    fun edgeMap(gray: Mat): Mat {
        val work = Mat()
        val edges = Mat()
        releasing(work) {
            Imgproc.createCLAHE(2.0, Size(8.0, 8.0)).apply(gray, work)
            Imgproc.GaussianBlur(work, work, Size(5.0, 5.0), 0.0)
            Imgproc.Canny(work, edges, 20.0, 60.0, 3, true)
        }
        return edges
    }

    /** Frame data as byte arrays, so many candidates can be scored without per-pixel JNI calls. */
    class Context(val edges: ByteArray, val gray: ByteArray, val width: Int, val height: Int) {
        /**
         * Blurred-gray difference between the point [offset] px along the normal ([nx], [ny]) and the point
         * [offset] px against it; null if either falls outside the frame.
         */
        fun contrastAcross(x: Double, y: Double, nx: Double, ny: Double, offset: Double): Int? {
            val ix = (x + nx * offset).roundToInt()
            val iy = (y + ny * offset).roundToInt()
            val ox = (x - nx * offset).roundToInt()
            val oy = (y - ny * offset).roundToInt()
            if (ix !in 0 until width || iy !in 0 until height || ox !in 0 until width || oy !in 0 until height) return null
            return (gray[iy * width + ix].toInt() and 0xFF) - (gray[oy * width + ox].toInt() and 0xFF)
        }

        /**
         * True for a step (a border between two surfaces): the difference is already about full at [near]
         * offset and does not keep growing at 2x the offset. A smooth lighting gradient roughly doubles
         * instead, and is rejected.
         */
        fun isStep(near: Int, far: Int, minDiff: Int): Boolean {
            if (near >= minDiff && far >= minDiff) return far <= near * 1.6 + 2
            if (near <= -minDiff && far <= -minDiff) return -far <= -near * 1.6 + 2
            return false
        }

        /**
         * Fraction of points along the segment where one side is consistently brighter than the other with
         * a step-like profile (see [isStep]). Page borders score ~0.9; lines through text or across a
         * lighting gradient score far lower.
         */
        fun sideConsistency(x0: Double, y0: Double, x1: Double, y1: Double, offset: Double = 3.0, minDiff: Int = 4): Double {
            val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(1e-9)
            val nx = -(y1 - y0) / len
            val ny = (x1 - x0) / len
            var pos = 0
            var neg = 0
            var valid = 0
            for (i in 0 until 32) {
                val t = (i + 0.5) / 32
                val x = x0 + (x1 - x0) * t
                val y = y0 + (y1 - y0) * t
                val near = contrastAcross(x, y, nx, ny, offset) ?: continue
                val far = contrastAcross(x, y, nx, ny, offset * 2) ?: continue
                valid++
                if (isStep(near, far, minDiff)) {
                    if (near > 0) pos++ else neg++
                }
            }
            return if (valid < 8) 0.0 else maxOf(pos, neg).toDouble() / valid
        }
    }

    /** [gray] the frame the quads refer to; [edges] from [edgeMap] on it. */
    fun context(gray: Mat, edges: Mat): Context {
        val w = edges.cols()
        val h = edges.rows()
        val e = ByteArray(w * h).also { edges.get(0, 0, it) }
        val blurred = Mat()
        val g = releasing(blurred) {
            // Blur so single dark text pixels near the border do not flip the inside/outside comparison.
            Imgproc.GaussianBlur(gray, blurred, Size(5.0, 5.0), 0.0)
            ByteArray(w * h).also { blurred.get(0, 0, it) }
        }
        return Context(e, g, w, h)
    }

    /**
     * [quadPx] in pixel coordinates of the context. With [ignoreFrameBorder] (page partly outside the
     * frame, corners clamped), samples on the frame edge carry no evidence and are skipped; at least two
     * sides must still have real support.
     */
    fun score(quadPx: Quad, ctx: Context, ignoreFrameBorder: Boolean = false): Double {
        val w = ctx.width
        val h = ctx.height
        val center = Vec2(quadPx.corners.sumOf { it.x } / 4, quadPx.corners.sumOf { it.y } / 4)
        val sideSupport = quadPx.sides().mapNotNull { (a, b) -> sideSupport(a, b, center, ctx, ignoreFrameBorder) }
        if (sideSupport.size < 2) return 0.0
        val edge = 0.5 * sideSupport.average() + 0.5 * sideSupport.min()
        val angle = quadPx.interiorAngles().map { 1.0 - abs(cos(it)) }.average()
        val area = min(1.0, quadPx.area() / (w.toDouble() * h) / 0.5)
        return edgeWeight * edge + angleWeight * angle + areaWeight * area
    }

    /** Null when every sample was skipped (side lies on the frame border). */
    private fun sideSupport(a: Vec2, b: Vec2, center: Vec2, ctx: Context, ignoreFrameBorder: Boolean): Double? {
        val w = ctx.width
        val h = ctx.height
        val len = hypot(b.x - a.x, b.y - a.y).coerceAtLeast(1e-9)
        var nx = -(b.y - a.y) / len
        var ny = (b.x - a.x) / len
        // Orient the normal towards the inside of the quad.
        val mid = Vec2((a.x + b.x) / 2, (a.y + b.y) / 2)
        if ((center.x - mid.x) * nx + (center.y - mid.y) * ny < 0) {
            nx = -nx
            ny = -ny
        }
        var edgeHits = 0
        var total = 0
        var brighterInside = 0
        var darkerInside = 0
        // Skip 10% at each end: corners are often rounded, occluded by fingers, or blurred.
        for (i in 0 until samplesPerSide) {
            val t = 0.1 + 0.8 * i / (samplesPerSide - 1)
            val sx = a.x + (b.x - a.x) * t
            val sy = a.y + (b.y - a.y) * t
            if (ignoreFrameBorder && (sx < 3 || sy < 3 || sx > w - 4 || sy > h - 4)) continue
            var inside = false
            var hit = false
            for (d in -searchRadius..searchRadius) {
                val x = (sx + nx * d).roundToInt()
                val y = (sy + ny * d).roundToInt()
                if (x !in 0 until w || y !in 0 until h) continue
                inside = true
                if (ctx.edges[y * w + x].toInt() != 0) {
                    hit = true
                    break
                }
            }
            if (!inside) continue
            total++
            if (hit) edgeHits++
            // Both offsets must agree: a real border separates two surfaces at any distance, whereas next
            // to text the sign flips between character and gap.
            val near = ctx.contrastAcross(sx, sy, nx, ny, contrastOffset)
            val far = ctx.contrastAcross(sx, sy, nx, ny, contrastOffset * 2)
            if (near != null && far != null && ctx.isStep(near, far, minContrast)) {
                if (near > 0) brighterInside++ else darkerInside++
            }
        }
        if (total == 0) return if (ignoreFrameBorder) null else 0.0
        val edge = edgeHits.toDouble() / total
        val contrast = maxOf(brighterInside, darkerInside).toDouble() / total
        return 0.5 * edge + 0.5 * contrast
    }
}
