package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.histogram8U
import com.ryan.scanme.core.imaging.percentile
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/**
 * White-patch white balance using the paper itself as the reference.
 *
 * Gray-world fails on documents (a page with a big coloured logo is not gray on average). The brightest
 * [paperFraction] of pixels by luma is almost always blank paper, so their mean colour is the light's
 * colour cast. Gains are clamped to [minGain]..[maxGain] so a coloured paper (e.g. yellow) is softened
 * rather than forced to white.
 */
class WhiteBalance(
    private val paperFraction: Double = 0.3,
    private val minGain: Double = 0.7,
    private val maxGain: Double = 1.5,
) {
    fun apply(bgr: Mat): Mat {
        val gray = Mat()
        val mask = Mat()
        return releasing(gray, mask) {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY)
            val threshold = percentile(histogram8U(gray), 1.0 - paperFraction)
            Imgproc.threshold(gray, mask, threshold - 1.0, 255.0, Imgproc.THRESH_BINARY)
            val mean = Core.mean(bgr, mask).`val`
            val target = max(mean[0], max(mean[1], mean[2]))
            val gains = DoubleArray(3) { c -> (target / max(mean[c], 1.0)).coerceIn(minGain, maxGain) }
            val out = Mat()
            Core.multiply(bgr, Scalar(gains[0], gains[1], gains[2]), out)
            out
        }
    }
}
