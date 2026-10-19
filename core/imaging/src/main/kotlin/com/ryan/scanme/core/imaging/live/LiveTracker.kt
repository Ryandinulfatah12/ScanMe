package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.detect.ClassicQuadDetector
import com.ryan.scanme.core.imaging.detect.DetectionResult
import com.ryan.scanme.core.imaging.detect.QuadCandidate
import com.ryan.scanme.core.imaging.geometry.Quad
import org.opencv.core.Mat

/**
 * One processed preview frame.
 *
 * @property quad smoothed outline (normalized) to draw, or null.
 * @property cutOff the page extends past the frame: show a hint, do not auto-capture.
 */
data class LiveFrameResult(
    val quad: Quad?,
    val state: LiveState,
    val cutOff: Boolean,
    val detectMs: Double,
)

/**
 * Turns per-frame detections into a stable live overlay and auto-capture state.
 *
 * Hysteresis: a page must score [acquireScore] to be picked up, but once tracked, a candidate within
 * [trackDistance] of the tracked outline only needs [trackScore]. Consistency with the previous frames is
 * itself evidence, and this stops the overlay vanishing when one side momentarily weakens (glare, a
 * finger, motion blur).
 *
 * Hold: when a frame finds nothing, the last outline stays visible for [holdMs] instead of blinking off;
 * the stability timer keeps running during the hold so auto-capture is not reset by a single bad frame.
 */
class LiveTracker(
    private val detect: (Mat) -> DetectionResult = ClassicQuadDetector()::detect,
    private val acquireScore: Double = 0.6,
    private val trackScore: Double = 0.45,
    private val trackDistance: Double = 0.06,
    private val holdMs: Long = 400,
) {
    private val smoother = QuadSmoother()
    private val motion = MotionEstimator()
    private val stability = StabilityTracker()

    private var tracked: Quad? = null
    private var trackedCutOff = false
    private var lastSeenMs = Long.MIN_VALUE / 2

    fun onCaptured() = stability.onCaptured()

    fun reset() {
        smoother.reset()
        motion.reset()
        stability.reset()
        tracked = null
    }

    /** [frame]: upright gray or BGR preview frame. */
    fun update(frame: Mat, timestampMs: Long): LiveFrameResult {
        val start = System.nanoTime()
        val result = detect(frame)
        val detectMs = (System.nanoTime() - start) / 1e6
        val moved = motion.update(frame)

        val previous = tracked?.takeIf { timestampMs - lastSeenMs <= holdMs }
        val chosen = choose(result.candidates, result.best, previous)

        val quad: Quad?
        val cutOff: Boolean
        if (chosen != null) {
            lastSeenMs = timestampMs
            quad = smoother.update(chosen.quad, timestampMs)
            tracked = quad
            trackedCutOff = chosen.cutOff
            cutOff = chosen.cutOff
        } else if (previous != null) {
            quad = previous // hold: keep showing the last outline
            cutOff = trackedCutOff
        } else {
            smoother.reset()
            tracked = null
            quad = null
            cutOff = false
        }
        // A cut-off page must not trigger auto-capture: report it as "no document" to the stability timer.
        val state = stability.update(quad.takeUnless { cutOff }, moved, timestampMs)
        return LiveFrameResult(quad, state, cutOff, detectMs)
    }

    private fun choose(candidates: List<QuadCandidate>, best: QuadCandidate?, previous: Quad?): QuadCandidate? {
        if (previous != null) {
            val near = candidates
                .filter { it.score >= trackScore && it.quad.maxCornerDistance(previous) <= trackDistance }
                .maxByOrNull { it.score }
            // A clearly better outline elsewhere (user moved to another page) still wins.
            if (near != null && (best == null || best.score < acquireScore || best.score - near.score < 0.15)) return near
        }
        return best?.takeIf { it.score >= acquireScore }
    }
}
