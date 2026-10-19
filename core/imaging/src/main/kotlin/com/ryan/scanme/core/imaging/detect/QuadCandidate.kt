package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad

enum class QuadSource {
    CLASSIC_HIGH_CONTRAST,
    CLASSIC_LOW_CONTRAST,
    CLASSIC_REGION,
    CLASSIC_LINES,
    SEGMENTATION,
    FUSED,
    MANUAL,
}

/**
 * A candidate document outline. [quad] is normalized (0..1); [score] is 0..1, higher is better.
 * [cutOff]: part of the page lies outside the frame (corners were clamped to the frame edge).
 */
data class QuadCandidate(val quad: Quad, val source: QuadSource, val score: Double, val cutOff: Boolean = false)

data class DetectionResult(val best: QuadCandidate?, val candidates: List<QuadCandidate>)
