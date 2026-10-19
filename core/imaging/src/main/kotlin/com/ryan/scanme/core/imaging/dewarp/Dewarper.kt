package com.ryan.scanme.core.imaging.dewarp

import com.ryan.scanme.core.imaging.DebugSink
import org.opencv.core.Mat

/**
 * Straightens curved pages (book spreads, folds) after perspective correction.
 *
 * Implementations: classic text-line surface fit (Phase 3 step 6, option A) and an ML model behind
 * `:core:ml` (option B). The pipeline must work without either.
 */
fun interface Dewarper {
    /** Returns a new Mat; must not modify [page]. */
    fun dewarp(page: Mat, debug: DebugSink?): Mat
}

object NoOpDewarper : Dewarper {
    override fun dewarp(page: Mat, debug: DebugSink?): Mat = page.clone()
}
