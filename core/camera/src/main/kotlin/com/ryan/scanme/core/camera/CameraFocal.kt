package com.ryan.scanme.core.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import kotlin.math.max

/**
 * Focal length divided by the sensor's long side (both in mm), from Camera2 metadata.
 *
 * Multiply by a full-FOV photo's long side in pixels to get the focal length in pixels, which makes the
 * page aspect-ratio estimate exact (see PerspectiveCorrector). Null when the HAL does not report it.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun CameraInfo.focalToSensorLongSide(): Double? {
    val info = Camera2CameraInfo.from(this)
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull()
        ?: return null
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val longSide = max(sensor.width, sensor.height)
    return if (focal > 0 && longSide > 0) focal.toDouble() / longSide else null
}
