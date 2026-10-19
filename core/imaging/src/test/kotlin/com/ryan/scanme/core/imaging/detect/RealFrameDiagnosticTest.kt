package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.DebugCollector
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.File
import kotlin.test.Test

/**
 * Temporary diagnostic on real phone frames kept OUTSIDE the repo (they contain private documents).
 * Runs only when -Dscanme.realFrames=<dir> is given.
 */
class RealFrameDiagnosticTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    @Test
    fun diagnose() {
        val dir = System.getProperty("scanme.realFrames") ?: return
        File(dir).listFiles { f -> f.extension == "png" || f.extension == "jpg" }?.sorted()?.forEach { file ->
            val img = Imgcodecs.imread(file.absolutePath)
            val frame = Mat()
            // Phone-like analysis frame: 480 px wide.
            Imgproc.resize(img, frame, Size(480.0, img.rows() * 480.0 / img.cols()), 0.0, 0.0, Imgproc.INTER_AREA)
            val debug = DebugCollector()
            val result = ClassicQuadDetector().detect(frame, debug)
            println("${file.name}: best=${result.best?.let { "%s %.3f".format(it.source, it.score) }} candidates=${result.candidates.size}")
            result.candidates.forEach { println("   ${it.source} %.3f".format(it.score)) }
            val out = frame.clone()
            result.candidates.forEach { c ->
                val pts = c.quad.toPixels(frame.cols(), frame.rows()).corners.map { Point(it.x, it.y) }
                Imgproc.polylines(out, listOf(MatOfPoint(*pts.toTypedArray())), true, Scalar(0.0, 220.0, 255.0), 1)
            }
            result.best?.let { c ->
                val pts = c.quad.toPixels(frame.cols(), frame.rows()).corners.map { Point(it.x, it.y) }
                Imgproc.polylines(out, listOf(MatOfPoint(*pts.toTypedArray())), true, Scalar(0.0, 200.0, 0.0), 3)
            }
            val outDir = File(dir, "out").apply { mkdirs() }
            Imgcodecs.imwrite(File(outDir, "${file.nameWithoutExtension}_result.png").absolutePath, out)
            debug.stages.forEach { Imgcodecs.imwrite(File(outDir, "${file.nameWithoutExtension}_${it.name.replace('/', '_')}.png").absolutePath, it.image) }
            debug.release()
        }
    }
}
