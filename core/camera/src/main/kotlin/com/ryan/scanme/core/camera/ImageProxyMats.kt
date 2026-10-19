package com.ryan.scanme.core.camera

import androidx.camera.core.ImageProxy
import com.ryan.scanme.core.imaging.rotatedClockwise
import org.opencv.core.CvType
import org.opencv.core.Mat

/**
 * Copies the luma (Y) plane of a YUV_420_888 frame into a new 8-bit gray Mat.
 *
 * Live detection only needs luma, so this skips YUV->RGB conversion entirely. Row stride can exceed the
 * width (padding), so rows are copied individually when needed.
 */
fun ImageProxy.lumaToMat(): Mat {
    val plane = planes[0]
    val buffer = plane.buffer.duplicate().apply { rewind() }
    val rowStride = plane.rowStride
    val mat = Mat(height, width, CvType.CV_8UC1)
    if (rowStride == width && plane.pixelStride == 1) {
        val bytes = ByteArray(width * height)
        buffer.get(bytes)
        mat.put(0, 0, bytes)
    } else {
        val row = ByteArray(width)
        for (y in 0 until height) {
            buffer.position(y * rowStride)
            buffer.get(row, 0, width)
            mat.put(y, 0, row)
        }
    }
    return mat
}

/** The encoded JPEG of an ImageCapture image. Rotation is NOT applied; see [ImageProxy.getImageInfo]. */
fun ImageProxy.jpegBytes(): ByteArray {
    val buffer = planes[0].buffer.duplicate().apply { rewind() }
    return ByteArray(buffer.remaining()).also { buffer.get(it) }
}

/** Returns the receiver rotated clockwise by [degrees] (0/90/180/270), releasing the receiver if rotated. */
fun Mat.rotatedUpright(degrees: Int): Mat {
    if (Math.floorMod(degrees, 360) == 0) return this
    return rotatedClockwise(degrees).also { release() }
}
