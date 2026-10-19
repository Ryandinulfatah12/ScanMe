package com.ryan.scanme.core.imaging

import com.ryan.scanme.core.imaging.enhance.FilterPreset
import com.ryan.scanme.core.imaging.enhance.FilterSettings
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * End to end: detect -> rectify -> filter on every scene kind. Writes a contact sheet per scene to
 * build/test-output (input with detected outline | AUTO | GRAYSCALE | B&W) for visual review.
 */
class ScanPipelineTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val pipeline = ScanPipeline()

    @Test
    fun `every scene kind produces a clean page`() {
        // CUT_OFF is not a capturable page (the app asks the user to move back).
        for (kind in SceneKind.entries - SceneKind.CUT_OFF) {
            val scene = SyntheticScenes.build(kind)
            val start = System.nanoTime()
            val best = assertNotNull(pipeline.detect(scene.image).best, "$kind: nothing detected")
            val detectMs = (System.nanoTime() - start) / 1e6

            val t0 = System.nanoTime()
            val page = pipeline.rectify(scene.image, ProcessRequest(best.quad))
            val rectifyMs = (System.nanoTime() - t0) / 1e6
            val outputs = FilterPreset.entries.filter { it != FilterPreset.ORIGINAL }.map { preset ->
                val t1 = System.nanoTime()
                val out = pipeline.filter(page, FilterSettings(preset))
                println("  %-10s %-12s %.0f ms".format(kind, preset, (System.nanoTime() - t1) / 1e6))
                out
            }
            println("%-16s detect %.0f ms, rectify %.0f ms, page %dx%d".format(kind, detectMs, rectifyMs, page.cols(), page.rows()))

            // Paper should be white in AUTO: sample the bright 60% of the page, which is paper.
            val auto = outputs[0]
            val gray = Mat()
            Imgproc.cvtColor(auto, gray, Imgproc.COLOR_BGR2GRAY)
            val median = percentile(histogram8U(gray), 0.5)
            assertTrue(median > 235, "$kind: AUTO median brightness $median too dark")

            writeSheet(kind, scene.image, best.quad, outputs)
            (outputs + listOf(page, gray)).forEach { it.release() }
            scene.release()
        }
    }

    private fun writeSheet(kind: SceneKind, input: Mat, quad: com.ryan.scanme.core.imaging.geometry.Quad, outputs: List<Mat>) {
        val h = 800.0
        val tiles = mutableListOf<Mat>()
        val annotated = input.clone()
        val px = quad.toPixels(input.cols(), input.rows()).corners.map { Point(it.x, it.y) }
        Imgproc.polylines(annotated, listOf(MatOfPoint(*px.toTypedArray())), true, Scalar(0.0, 220.0, 0.0), 6)
        for (m in listOf(annotated) + outputs) {
            val t = Mat()
            Imgproc.resize(m, t, Size(m.cols() * h / m.rows(), h), 0.0, 0.0, Imgproc.INTER_AREA)
            tiles += t
        }
        val sheet = Mat()
        Core.hconcat(tiles, sheet)
        OpenCvTestSupport.write("pipeline_${kind.name.lowercase()}.png", sheet)
        (tiles + listOf(sheet, annotated)).forEach { it.release() }
    }
}
