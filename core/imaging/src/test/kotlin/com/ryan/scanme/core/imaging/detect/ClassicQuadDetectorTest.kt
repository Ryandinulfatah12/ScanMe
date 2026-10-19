package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ClassicQuadDetectorTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val detector = ClassicQuadDetector()

    /** Max corner error as a fraction of the image diagonal. */
    private fun cornerError(kind: SceneKind, seed: Int): Double {
        val scene = SyntheticScenes.build(kind, seed)
        val w = scene.image.cols()
        val h = scene.image.rows()
        val result = detector.detect(scene.image)
        writeOverlay(kind, seed, scene.image, scene.truth, result)
        val best = assertNotNull(result.best, "$kind seed=$seed: no document found")
        val err = best.quad.toPixels(w, h).maxCornerDistance(scene.truth) / hypot(w.toDouble(), h.toDouble())
        println("%-16s seed=%d source=%-22s score=%.2f err=%.2f%% candidates=%d".format(kind, seed, best.source, best.score, err * 100, result.candidates.size))
        for (c in result.candidates) {
            val e = c.quad.toPixels(w, h).maxCornerDistance(scene.truth) / hypot(w.toDouble(), h.toDouble())
            println("    candidate %-22s score=%.3f err=%.2f%%".format(c.source, c.score, e * 100))
        }
        scene.release()
        return err
    }

    /** Truth in white, every candidate in yellow, the chosen outline in green. */
    private fun writeOverlay(kind: SceneKind, seed: Int, image: Mat, truth: Quad, result: DetectionResult) {
        val out = image.clone()
        fun draw(q: Quad, color: Scalar, thickness: Int) {
            val pts = q.corners.map { Point(it.x, it.y) }.toTypedArray()
            Imgproc.polylines(out, listOf(MatOfPoint(*pts)), true, color, thickness)
        }
        result.candidates.forEach { draw(it.quad.toPixels(image.cols(), image.rows()), Scalar(0.0, 220.0, 255.0), 2) }
        draw(truth, Scalar(255.0, 255.0, 255.0), 2)
        result.best?.let { draw(it.quad.toPixels(image.cols(), image.rows()), Scalar(0.0, 200.0, 0.0), 5) }
        Imgproc.resize(out, out, Size(600.0, 800.0), 0.0, 0.0, Imgproc.INTER_AREA)
        OpenCvTestSupport.write("detect_${kind.name.lowercase()}_$seed.png", out)
        out.release()
    }

    private fun assertDetects(kind: SceneKind, maxError: Double = 0.02) {
        for (seed in 1..3) {
            val err = cornerError(kind, seed)
            assertTrue(err <= maxError, "$kind seed=$seed corner error ${"%.2f".format(err * 100)}% > ${maxError * 100}%")
        }
    }

    @Test fun flat() = assertDetects(SceneKind.FLAT)

    @Test fun shadow() = assertDetects(SceneKind.SHADOW)

    @Test fun crumpled() = assertDetects(SceneKind.CRUMPLED)

    @Test fun lowContrast() = assertDetects(SceneKind.LOW_CONTRAST)

    @Test fun busyBackground() = assertDetects(SceneKind.BUSY_BACKGROUND)

    @Test fun curvedBook() = assertDetects(SceneKind.CURVED_BOOK)

    /** White paper on a white desk: ~15 grey levels of contrast at the border. */
    @Test fun whiteOnWhite() = assertDetects(SceneKind.WHITE_ON_WHITE)

    /** Hand-held page: fingers cover parts of three edges, so no side is fully visible. */
    @Test fun fingers() = assertDetects(SceneKind.FINGERS)

    @Test
    fun `page running off the frame is flagged as cut off`() {
        for (seed in 1..3) {
            val scene = SyntheticScenes.build(SceneKind.CUT_OFF, seed)
            val result = detector.detect(scene.image)
            writeOverlay(SceneKind.CUT_OFF, seed, scene.image, scene.truth, result)
            val best = assertNotNull(result.best, "seed=$seed: cut-off page not detected at all")
            println("CUT_OFF seed=$seed source=${best.source} score=%.2f cutOff=${best.cutOff}".format(best.score))
            assertTrue(best.cutOff, "seed=$seed: page runs off the frame but was not flagged")
            scene.release()
        }
    }

    @Test
    fun `reports timing at preview resolution`() {
        val scene = SyntheticScenes.build(SceneKind.SHADOW)
        val preview = Mat()
        Imgproc.resize(scene.image, preview, Size(480.0, 640.0), 0.0, 0.0, Imgproc.INTER_AREA)
        repeat(5) { detector.detect(preview) } // warm-up
        val runs = 20
        val start = System.nanoTime()
        repeat(runs) { detector.detect(preview) }
        val ms = (System.nanoTime() - start) / 1e6 / runs
        // Desktop JVM timing is only indicative; the < 50 ms criterion is verified on device (benchmark module).
        println("detect @640px: %.1f ms/frame (desktop JVM)".format(ms))
        preview.release()
        scene.release()
    }
}
