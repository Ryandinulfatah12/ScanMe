package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** A quad built from lines; [cutOff] when a corner lies outside the frame (page not fully in view). */
internal class LineQuad(val quad: Quad, val cutOff: Boolean)

/**
 * Builds page candidates from the four longest straight lines instead of closed contours.
 *
 * Contours need the whole outline: one invisible side (white paper on a white desk), a finger across an
 * edge, or a corner outside the frame breaks them. Hough line detection accumulates evidence along each
 * side separately, so three clear sides plus a faint or partly hidden fourth still yield the page, and
 * corners may be extrapolated beyond the frame.
 *
 * Parameters scale with the frame's short side (480 px for live preview):
 * - min line length 12% (58 px): shorter segments are text and clutter.
 * - max gap 4%: bridges fingers and dropouts along an edge.
 * - up to [linesPerGroup] longest lines per orientation; text baselines are long too, so a few spare
 *   lines leave room for the real page edges. Combinations are ranked by the caller's scorer.
 */
internal class LineQuadDetector(
    private val linesPerGroup: Int = 8,
    /** Corners may lie this far outside the frame (fraction of size) before the combination is dropped. */
    private val maxOutside: Double = 0.15,
    /** See [QuadScorer.Context.sideConsistency]: page borders ~0.9, text lines ~0.3-0.5. */
    private val minSideConsistency: Double = 0.65,
) {
    /** [frame]: a virtual line along the image border, standing in for a page side outside the view. */
    private class Line(val x1: Double, val y1: Double, val x2: Double, val y2: Double, val frame: Boolean = false) {
        val length = hypot(x2 - x1, y2 - y1)
        /** Orientation in [0, PI). */
        val angle = (atan2(y2 - y1, x2 - x1) + PI) % PI
        val horizontal = angle < PI / 4 || angle > 3 * PI / 4
        /** Signed distance of the infinite line from the origin along its normal. */
        val rho = x1 * -sin(angle) + y1 * cos(angle)
        val mid = Vec2((x1 + x2) / 2, (y1 + y2) / 2)
    }

    /**
     * [edges] thin edge map; [ctx] the same frame's scoring context. Returns convex candidates of at least
     * [minArea] px.
     */
    fun detect(edges: Mat, ctx: QuadScorer.Context, minArea: Double): List<LineQuad> {
        val w = edges.cols().toDouble()
        val h = edges.rows().toDouble()
        val short = min(w, h)
        val raw = Mat()
        val lines = releasing(raw) {
            Imgproc.HoughLinesP(edges, raw, 1.0, PI / 180, max(30, (short * 0.08).toInt()), short * 0.12, short * 0.04)
            (0 until raw.rows()).map {
                val l = raw.get(it, 0)
                Line(l[0], l[1], l[2], l[3])
            }
        }
        // Keep only lines that separate two different surfaces. Text baselines and lines Hough threads
        // through aligned characters have alternating brightness on either side and are dropped here.
        fun isBorder(l: Line) = ctx.sideConsistency(l.x1, l.y1, l.x2, l.y2) >= minSideConsistency
        // The frame edges may stand in for one page side (page partly out of view -> flagged cut off).
        val horizontals = merge(lines.filter { it.horizontal }).filter(::isBorder).sortedByDescending { it.length }.take(linesPerGroup) +
            listOf(Line(0.0, 0.0, w - 1, 0.0, frame = true), Line(0.0, h - 1, w - 1, h - 1, frame = true))
        val verticals = merge(lines.filter { !it.horizontal }).filter(::isBorder).sortedByDescending { it.length }.take(linesPerGroup) +
            listOf(Line(0.0, 0.0, 0.0, h - 1, frame = true), Line(w - 1, 0.0, w - 1, h - 1, frame = true))

        val out = ArrayList<LineQuad>()
        for (i in horizontals.indices) for (j in i + 1 until horizontals.size) {
            val a = horizontals[i]
            val b = horizontals[j]
            if (abs(a.mid.y - b.mid.y) < 0.2 * h) continue
            for (k in verticals.indices) for (m in k + 1 until verticals.size) {
                val c = verticals[k]
                val d = verticals[m]
                if (abs(c.mid.x - d.mid.x) < 0.2 * w) continue
                // At most one side may come from the frame: three real page edges are required.
                val frameSides = listOf(a, b, c, d).count { it.frame }
                if (frameSides > 1) continue
                val corners = listOf(intersect(a, c), intersect(a, d), intersect(b, c), intersect(b, d))
                if (corners.any { it == null }) continue
                val pts = corners.map { it!! }
                if (pts.any { it.x < -maxOutside * w || it.x > (1 + maxOutside) * w || it.y < -maxOutside * h || it.y > (1 + maxOutside) * h }) continue
                val quad = Quad.fromUnordered(pts)
                if (!quad.isConvex() || quad.area() < minArea) continue
                val tol = 0.01 * short
                val cutOff = frameSides > 0 || pts.any { it.x < -tol || it.x > w - 1 + tol || it.y < -tol || it.y > h - 1 + tol }
                val clamped = if (cutOff) clamp(quad, w, h) else quad
                if (clamped.isConvex()) out += LineQuad(clamped, cutOff)
            }
        }
        return out
    }

    /** Joins near-collinear segments (angle within 3 deg, offset within 1.5% of the frame) into one line. */
    private fun merge(lines: List<Line>): List<Line> {
        val sorted = lines.sortedByDescending { it.length }
        val used = BooleanArray(sorted.size)
        val merged = ArrayList<Line>()
        for (i in sorted.indices) {
            if (used[i]) continue
            val base = sorted[i]
            val group = mutableListOf(base)
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val o = sorted[j]
                var da = abs(base.angle - o.angle)
                da = min(da, PI - da)
                if (da < 3 * PI / 180 && abs(base.rho - o.rho) < 8.0) {
                    group += o
                    used[j] = true
                }
            }
            // Extent of all endpoints projected on the base direction.
            val ux = cos(base.angle)
            val uy = sin(base.angle)
            val ends = group.flatMap { listOf(Vec2(it.x1, it.y1), Vec2(it.x2, it.y2)) }
            val p0 = ends.minBy { it.x * ux + it.y * uy }
            val p1 = ends.maxBy { it.x * ux + it.y * uy }
            merged += Line(p0.x, p0.y, p1.x, p1.y)
        }
        return merged
    }

    private fun intersect(a: Line, b: Line): Vec2? {
        val d1 = Vec2(a.x2 - a.x1, a.y2 - a.y1)
        val d2 = Vec2(b.x2 - b.x1, b.y2 - b.y1)
        val denom = d1.cross(d2)
        if (abs(denom) < 1e-6) return null
        val t = (Vec2(b.x1 - a.x1, b.y1 - a.y1)).cross(d2) / denom
        return Vec2(a.x1 + d1.x * t, a.y1 + d1.y * t)
    }

    private fun clamp(q: Quad, w: Double, h: Double): Quad {
        fun c(v: Vec2) = Vec2(v.x.coerceIn(0.0, w - 1), v.y.coerceIn(0.0, h - 1))
        return Quad(c(q.tl), c(q.tr), c(q.br), c(q.bl))
    }
}
