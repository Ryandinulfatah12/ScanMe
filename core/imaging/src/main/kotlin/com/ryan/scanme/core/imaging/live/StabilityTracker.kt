package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.geometry.Quad

sealed interface LiveState {
    data object NoDocument : LiveState
    data object Unstable : LiveState

    /** [progress] goes 0..1 while the user holds still; drives the "hold steady" indicator. */
    data class Steadying(val progress: Double) : LiveState
    data object ReadyToCapture : LiveState

    /** Just captured; waits for the scene to change before arming again (batch mode). */
    data object WaitingForNextPage : LiveState
}

/**
 * Auto-capture state machine.
 *
 * Stable means both: little frame motion (camera/page not moving) and the smoothed quad stays within
 * [maxDrift] of where it was when steadying started. Checking both avoids firing when the phone is still
 * but the detector flickers between two outlines, and vice versa.
 *
 * Defaults (to be tuned on device):
 * - [motionThreshold] 0.02 = 2% mean luma change at 64 px; hand tremor stays below ~1%.
 * - [maxDrift] 0.02 = 2% of the frame; larger drift means the user is still aiming.
 * - [steadyDurationMs] 800: long enough to avoid accidental shots, short enough not to feel slow.
 * - [rearmMotion] 0.06: after a capture, a page turn or re-aim produces a clear motion spike.
 */
class StabilityTracker(
    private val motionThreshold: Double = 0.02,
    private val maxDrift: Double = 0.02,
    private val steadyDurationMs: Long = 800,
    private val rearmMotion: Double = 0.06,
) {
    private var anchor: Quad? = null
    private var steadySinceMs: Long = 0
    private var waitingForChange = false

    var state: LiveState = LiveState.NoDocument
        private set

    fun reset() {
        anchor = null
        waitingForChange = false
        state = LiveState.NoDocument
    }

    /** Call after a capture (manual or automatic). */
    fun onCaptured() {
        waitingForChange = true
        anchor = null
        state = LiveState.WaitingForNextPage
    }

    fun update(quad: Quad?, motion: Double, timestampMs: Long): LiveState {
        if (waitingForChange) {
            if (motion >= rearmMotion || quad == null) {
                waitingForChange = false
            } else {
                return LiveState.WaitingForNextPage.also { state = it }
            }
        }
        if (quad == null) {
            anchor = null
            return LiveState.NoDocument.also { state = it }
        }
        val a = anchor
        if (motion > motionThreshold || a == null || a.maxCornerDistance(quad) > maxDrift) {
            anchor = quad
            steadySinceMs = timestampMs
            return (if (a == null) LiveState.Steadying(0.0) else LiveState.Unstable).also { state = it }
        }
        val progress = (timestampMs - steadySinceMs).toDouble() / steadyDurationMs
        val next = if (progress >= 1.0) LiveState.ReadyToCapture else LiveState.Steadying(progress)
        return next.also { state = it }
    }
}
