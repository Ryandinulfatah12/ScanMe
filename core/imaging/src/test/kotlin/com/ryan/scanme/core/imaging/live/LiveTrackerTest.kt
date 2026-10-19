package com.ryan.scanme.core.imaging.live

import com.ryan.scanme.core.imaging.detect.ClassicQuadDetector
import com.ryan.scanme.core.imaging.detect.DetectionResult
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveTrackerTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    /** Scene downscaled to a 480x640 preview frame. */
    private fun frame(kind: SceneKind, seed: Int = 1): Mat {
        val scene = SyntheticScenes.build(kind, seed)
        val out = Mat()
        Imgproc.resize(scene.image, out, Size(480.0, 640.0), 0.0, 0.0, Imgproc.INTER_AREA)
        scene.release()
        return out
    }

    private val blank = Mat(640, 480, CvType.CV_8UC3, Scalar(90.0, 110.0, 130.0))

    @Test
    fun `outline appears on the first frame and survives short dropouts`() {
        val tracker = LiveTracker()
        val page = frame(SceneKind.FLAT)
        var t = 0L
        assertNotNull(tracker.update(page, t).quad, "first frame should already show the outline")
        t += 33
        tracker.update(page, t)

        // Two bad frames (e.g. motion blur): outline is held, not blinking off.
        t += 33
        assertNotNull(tracker.update(blank, t).quad)
        t += 33
        assertNotNull(tracker.update(blank, t).quad)

        // Gone for longer than the hold: outline disappears.
        repeat(15) { t += 33; tracker.update(blank, t) }
        assertNull(tracker.update(blank, t + 33).quad)
    }

    @Test
    fun `auto capture still arms when single frames drop out`() {
        // Same still image every frame, but detection fails on every 5th (as with glare or blur).
        val real = ClassicQuadDetector()
        var calls = 0
        val tracker = LiveTracker(detect = { m ->
            val r = real.detect(m)
            if (calls++ % 5 == 4) DetectionResult(null, emptyList()) else r
        })
        val page = frame(SceneKind.SHADOW)
        var t = 0L
        var state: LiveState = LiveState.NoDocument
        while (state !is LiveState.ReadyToCapture && t < 3000) {
            state = tracker.update(page, t).state
            t += 33
        }
        assertIs<LiveState.ReadyToCapture>(state)
        assertTrue(t < 1200, "armed only after $t ms")
    }

    @Test
    fun `cut off page is shown but never auto captured`() {
        val tracker = LiveTracker()
        val page = frame(SceneKind.CUT_OFF)
        var t = 0L
        var sawCutOff = false
        repeat(60) {
            val r = tracker.update(page, t)
            if (r.cutOff) sawCutOff = true
            assertFalse(r.state is LiveState.ReadyToCapture, "must not auto capture a cut-off page")
            t += 33
        }
        assertTrue(sawCutOff, "cut-off page never flagged")
    }
}
