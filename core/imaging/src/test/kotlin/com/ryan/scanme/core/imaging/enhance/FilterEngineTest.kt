package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.metrics.QualityMetrics
import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.Scene
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import com.ryan.scanme.core.imaging.warp.PerspectiveCorrector
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Filter checks against the acceptance criteria. Pages are rectified with the ground-truth corners so
 * these tests isolate the filters from detection.
 */
class FilterEngineTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val engine = FilterEngine()
    private val toRelease = mutableListOf<Mat>()
    private val scenes = mutableListOf<Scene>()

    @AfterTest
    fun cleanup() {
        toRelease.forEach { it.release() }
        scenes.forEach { it.release() }
    }

    private fun <T : Mat> T.track(): T = also { toRelease += it }

    /** Rectifies with ground truth and resizes to the page masks' size. */
    private fun rectified(kind: SceneKind, castBgr: DoubleArray? = null): Pair<Scene, Mat> {
        val scene = SyntheticScenes.build(kind).also { scenes += it }
        val src = if (castBgr != null) SyntheticScenes.withColorCast(scene.image, castBgr[0], castBgr[1], castBgr[2]).track() else scene.image
        val page = PerspectiveCorrector().correct(src, scene.truth.toNormalized(src.cols(), src.rows()), scene.focalPx).track()
        Imgproc.resize(page, page, Size(scene.pageWidth.toDouble(), scene.pageHeight.toDouble()), 0.0, 0.0, Imgproc.INTER_AREA)
        return scene to page
    }

    /** Blank paper: away from ink by 9 px and from the page border by 12 px (warp edge blur). */
    private fun paperMask(scene: Scene): Mat {
        val mask = Mat().track()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_ELLIPSE, Size(19.0, 19.0)).track()
        Imgproc.dilate(scene.ink, mask, k)
        Core.bitwise_not(mask, mask)
        val inner = Mat.zeros(mask.size(), CvType.CV_8UC1).track()
        Imgproc.rectangle(inner, org.opencv.core.Point(12.0, 12.0), org.opencv.core.Point(mask.cols() - 13.0, mask.rows() - 13.0), org.opencv.core.Scalar(255.0), -1)
        Core.bitwise_and(mask, inner, mask)
        return mask
    }

    private fun eroded(mask: Mat, size: Double = 3.0): Mat {
        val out = Mat().track()
        val k = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(size, size)).track()
        Imgproc.erode(mask, out, k)
        return out
    }

    private fun report(name: String, values: Map<String, Double>) =
        println("%-28s %s".format(name, values.entries.joinToString { (k, v) -> "$k=%.2f".format(v) }))

    @Test
    fun `auto color whitens shadowed paper and keeps text`() {
        val (scene, page) = rectified(SceneKind.SHADOW)
        val paper = paperMask(scene)
        val beforeP5 = 0.0.let { QualityMetrics.medianLStar(page, paper) }
        val out = engine.apply(page, FilterSettings(FilterPreset.AUTO_COLOR)).track()
        OpenCvTestSupport.write("filter_auto_shadow.png", out)

        val paperL = QualityMetrics.medianLStar(out, paper)
        val paperDarkFraction = QualityMetrics.fractionDarkerThan(out, paper, 85.0)
        val inkL = QualityMetrics.meanLStar(out, eroded(scene.ink))
        val thinDark = QualityMetrics.fractionDarkerThan(out, scene.thinInk, 75.0)
        report("auto/shadow", mapOf("paperBefore" to beforeP5, "paperL*" to paperL, "paper<85" to paperDarkFraction, "inkL*" to inkL, "thinInk<75" to thinDark))

        assertTrue(paperL > 90, "paper L* $paperL must be > 90")
        assertTrue(paperDarkFraction < 0.02, "shadow not removed: ${paperDarkFraction * 100}% of paper below L* 85")
        assertTrue(inkL < 40, "ink too light: L* $inkL")
        assertTrue(thinDark > 0.6, "thin text lost: only ${thinDark * 100}% of it is darker than L* 75")
    }

    @Test
    fun `auto color keeps the red stamp red`() {
        val (scene, page) = rectified(SceneKind.FLAT)
        val out = engine.apply(page, FilterSettings(FilterPreset.AUTO_COLOR)).track()
        val lab = Mat().track()
        Imgproc.cvtColor(out, lab, Imgproc.COLOR_BGR2Lab)
        val a = Core.mean(lab, eroded(scene.stamp)).`val`[1]
        report("auto/stamp", mapOf("a(8bit)" to a))
        assertTrue(a > 150, "stamp lost its red: a=$a (128 = neutral)")
    }

    @Test
    fun `auto color keeps a solid logo dark and blue instead of washing it out`() {
        for (kind in listOf(SceneKind.FLAT, SceneKind.SHADOW)) {
            val (scene, page) = rectified(kind)
            val out = engine.apply(page, FilterSettings(FilterPreset.AUTO_COLOR)).track()
            val interior = eroded(scene.logo, 15.0)
            val lab = Mat().track()
            Imgproc.cvtColor(out, lab, Imgproc.COLOR_BGR2Lab)
            val m = Core.mean(lab, interior).`val`
            val lStar = m[0] * 100 / 255
            report("auto/logo/${kind.name.lowercase()}", mapOf("L*" to lStar, "b(8bit)" to m[2]))
            assertTrue(lStar < 55, "$kind: logo washed out, L* $lStar")
            assertTrue(m[2] < 110, "$kind: logo lost its blue, b=${m[2]} (128 = neutral)")
        }
    }

    @Test
    fun `auto color handles crumpled paper and book spine shading`() {
        for (kind in listOf(SceneKind.CRUMPLED, SceneKind.CURVED_BOOK, SceneKind.LOW_CONTRAST)) {
            val (scene, page) = rectified(kind)
            val out = engine.apply(page, FilterSettings(FilterPreset.AUTO_COLOR)).track()
            OpenCvTestSupport.write("filter_auto_${kind.name.lowercase()}.png", out)
            val paperL = QualityMetrics.medianLStar(out, paperMask(scene))
            report("auto/${kind.name.lowercase()}", mapOf("paperL*" to paperL))
            assertTrue(paperL > 90, "$kind paper L* $paperL")
        }
    }

    @Test
    fun `grayscale has equal channels and white paper`() {
        val (scene, page) = rectified(SceneKind.SHADOW)
        val out = engine.apply(page, FilterSettings(FilterPreset.GRAYSCALE)).track()
        OpenCvTestSupport.write("filter_gray_shadow.png", out)
        val ch = mutableListOf<Mat>()
        Core.split(out, ch)
        val diff = Mat().track()
        Core.absdiff(ch[0], ch[2], diff)
        assertEquals(0, Core.countNonZero(diff))
        ch.forEach { it.release() }
        assertTrue(QualityMetrics.medianLStar(out, paperMask(scene)) > 90)
    }

    @Test
    fun `black and white is binary with high text recall`() {
        val (scene, page) = rectified(SceneKind.SHADOW)
        val out = engine.apply(page, FilterSettings(FilterPreset.BLACK_WHITE)).track()
        OpenCvTestSupport.write("filter_bw_shadow.png", out)
        val gray = Mat().track()
        Imgproc.cvtColor(out, gray, Imgproc.COLOR_BGR2GRAY)
        val mid = Mat().track()
        Core.inRange(gray, org.opencv.core.Scalar(1.0), org.opencv.core.Scalar(254.0), mid)
        assertEquals(0, Core.countNonZero(mid), "output must be pure black/white")

        val recall = QualityMetrics.fractionDarkerThan(out, eroded(scene.ink), 50.0)
        val falseInk = QualityMetrics.fractionDarkerThan(out, paperMask(scene), 50.0)
        val thinRecall = QualityMetrics.fractionDarkerThan(out, scene.thinInk, 50.0)
        report("bw/shadow", mapOf("recall" to recall, "falseInk" to falseInk, "thinRecall" to thinRecall))
        assertTrue(recall > 0.85, "ink recall $recall")
        assertTrue(thinRecall > 0.5, "thin text recall $thinRecall")
        assertTrue(falseInk < 0.005, "noise on paper $falseInk")
    }

    @Test
    fun `original removes a colour cast from the paper`() {
        val (scene, page) = rectified(SceneKind.FLAT, castBgr = doubleArrayOf(0.78, 0.95, 1.12))
        val paper = paperMask(scene)
        fun paperAB(img: Mat): Pair<Double, Double> {
            val lab = Mat().track()
            Imgproc.cvtColor(img, lab, Imgproc.COLOR_BGR2Lab)
            val m = Core.mean(lab, paper).`val`
            return m[1] to m[2]
        }
        val (a0, b0) = paperAB(page)
        val out = engine.apply(page, FilterSettings(FilterPreset.ORIGINAL)).track()
        val (a1, b1) = paperAB(out)
        report("original/cast", mapOf("aBefore" to a0, "bBefore" to b0, "aAfter" to a1, "bAfter" to b1))
        assertTrue(kotlin.math.abs(b0 - 128) > 8, "test cast too weak to be meaningful")
        assertTrue(kotlin.math.abs(a1 - 128) < 4 && kotlin.math.abs(b1 - 128) < 4, "paper not neutral: a=$a1 b=$b1")
    }

    @Test
    fun `intensity zero returns the rectified page`() {
        val (_, page) = rectified(SceneKind.FLAT)
        val out = engine.apply(page, FilterSettings(FilterPreset.AUTO_COLOR, intensity = 0.0)).track()
        val diff = Mat().track()
        Core.absdiff(out, page, diff)
        assertEquals(0.0, Core.norm(diff, Core.NORM_INF), 1.0)
    }
}
