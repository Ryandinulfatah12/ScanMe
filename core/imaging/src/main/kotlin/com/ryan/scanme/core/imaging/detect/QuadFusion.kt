package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2

/**
 * Picks the best candidate and averages it with candidates that agree with it.
 *
 * Agreement between independent detectors (two Canny profiles, region, later the segmentation model)
 * is strong evidence; averaging their corners also reduces per-detector jitter.
 */
class QuadFusion(
    /** Max corner distance (normalized units) for two candidates to count as the same outline. */
    private val agreeDistance: Double = 0.03,
    private val agreementBonus: Double = 0.05,
) {
    fun fuse(candidates: List<QuadCandidate>): QuadCandidate? {
        val best = candidates.maxByOrNull { it.score } ?: return null
        val cluster = candidates.filter { it.quad.maxCornerDistance(best.quad) <= agreeDistance }
        if (cluster.size == 1) return best
        val total = cluster.sumOf { it.score }
        fun avg(pick: (Quad) -> Vec2) = cluster
            .map { pick(it.quad) * (it.score / total) }
            .reduce { a, b -> a + b }
        val fused = Quad(avg { it.tl }, avg { it.tr }, avg { it.br }, avg { it.bl })
        val score = (best.score + agreementBonus * (cluster.size - 1)).coerceAtMost(1.0)
        return QuadCandidate(fused, QuadSource.FUSED, score, cutOff = cluster.any { it.cutOff })
    }
}
