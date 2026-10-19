package com.ryan.scanme.core.imaging.warp

import com.ryan.scanme.core.imaging.DebugSink
import com.ryan.scanme.core.imaging.geometry.Quad
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Crops and rectifies the page.
 *
 * Aspect ratio: plain side-length ratios are biased by perspective (the far edge looks shorter), which
 * would squash pages shot at an angle. We instead recover the true width/height ratio of the physical
 * rectangle with the method of Zhang & He, "Whiteboard scanning and image enhancement" (2007), assuming
 * square pixels and the principal point at the image centre.
 *
 * Focal length, in order of preference:
 * 1. `focalLengthPx` from camera metadata (CameraX/Camera2 lens focal length / sensor size), exact.
 * 2. Solved from the quad itself. Only possible when both vanishing points are finite: a pure pitch or pure
 *    yaw (one pair of page edges parallel in the image) makes it unobservable.
 * 3. [defaultFocalFactor] x long side: a typical phone main camera (~26 mm equivalent, ~70 deg horizontal
 *    FOV). Used for gallery imports without EXIF and for the degenerate poses above.
 *
 * Snap: if the ratio is within [snapTolerance] of a standard paper size, it is snapped exactly, which
 * removes residual error for the common case (A4/Letter/Legal).
 */
class PerspectiveCorrector(
    private val snapTolerance: Double = 0.03,
    private val maxOutputLongSide: Int = 4800,
    private val defaultFocalFactor: Double = 0.8,
) {
    /** Height/width of portrait standard sizes. */
    private val paperRatios = listOf(sqrt(2.0), 11.0 / 8.5, 14.0 / 8.5)

    /**
     * [quad] normalized; [focalLengthPx] in pixels of [src] when known. Returns a new Mat with the
     * rectified page.
     */
    fun correct(src: Mat, quad: Quad, focalLengthPx: Double? = null, debug: DebugSink? = null): Mat {
        val q = quad.toPixels(src.cols(), src.rows())
        val aspect = snap(estimateAspect(q, src.cols(), src.rows(), focalLengthPx))
        val (outW, outH) = outputSize(q, aspect)
        val from = MatOfPoint2f(*q.corners.map { Point(it.x, it.y) }.toTypedArray())
        val to = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(outW - 1.0, 0.0),
            Point(outW - 1.0, outH - 1.0),
            Point(0.0, outH - 1.0),
        )
        val m = Imgproc.getPerspectiveTransform(from, to)
        val dst = Mat()
        // Cubic keeps thin strokes crisp; replicate avoids black wedges when a corner sits on the frame edge.
        Imgproc.warpPerspective(src, dst, m, Size(outW.toDouble(), outH.toDouble()), Imgproc.INTER_CUBIC, Core.BORDER_REPLICATE, Scalar.all(0.0))
        from.release()
        to.release()
        m.release()
        debug?.stage("warp/rectified", dst)
        return dst
    }

    /** Width/height of the physical rectangle seen as [q] (pixel coordinates) in a [w]x[h] image. */
    fun estimateAspect(q: Quad, w: Int, h: Int, focalLengthPx: Double? = null): Double {
        val sideRatio = sideLengthAspect(q)
        val u0 = w / 2.0
        val v0 = h / 2.0
        val m1 = doubleArrayOf(q.tl.x, q.tl.y, 1.0)
        val m2 = doubleArrayOf(q.tr.x, q.tr.y, 1.0)
        val m3 = doubleArrayOf(q.bl.x, q.bl.y, 1.0)
        val m4 = doubleArrayOf(q.br.x, q.br.y, 1.0)
        val k2 = dot(cross(m1, m4), m3) / dot(cross(m2, m4), m3)
        val k3 = dot(cross(m1, m4), m2) / dot(cross(m3, m4), m2)
        if (!k2.isFinite() || !k3.isFinite()) return sideRatio
        // n2 ~ A*r1*width, n3 ~ A*r2*height (r1, r2: the page's in-plane axes in camera space).
        val n2 = DoubleArray(3) { k2 * m2[it] - m1[it] }
        val n3 = DoubleArray(3) { k3 * m3[it] - m1[it] }

        val longSide = max(w, h).toDouble()
        val f = focalLengthPx ?: solveFocal(n2, n3, u0, v0, longSide) ?: (defaultFocalFactor * longSide)
        fun normSq(n: DoubleArray): Double {
            val x = (n[0] - u0 * n[2]) / f
            val y = (n[1] - v0 * n[2]) / f
            return x * x + y * y + n[2] * n[2]
        }
        val ratio = sqrt(normSq(n2) / normSq(n3))
        return if (ratio.isFinite() && ratio > 0) ratio else sideRatio
    }

    /**
     * Focal length from r1 . r2 = 0. Returns null when unobservable: n2z or n3z ~ 0 means one vanishing
     * point is at infinity. The result must also be a plausible phone focal length (0.4..4x long side).
     */
    private fun solveFocal(n2: DoubleArray, n3: DoubleArray, u0: Double, v0: Double, longSide: Double): Double? {
        val scale2 = sqrt(n2[0] * n2[0] + n2[1] * n2[1])
        val scale3 = sqrt(n3[0] * n3[0] + n3[1] * n3[1])
        // |k-1| is the foreshortening along that axis. Under ~2-3% (scaled by how much of the frame the
        // page spans) a 1-2 px corner error dominates and f becomes noise, so treat it as unobservable.
        if (abs(n2[2]) * longSide < 0.05 * scale2 || abs(n3[2]) * longSide < 0.05 * scale3) return null
        val f2 = -((n2[0] - u0 * n2[2]) * (n3[0] - u0 * n3[2]) + (n2[1] - v0 * n2[2]) * (n3[1] - v0 * n3[2])) /
            (n2[2] * n3[2])
        if (!f2.isFinite() || f2 <= 0) return null
        return sqrt(f2).takeIf { it in (0.4 * longSide)..(4.0 * longSide) }
    }

    private fun sideLengthAspect(q: Quad): Double {
        val width = max(q.tl.distanceTo(q.tr), q.bl.distanceTo(q.br))
        val height = max(q.tl.distanceTo(q.bl), q.tr.distanceTo(q.br))
        return width / height
    }

    /** Snaps width/height to a standard paper ratio in either orientation when close enough. */
    fun snap(aspect: Double): Double {
        for (r in paperRatios) {
            for (target in doubleArrayOf(1.0 / r, r)) {
                if (abs(aspect - target) / target <= snapTolerance) return target
            }
        }
        return aspect
    }

    /** Keeps the larger measured dimension (no detail loss) and derives the other from [aspect]. */
    private fun outputSize(q: Quad, aspect: Double): Pair<Int, Int> {
        val w0 = max(q.tl.distanceTo(q.tr), q.bl.distanceTo(q.br))
        val h0 = max(q.tl.distanceTo(q.bl), q.tr.distanceTo(q.br))
        var w: Double
        var h: Double
        if (w0 / h0 > aspect) {
            w = w0
            h = w0 / aspect
        } else {
            h = h0
            w = h0 * aspect
        }
        val scale = (maxOutputLongSide / max(w, h)).coerceAtMost(1.0)
        w *= scale
        h *= scale
        return max(w.roundToInt(), 2) to max(h.roundToInt(), 2)
    }

    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
}
