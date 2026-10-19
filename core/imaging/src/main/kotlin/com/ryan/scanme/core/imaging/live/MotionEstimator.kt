package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.releasing
import com.ryan.scanme.core.imaging.toGray
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Frame-to-frame motion from luma only (same idea as the reference app's YUV420 motion check).
 *
 * The frame is shrunk to [width] px wide (area interpolation averages away sensor noise), then the mean
 * absolute difference to the previous frame is returned in 0..1. 64 px wide costs well under 1 ms and is
 * still sensitive to page or phone movement.
 */
class MotionEstimator(private val width: Int = 64) {
    private var previous: Mat? = null

    fun reset() {
        previous?.release()
        previous = null
    }

    /** Returns motion in 0..1, or 0 for the first frame. [frame] may be gray or BGR. */
    fun update(frame: Mat): Double {
        val gray = toGray(frame)
        val small = Mat()
        releasing(gray) {
            val h = (gray.rows().toDouble() * width / gray.cols()).coerceAtLeast(1.0)
            Imgproc.resize(gray, small, Size(width.toDouble(), h), 0.0, 0.0, Imgproc.INTER_AREA)
        }
        val prev = previous
        previous = small
        if (prev == null || prev.size() != small.size()) {
            prev?.release()
            return 0.0
        }
        val diff = Mat()
        return releasing(prev, diff) {
            Core.absdiff(prev, small, diff)
            Core.mean(diff).`val`[0] / 255.0
        }
    }
}
