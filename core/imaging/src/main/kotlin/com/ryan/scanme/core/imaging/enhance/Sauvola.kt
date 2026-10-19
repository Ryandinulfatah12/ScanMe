package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.longSide
import com.ryan.scanme.core.imaging.oddAtLeast
import com.ryan.scanme.core.imaging.releasing
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Sauvola binarization: T = mean * (1 + k * (std / R - 1)) over a local window.
 *
 * Implemented with box filters (O(1) per pixel regardless of window) because the official OpenCV Android
 * AAR does not ship opencv_contrib's ximgproc::niBlackThreshold.
 *
 * - window: 2% of the long side (~80 px on a 4000 px page) spans a few text lines, so the local mean
 *   reflects paper, not just ink.
 * - [k] 0.25: within the usual 0.2..0.5 range; lower keeps thin, light strokes as ink.
 * - [r] 128: dynamic range of the standard deviation for 8-bit input.
 */
class Sauvola(
    private val k: Double = 0.25,
    private val r: Double = 128.0,
    private val windowFraction: Double = 0.02,
) {
    /** [gray] 8-bit single channel. Returns 8-bit binary: ink = 0, paper = 255. */
    fun binarize(gray: Mat): Mat {
        val window = oddAtLeast(gray.longSide() * windowFraction, 15).toDouble()
        val f = Mat()
        val mean = Mat()
        val sq = Mat()
        val meanSq = Mat()
        val threshold = Mat()
        return releasing(f, mean, sq, meanSq, threshold) {
            gray.convertTo(f, CvType.CV_32F)
            val size = Size(window, window)
            val anchor = Point(-1.0, -1.0)
            Imgproc.boxFilter(f, mean, -1, size, anchor, true, Core.BORDER_REPLICATE)
            Core.multiply(f, f, sq)
            Imgproc.boxFilter(sq, meanSq, -1, size, anchor, true, Core.BORDER_REPLICATE)
            // std = sqrt(max(E[x^2] - E[x]^2, 0)); reuse sq as scratch.
            Core.multiply(mean, mean, sq)
            Core.subtract(meanSq, sq, sq)
            Core.max(sq, Scalar(0.0), sq)
            Core.sqrt(sq, sq)
            // T = mean * (1 + k * (std / r - 1)) = mean * ((1 - k) + (k / r) * std)
            sq.convertTo(threshold, CvType.CV_32F, k / r, 1.0 - k)
            Core.multiply(mean, threshold, threshold)
            val out = Mat()
            Core.compare(f, threshold, out, Core.CMP_GT)
            out
        }
    }
}
