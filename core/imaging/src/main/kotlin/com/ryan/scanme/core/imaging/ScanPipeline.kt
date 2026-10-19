package com.ryan.scanme.core.imaging

import com.ryan.scanme.core.imaging.detect.ClassicQuadDetector
import com.ryan.scanme.core.imaging.detect.DetectionResult
import com.ryan.scanme.core.imaging.dewarp.Dewarper
import com.ryan.scanme.core.imaging.dewarp.NoOpDewarper
import com.ryan.scanme.core.imaging.enhance.FilterEngine
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.warp.PerspectiveCorrector
import org.opencv.core.Mat

data class ProcessRequest(
    /** Normalized page outline in the source photo. */
    val quad: Quad,
    /** Clockwise, multiple of 90. */
    val rotationDegrees: Int = 0,
    val dewarp: Boolean = false,
    val filter: FilterSettings = FilterSettings(),
    /** Camera focal length in pixels of the source photo (from camera metadata); improves aspect ratio. */
    val focalLengthPx: Double? = null,
)

/**
 * Entry point of `:core:imaging`. Pure JVM + OpenCV; the caller must have loaded the OpenCV natives.
 *
 * Split in two so the editor can change the filter without redoing geometry:
 * [rectify] (warp + dewarp + rotation, the expensive and cacheable part) and [filter].
 */
class ScanPipeline(
    private val detector: ClassicQuadDetector = ClassicQuadDetector(),
    private val corrector: PerspectiveCorrector = PerspectiveCorrector(),
    private val dewarper: Dewarper = NoOpDewarper,
    private val filters: FilterEngine = FilterEngine(),
) {
    fun detect(image: Mat, debug: DebugSink? = null): DetectionResult = detector.detect(image, debug)

    /** [source] BGR photo. Returns a new BGR Mat: the flat, upright page before filtering. */
    fun rectify(source: Mat, request: ProcessRequest, debug: DebugSink? = null): Mat {
        var page = corrector.correct(source, request.quad, request.focalLengthPx, debug)
        if (request.dewarp) {
            val flat = dewarper.dewarp(page, debug)
            page.release()
            page = flat
        }
        if (Math.floorMod(request.rotationDegrees, 360) == 0) return page
        return releasing(page) { page.rotatedClockwise(request.rotationDegrees) }
    }

    fun filter(page: Mat, settings: FilterSettings, debug: DebugSink? = null): Mat = filters.apply(page, settings, debug)

    fun process(source: Mat, request: ProcessRequest, debug: DebugSink? = null): Mat {
        val page = rectify(source, request, debug)
        return releasing(page) { filter(page, request.filter, debug) }
    }
}
