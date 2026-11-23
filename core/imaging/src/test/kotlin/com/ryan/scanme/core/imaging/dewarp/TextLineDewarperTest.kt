package com.ryan.scanme.core.imaging.dewarp

import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Acceptance criterion: a curved book page comes out with straight text lines (deviation < 2% of the page
 * height). Each text line is also drawn into a label image (value = line number) that goes through the same
 * curl and the same dewarp remap, so straightness is measured on ground truth, not on the dewarper's own
 * line detection.
 */
class TextLineDewarperTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val dewarper = TextLineDewarper()

    /** Book curl: spine on the left, bow changes sign from top to bottom (non-polynomial on purpose). */
    private val bookCurl = { x: Double, y: Double ->
        H * (exp(-2.5 * x / W) * (0.03 + 0.05 * (2 * y / H - 1)) + 0.008 * sin(PI * x / W))
    }

    @Test
    fun `curved book page comes out with straight lines`() {
        for (seed in 1..3) {
            val (page, labels) = drawPage(Random(seed))
            val curved = curl(page, bookCurl, Imgproc.INTER_LINEAR)
            val curvedLabels = curl(labels, bookCurl, Imgproc.INTER_NEAREST)
            val before = lineDeviation(curvedLabels)

            val start = System.nanoTime()
            val model = dewarper.estimate(curved)
            val estimateMs = (System.nanoTime() - start) / 1e6
            checkNotNull(model) { "seed $seed: no curl detected" }
            // Edge rows must not sample outside the image (that smeared the border into streaks).
            for (x in listOf(0.0, 0.5, 1.0)) {
                assertEquals(0.0, model.displacement(x, 0.0), 1e-9)
                assertEquals(0.0, model.displacement(x, 1.0), 1e-9)
            }
            val flat = model.remap(curved)
            val flatLabels = model.remap(curvedLabels, Imgproc.INTER_NEAREST)
            val after = lineDeviation(flatLabels)
            println("dewarp seed $seed: deviation %.2f%% -> %.2f%% (estimate %.0f ms)".format(before * 100, after * 100, estimateMs))

            assertTrue(before > 0.03,"seed $seed: test curl too weak ($before)")
            assertTrue(after < 0.02, "seed $seed: deviation after dewarp ${after * 100}% >= 2%")

            if (seed == 1) writeSheet(curved, flat)
            listOf(page, labels, curved, curvedLabels, flat, flatLabels).forEach { it.release() }
        }
    }

    @Test
    fun `flat page is left untouched`() {
        val (page, labels) = drawPage(Random(5))
        val model = dewarper.estimate(page)
        assertNull(model, "flat page should not be dewarped (bow ${model?.maxBow()})")
        page.release()
        labels.release()
    }

    @Test
    fun `page without text is left untouched`() {
        val page = Mat(H, W, CvType.CV_8UC3, PAPER)
        Imgproc.rectangle(page, Point(100.0, 100.0), Point(500.0, 600.0), Scalar(170.0, 95.0, 35.0), -1)
        assertNull(dewarper.estimate(page))
        page.release()
    }

    /** Output (x, y) samples the flat page at (x, y - d): a flat line at y0 ends up at about y0 + d(x, y0). */
    private fun curl(src: Mat, d: (Double, Double) -> Double, interpolation: Int): Mat {
        val xs = FloatArray(W * H) { (it % W).toFloat() }
        val ys = FloatArray(W * H) { i -> (i / W - d((i % W).toDouble(), (i / W).toDouble())).toFloat() }
        val mapX = Mat(H, W, CvType.CV_32FC1).apply { put(0, 0, xs) }
        val mapY = Mat(H, W, CvType.CV_32FC1).apply { put(0, 0, ys) }
        val out = Mat()
        val fill = if (src.channels() == 3) PAPER else Scalar(0.0)
        Imgproc.remap(src, out, mapX, mapY, interpolation, Core.BORDER_CONSTANT, fill)
        mapX.release()
        mapY.release()
        return out
    }

    /**
     * Worst line: peak-to-peak of the line's ink centroid across 40 px column buckets, as a fraction of the
     * page height. 40 px averages over letter shapes so a straight line measures ~0.3%.
     */
    private fun lineDeviation(labels: Mat): Double {
        val px = ByteArray(W * H)
        labels.get(0, 0, px)
        val buckets = W / BUCKET
        val sum = Array(LINES + 1) { DoubleArray(buckets) }
        val count = Array(LINES + 1) { IntArray(buckets) }
        for (i in px.indices) {
            val v = px[i].toInt() and 0xFF
            val b = (i % W) / BUCKET
            if (v == 0 || v > LINES || b >= buckets) continue
            sum[v][b] += (i / W).toDouble()
            count[v][b]++
        }
        return (1..LINES).maxOf { v ->
            val ys = (0 until buckets).filter { count[v][it] >= 30 }.map { sum[v][it] / count[v][it] }
            if (ys.size < 3) 0.0 else (ys.max() - ys.min()) / H
        }
    }

    private fun drawPage(rnd: Random): Pair<Mat, Mat> {
        val page = Mat(H, W, CvType.CV_8UC3, PAPER)
        val labels = Mat.zeros(H, W, CvType.CV_8UC1)
        Imgproc.rectangle(page, Point(60.0, 50.0), Point(170.0, 110.0), Scalar(170.0, 95.0, 35.0), -1)
        Imgproc.putText(page, "CHAPTER 3", Point(210.0, 100.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.4, INK, 3, Imgproc.LINE_AA)
        for (i in 0 until LINES) {
            val sb = StringBuilder()
            // Ragged right edge and a short last line per paragraph, like real body text.
            val length = if (i % 7 == 6) rnd.nextInt(15, 30) else rnd.nextInt(42, 50)
            while (sb.length < length) sb.append(WORDS[rnd.nextInt(WORDS.size)]).append(' ')
            val org = Point(70.0, 170.0 + i * 32.0)
            val thick = if (rnd.nextInt(5) == 0) 2 else 1
            Imgproc.putText(page, sb.trim().toString(), org, Imgproc.FONT_HERSHEY_SIMPLEX, 0.62, INK, thick, Imgproc.LINE_AA)
            Imgproc.putText(labels, sb.trim().toString(), org, Imgproc.FONT_HERSHEY_SIMPLEX, 0.62, Scalar(i + 1.0), thick, Imgproc.LINE_8)
        }
        return page to labels
    }

    private fun writeSheet(curved: Mat, flat: Mat) {
        val sheet = Mat()
        Core.hconcat(listOf(curved, flat), sheet)
        Imgproc.resize(sheet, sheet, Size(sheet.cols() * 0.7, sheet.rows() * 0.7), 0.0, 0.0, Imgproc.INTER_AREA)
        OpenCvTestSupport.write("dewarp_book.png", sheet)
        sheet.release()
    }

    private companion object {
        const val W = 840
        const val H = 1188
        const val LINES = 28
        const val BUCKET = 40
        val PAPER = Scalar(236.0, 243.0, 247.0)
        val INK = Scalar(35.0, 35.0, 35.0)
        val WORDS = listOf(
            "the", "page", "curve", "book", "spine", "light", "shadow", "reading", "chapter", "line",
            "straight", "paper", "scanner", "document", "text", "camera", "flat", "corner", "edge",
        )
    }
}
