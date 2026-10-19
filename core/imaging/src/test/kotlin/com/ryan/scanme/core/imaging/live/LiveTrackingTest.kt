package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import org.opencv.core.Mat
import org.opencv.core.Rect
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LiveTrackingTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val base = Quad(Vec2(0.2, 0.15), Vec2(0.8, 0.17), Vec2(0.82, 0.85), Vec2(0.18, 0.83))

    private fun Quad.shifted(dx: Double, dy: Double) =
        Quad(tl + Vec2(dx, dy), tr + Vec2(dx, dy), br + Vec2(dx, dy), bl + Vec2(dx, dy))

    @Test
    fun `smoother suppresses jitter while still and follows a move`() {
        val smoother = QuadSmoother()
        val rnd = Random(3)
        val raw = mutableListOf<Double>()
        val smooth = mutableListOf<Double>()
        var t = 0L
        repeat(60) {
            val noisy = base.shifted(rnd.nextDouble(-0.004, 0.004), rnd.nextDouble(-0.004, 0.004))
            val out = smoother.update(noisy, t)!!
            if (it > 10) {
                raw += noisy.tl.x
                smooth += out.tl.x
            }
            t += 33
        }
        fun std(v: List<Double>): Double = v.average().let { m -> sqrt(v.sumOf { (it - m) * (it - m) } / v.size) }
        println("jitter std raw=%.5f smooth=%.5f".format(std(raw), std(smooth)))
        assertTrue(std(smooth) < 0.5 * std(raw))

        // Move 10% to the right; within ~300 ms the overlay should be within 1% of the target.
        val moved = base.shifted(0.10, 0.0)
        var out = base
        repeat(10) {
            out = smoother.update(moved, t)!!
            t += 33
        }
        assertTrue(out.maxCornerDistance(moved) < 0.01, "lag ${out.maxCornerDistance(moved)}")
    }

    @Test
    fun `stability tracker arms after holding still and rearms after a page change`() {
        val tracker = StabilityTracker()
        var t = 0L
        assertIs<LiveState.NoDocument>(tracker.update(null, 0.0, t))
        var state: LiveState = tracker.update(base, 0.005, t)
        while (state !is LiveState.ReadyToCapture && t < 2000) {
            t += 33
            state = tracker.update(base.shifted(0.002, 0.0), 0.005, t)
        }
        assertIs<LiveState.ReadyToCapture>(state)
        assertTrue(t in 800L..900L, "armed after $t ms")

        // Movement resets.
        assertIs<LiveState.Unstable>(tracker.update(base, 0.05, t + 33))

        tracker.onCaptured()
        // Still holding the same page: must not fire again.
        repeat(60) { t += 33; assertIs<LiveState.WaitingForNextPage>(tracker.update(base, 0.005, t)) }
        // Page turn (motion spike) leaves the waiting state; holding the new page steady arms again.
        t += 33
        val afterTurn = tracker.update(base.shifted(0.01, 0.0), 0.1, t)
        assertTrue(afterTurn !is LiveState.WaitingForNextPage && afterTurn !is LiveState.ReadyToCapture)
        var rearmed: LiveState = afterTurn
        val turnAt = t
        while (rearmed !is LiveState.ReadyToCapture && t < turnAt + 2000) {
            t += 33
            rearmed = tracker.update(base.shifted(0.01, 0.0), 0.005, t)
        }
        assertIs<LiveState.ReadyToCapture>(rearmed)
    }

    @Test
    fun `motion estimator separates still and moving frames`() {
        val scene = SyntheticScenes.build(SceneKind.FLAT)
        val est = MotionEstimator()
        val a = Mat(scene.image, Rect(0, 0, 1000, 1400))
        val b = Mat(scene.image, Rect(60, 40, 1000, 1400)) // camera moved
        assertEquals(0.0, est.update(a))
        val still = est.update(a.clone())
        val moving = est.update(b)
        println("motion still=%.4f moving=%.4f".format(still, moving))
        assertTrue(still < 0.005)
        assertTrue(moving > 0.02)
        scene.release()
    }
}
