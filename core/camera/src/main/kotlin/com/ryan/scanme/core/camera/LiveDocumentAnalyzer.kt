package com.ryan.scanme.core.camera

import android.util.Log
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.live.LiveState
import com.ryan.scanme.core.imaging.live.LiveTracker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One analysed preview frame.
 *
 * @property quad smoothed outline, normalized to the upright frame ([frameWidth] x [frameHeight]).
 * @property cutOff part of the page is outside the frame (user should move back).
 * @property detectMs time spent in detection for this frame (the < 50 ms acceptance criterion).
 */
data class LiveDetection(
    val quad: Quad?,
    val frameWidth: Int,
    val frameHeight: Int,
    val state: LiveState,
    val cutOff: Boolean,
    val detectMs: Double,
)

/** ImageAnalysis analyzer: luma -> upright -> [LiveTracker]. Runs on the analysis executor. */
class LiveDocumentAnalyzer(
    private val tracker: LiveTracker = LiveTracker(),
) : ImageAnalysis.Analyzer {

    private val _detections = MutableStateFlow<LiveDetection?>(null)
    val detections: StateFlow<LiveDetection?> = _detections.asStateFlow()

    @Volatile
    var paused: Boolean = false

    /** Call when a photo is taken so auto-capture waits for the next page. */
    @Synchronized
    fun onCaptured() = tracker.onCaptured()

    private var perfFrames = 0
    private var perfTotalMs = 0.0
    private var perfMaxMs = 0.0

    /**
     * Logs mean/max detection time every 60 frames (tag ScanMePerf), so the < 50 ms/frame criterion can be
     * checked on any device with `adb logcat -s ScanMePerf`, release builds included.
     */
    private fun logPerf(ms: Double, w: Int, h: Int) {
        perfFrames++
        perfTotalMs += ms
        perfMaxMs = maxOf(perfMaxMs, ms)
        if (perfFrames == 60) {
            Log.i("ScanMePerf", "detect %dx%d: mean %.1f ms, max %.1f ms over 60 frames".format(w, h, perfTotalMs / 60, perfMaxMs))
            perfFrames = 0
            perfTotalMs = 0.0
            perfMaxMs = 0.0
        }
    }

    @Synchronized
    override fun analyze(image: ImageProxy) {
        image.use {
            if (paused) return
            val timestampMs = image.imageInfo.timestamp / 1_000_000
            val frame = image.lumaToMat().rotatedUpright(image.imageInfo.rotationDegrees)
            try {
                val r = tracker.update(frame, timestampMs)
                _detections.value = LiveDetection(r.quad, frame.cols(), frame.rows(), r.state, r.cutOff, r.detectMs)
                logPerf(r.detectMs, frame.cols(), frame.rows())
            } finally {
                frame.release()
            }
        }
    }
}
