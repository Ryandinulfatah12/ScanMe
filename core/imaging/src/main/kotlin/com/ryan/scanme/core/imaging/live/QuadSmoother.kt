package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import kotlin.math.PI
import kotlin.math.abs

/**
 * One-Euro filter (Casiez et al. 2012) applied to each of the 8 corner coordinates.
 *
 * Why One-Euro instead of a plain EMA: a fixed EMA either jitters while the phone is still or lags
 * while it moves. One-Euro raises its cutoff with speed, so the overlay is steady when held still and
 * follows quickly when the user re-aims.
 *
 * Units: coordinates are normalized (0..1), time in milliseconds.
 * - [minCutoffHz] 1.5: removes hand tremor (~8-12 Hz) and detector noise while still.
 * - [beta] 4.0: at 0.25 frame-widths/s the cutoff roughly doubles; tuned for typical re-aim speed.
 * - [resetJump] 0.15: a jump this large means a different page or a misdetection; snap, don't glide.
 */
class QuadSmoother(
    private val minCutoffHz: Double = 1.5,
    private val beta: Double = 4.0,
    private val derivativeCutoffHz: Double = 1.0,
    private val resetJump: Double = 0.15,
    private val resetAfterMissingMs: Long = 300,
) {
    private val filters = Array(8) { OneEuro() }
    private var last: Quad? = null
    private var lastSeenMs: Long = Long.MIN_VALUE

    fun reset() {
        filters.forEach { it.reset() }
        last = null
    }

    /** Feed the latest detection (normalized) or null when nothing was found. */
    fun update(quad: Quad?, timestampMs: Long): Quad? {
        if (quad == null) {
            if (timestampMs - lastSeenMs > resetAfterMissingMs) reset()
            return last
        }
        val prev = last
        if (prev != null && prev.maxCornerDistance(quad) > resetJump) reset()
        lastSeenMs = timestampMs
        val v = quad.corners.flatMap { listOf(it.x, it.y) }
        val f = DoubleArray(8) { filters[it].filter(v[it], timestampMs) }
        return Quad(Vec2(f[0], f[1]), Vec2(f[2], f[3]), Vec2(f[4], f[5]), Vec2(f[6], f[7])).also { last = it }
    }

    private inner class OneEuro {
        private var x: Double? = null
        private var dx = 0.0
        private var t = 0L

        fun reset() {
            x = null
            dx = 0.0
        }

        fun filter(value: Double, timestampMs: Long): Double {
            val prev = x
            if (prev == null) {
                x = value
                t = timestampMs
                return value
            }
            val dt = ((timestampMs - t).coerceAtLeast(1)) / 1000.0
            t = timestampMs
            val rawDx = (value - prev) / dt
            dx += alpha(derivativeCutoffHz, dt) * (rawDx - dx)
            val cutoff = minCutoffHz + beta * abs(dx)
            val next = prev + alpha(cutoff, dt) * (value - prev)
            x = next
            return next
        }

        private fun alpha(cutoffHz: Double, dt: Double): Double {
            val tau = 1.0 / (2 * PI * cutoffHz)
            return 1.0 / (1.0 + tau / dt)
        }
    }
}
