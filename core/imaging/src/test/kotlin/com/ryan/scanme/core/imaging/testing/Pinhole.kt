package com.ryan.scanme.core.imaging.testing

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import kotlin.math.cos
import kotlin.math.sin

/** Ideal pinhole camera (square pixels, principal point at the centre) used to make realistic test quads. */
class Pinhole(val width: Int, val height: Int, val focalPx: Double) {

    /**
     * Projects an A4 sheet (210 x 297 mm) centred on the optical axis at [distanceMm], rotated by [rx], [ry],
     * [rz] radians (about X, then Y, then Z), and translated sideways by ([txMm], [tyMm]).
     */
    fun projectA4(rx: Double, ry: Double, rz: Double, distanceMm: Double, txMm: Double = 0.0, tyMm: Double = 0.0): Quad {
        val corners = listOf(-105.0 to -148.5, 105.0 to -148.5, 105.0 to 148.5, -105.0 to 148.5)
        val pts = corners.map { (x, y) ->
            var p = doubleArrayOf(x, y, 0.0)
            p = doubleArrayOf(p[0], p[1] * cos(rx) - p[2] * sin(rx), p[1] * sin(rx) + p[2] * cos(rx))
            p = doubleArrayOf(p[0] * cos(ry) + p[2] * sin(ry), p[1], -p[0] * sin(ry) + p[2] * cos(ry))
            p = doubleArrayOf(p[0] * cos(rz) - p[1] * sin(rz), p[0] * sin(rz) + p[1] * cos(rz), p[2])
            val z = p[2] + distanceMm
            Vec2(width / 2.0 + focalPx * (p[0] + txMm) / z, height / 2.0 + focalPx * (p[1] + tyMm) / z)
        }
        return Quad(pts[0], pts[1], pts[2], pts[3])
    }
}
