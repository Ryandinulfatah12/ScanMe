package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

/**
 * Snaps each side of a rough quad onto the actual page border.
 *
 * Hull-based corners sit a few pixels inside or outside the border (they come from the dilated edge
 * band). For every side we take the edge pixels within [band] px of it, fit a line with a Huber loss
 * (robust to text or clutter edges inside the band), and intersect neighbouring lines for the corners.
 * The result is sub-pixel and unbiased. If a side has too little support, or a corner would move more
 * than [maxShift] px, the original quad is kept: refinement should polish, never jump to another outline.
 */
class QuadRefiner(
    private val band: Double = 6.0,
    private val maxShift: Double = 8.0,
    /** A side needs edge pixels along at least this fraction of its length to be refit. */
    private val minSupport: Double = 0.35,
) {
    /** Edge pixel coordinates of a thin (non-dilated) edge map; compute once per frame, reuse per candidate. */
    fun edgePoints(edges: Mat): List<Vec2> {
        val w = edges.cols()
        val data = ByteArray(w * edges.rows())
        edges.get(0, 0, data)
        val points = ArrayList<Vec2>()
        for (i in data.indices) if (data[i].toInt() != 0) points += Vec2((i % w).toDouble(), (i / w).toDouble())
        return points
    }

    /** [quadPx] and [points] share pixel coordinates. */
    fun refine(quadPx: Quad, points: List<Vec2>): Quad {
        val lines = quadPx.sides().map { (a, b) -> fitSide(a, b, points) ?: return quadPx }
        // sides: 0 top, 1 right, 2 bottom, 3 left
        val tl = intersect(lines[3], lines[0]) ?: return quadPx
        val tr = intersect(lines[0], lines[1]) ?: return quadPx
        val br = intersect(lines[1], lines[2]) ?: return quadPx
        val bl = intersect(lines[2], lines[3]) ?: return quadPx
        val refined = Quad(tl, tr, br, bl)
        return if (refined.isConvex() && refined.maxCornerDistance(quadPx) <= maxShift) refined else quadPx
    }

    private class Line(val p: Vec2, val d: Vec2)

    private fun fitSide(a: Vec2, b: Vec2, points: List<Vec2>): Line? {
        val dir = b - a
        val len = dir.length()
        if (len < 10) return null
        val u = dir * (1 / len)
        val n = Vec2(-u.y, u.x)
        val selected = ArrayList<Point>()
        for (p in points) {
            val rel = p - a
            val t = rel.dot(u) / len
            if (t < 0.1 || t > 0.9) continue
            if (abs(rel.dot(n)) <= band) selected += Point(p.x, p.y)
        }
        if (selected.size < minSupport * 0.8 * len) return null
        val mat = MatOfPoint2f(*selected.toTypedArray())
        val line = Mat()
        return releasing(mat, line) {
            Imgproc.fitLine(mat, line, Imgproc.DIST_HUBER, 0.0, 0.01, 0.01)
            val v = DoubleArray(4) { line.get(it, 0)[0] }
            Line(Vec2(v[2], v[3]), Vec2(v[0], v[1]))
        }
    }

    private fun intersect(l1: Line, l2: Line): Vec2? {
        val denom = l1.d.cross(l2.d)
        if (abs(denom) < 1e-6) return null
        val t = (l2.p - l1.p).cross(l2.d) / denom
        return l1.p + l1.d * t
    }
}
