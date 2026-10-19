package com.ryan.scanme.core.imaging.metrics

import com.ryan.scanme.core.imaging.histogram8U
import com.ryan.scanme.core.imaging.percentile
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.imgproc.Imgproc

/**
 * Objective checks behind the acceptance criteria (AGENTS.md). Used by tests and the debug screen.
 *
 * L* note: OpenCV 8-bit Lab stores L* scaled to 0..255, so L* = L * 100 / 255.
 */
object QualityMetrics {

    /** Median L* (0..100) of [bgr] over [mask] (non-zero = included). */
    fun medianLStar(bgr: Mat, mask: Mat): Double {
        val l = lChannel(bgr)
        return releasing(l) { percentile(histogram8U(l, mask), 0.5) * 100.0 / 255.0 }
    }

    /** Mean L* (0..100) of [bgr] over [mask]. */
    fun meanLStar(bgr: Mat, mask: Mat): Double {
        val l = lChannel(bgr)
        return releasing(l) { Core.mean(l, mask).`val`[0] * 100.0 / 255.0 }
    }

    /** Fraction of [mask] pixels whose L* is below [lStar]. */
    fun fractionDarkerThan(bgr: Mat, mask: Mat, lStar: Double): Double {
        val l = lChannel(bgr)
        val dark = Mat()
        return releasing(l, dark) {
            Imgproc.threshold(l, dark, lStar * 255.0 / 100.0, 255.0, Imgproc.THRESH_BINARY_INV)
            Core.bitwise_and(dark, mask, dark)
            Core.countNonZero(dark).toDouble() / Core.countNonZero(mask).coerceAtLeast(1)
        }
    }

    private fun lChannel(bgr: Mat): Mat {
        val lab = Mat()
        val ch = mutableListOf<Mat>()
        releasing(lab) {
            Imgproc.cvtColor(bgr, lab, Imgproc.COLOR_BGR2Lab)
            Core.split(lab, ch)
        }
        ch[1].release()
        ch[2].release()
        return ch[0]
    }
}
