package com.ryan.scanme.core.imaging

import org.opencv.core.Mat
import org.opencv.core.MatOfFloat
import org.opencv.core.MatOfInt
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max
import kotlin.math.roundToInt

/** Returns a new Mat rotated clockwise by [degrees] (multiple of 90); a copy when no rotation is needed. */
fun Mat.rotatedClockwise(degrees: Int): Mat {
    val code = when (Math.floorMod(degrees, 360)) {
        90 -> org.opencv.core.Core.ROTATE_90_CLOCKWISE
        180 -> org.opencv.core.Core.ROTATE_180
        270 -> org.opencv.core.Core.ROTATE_90_COUNTERCLOCKWISE
        else -> return clone()
    }
    val out = Mat()
    org.opencv.core.Core.rotate(this, out, code)
    return out
}

/** Runs [block] and releases every given Mat afterwards. */
internal inline fun <R> releasing(vararg mats: Mat, block: () -> R): R =
    try {
        block()
    } finally {
        mats.forEach { it.release() }
    }

internal fun Mat.longSide(): Int = max(cols(), rows())

/** Smallest odd integer >= [value], and at least [min]. */
internal fun oddAtLeast(value: Double, min: Int): Int {
    val v = max(value.roundToInt(), min)
    return if (v % 2 == 0) v + 1 else v
}

/** Returns a new Mat whose long side is at most [maxSide], plus the applied scale (<= 1). */
internal fun resizeLongSide(src: Mat, maxSide: Int, interpolation: Int = Imgproc.INTER_AREA): Pair<Mat, Double> {
    val scale = maxSide.toDouble() / src.longSide()
    if (scale >= 1.0) return src.clone() to 1.0
    val dst = Mat()
    Imgproc.resize(src, dst, Size(src.cols() * scale, src.rows() * scale), 0.0, 0.0, interpolation)
    return dst to scale
}

/** Converts BGR/BGRA/gray input to a new single-channel 8-bit gray Mat. */
internal fun toGray(src: Mat): Mat {
    val dst = Mat()
    when (src.channels()) {
        1 -> src.copyTo(dst)
        3 -> Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGR2GRAY)
        4 -> Imgproc.cvtColor(src, dst, Imgproc.COLOR_BGRA2GRAY)
        else -> error("Unsupported channel count ${src.channels()}")
    }
    return dst
}

/** 256-bin histogram of a single-channel 8-bit Mat, optionally restricted by [mask]. */
internal fun histogram8U(gray: Mat, mask: Mat? = null): DoubleArray {
    val hist = Mat()
    return releasing(hist) {
        Imgproc.calcHist(listOf(gray), MatOfInt(0), mask ?: Mat(), hist, MatOfInt(256), MatOfFloat(0f, 256f))
        DoubleArray(256) { hist.get(it, 0)[0] }
    }
}

/** Value at fraction [p] (0..1) of the cumulative histogram. */
internal fun percentile(hist: DoubleArray, p: Double): Int {
    val total = hist.sum()
    if (total <= 0) return 0
    val target = p.coerceIn(0.0, 1.0) * total
    var cum = 0.0
    for (i in hist.indices) {
        cum += hist[i]
        if (cum >= target) return i
    }
    return hist.lastIndex
}
