package com.ryan.scanme.core.imaging.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

data class Vec2(val x: Double, val y: Double) {
    operator fun plus(o: Vec2) = Vec2(x + o.x, y + o.y)
    operator fun minus(o: Vec2) = Vec2(x - o.x, y - o.y)
    operator fun times(s: Double) = Vec2(x * s, y * s)
    fun length() = hypot(x, y)
    fun distanceTo(o: Vec2) = (this - o).length()
    fun cross(o: Vec2) = x * o.y - y * o.x
    fun dot(o: Vec2) = x * o.x + y * o.y
}

/**
 * Four document corners in clockwise order starting top-left.
 *
 * Coordinates are either pixels or normalized (0..1); the type does not track which, so APIs state it.
 * Cross-module APIs use normalized quads so they survive the preview/photo resolution change.
 */
data class Quad(val tl: Vec2, val tr: Vec2, val br: Vec2, val bl: Vec2) {

    val corners: List<Vec2> get() = listOf(tl, tr, br, bl)

    fun scaled(sx: Double, sy: Double) =
        Quad(Vec2(tl.x * sx, tl.y * sy), Vec2(tr.x * sx, tr.y * sy), Vec2(br.x * sx, br.y * sy), Vec2(bl.x * sx, bl.y * sy))

    fun toPixels(width: Int, height: Int) = scaled(width.toDouble(), height.toDouble())

    fun toNormalized(width: Int, height: Int) = scaled(1.0 / width, 1.0 / height)

    /** Shoelace area (absolute). */
    fun area(): Double {
        val c = corners
        var s = 0.0
        for (i in c.indices) s += c[i].cross(c[(i + 1) % 4])
        return abs(s) / 2
    }

    fun sides(): List<Pair<Vec2, Vec2>> = listOf(tl to tr, tr to br, br to bl, bl to tl)

    fun isConvex(): Boolean {
        val c = corners
        var sign = 0
        for (i in c.indices) {
            val z = (c[(i + 1) % 4] - c[i]).cross(c[(i + 2) % 4] - c[(i + 1) % 4])
            if (z == 0.0) return false
            val s = if (z > 0) 1 else -1
            if (sign == 0) sign = s else if (s != sign) return false
        }
        return true
    }

    /** Largest distance between corresponding corners. */
    fun maxCornerDistance(other: Quad): Double =
        corners.zip(other.corners).maxOf { (a, b) -> a.distanceTo(b) }

    /** Interior angles in radians, in corner order. */
    fun interiorAngles(): List<Double> {
        val c = corners
        return c.indices.map { i ->
            val prev = c[(i + 3) % 4] - c[i]
            val next = c[(i + 1) % 4] - c[i]
            val cos = prev.dot(next) / max(prev.length() * next.length(), 1e-12)
            kotlin.math.acos(cos.coerceIn(-1.0, 1.0))
        }
    }

    companion object {
        val FULL_FRAME = Quad(Vec2(0.0, 0.0), Vec2(1.0, 0.0), Vec2(1.0, 1.0), Vec2(0.0, 1.0))

        /**
         * Orders four arbitrary points clockwise (image coordinates, y down), starting at the corner
         * closest to the top-left. Angle sorting stays correct for rotated pages, unlike sum/diff tricks.
         */
        fun fromUnordered(points: List<Vec2>): Quad {
            require(points.size == 4) { "Quad needs exactly 4 points, got ${points.size}" }
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            val sorted = points.sortedBy { atan2(it.y - cy, it.x - cx) }
            val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
            val o = List(4) { sorted[(start + it) % 4] }
            return Quad(o[0], o[1], o[2], o[3])
        }
    }
}
