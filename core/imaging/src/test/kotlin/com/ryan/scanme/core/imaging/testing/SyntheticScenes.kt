package com.ryan.scanme.core.imaging.testing

import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** The sample categories required by AGENTS.md ("Cara Kerja"). */
enum class SceneKind { FLAT, SHADOW, CRUMPLED, BUSY_BACKGROUND, LOW_CONTRAST, CURVED_BOOK, WHITE_ON_WHITE, FINGERS, CUT_OFF }

/**
 * A synthetic photo of an A4 page with known ground truth.
 *
 * @property truth page corners in [image] pixels, from a real pinhole projection (see [Pinhole]).
 * @property focalPx focal length of that camera, as the app would get from camera metadata.
 * @property ink page-space mask (pageWidth x pageHeight) of everything printed, including the stamp.
 * @property thinInk page-space mask of the light, 1 px "thin text" line only.
 * @property stamp page-space mask of the red stamp only.
 * @property logo page-space mask of the solid blue logo block only.
 */
class Scene(
    val kind: SceneKind,
    val image: Mat,
    val truth: Quad,
    val focalPx: Double,
    val pageWidth: Int,
    val pageHeight: Int,
    val ink: Mat,
    val thinInk: Mat,
    val stamp: Mat,
    val logo: Mat,
) {
    fun release() = listOf(image, ink, thinInk, stamp, logo).forEach { it.release() }
}

/**
 * Generates deterministic synthetic test scenes. Real photos should replace / complement these once
 * available; synthetic scenes give exact ground truth for corners and ink.
 */
object SyntheticScenes {
    const val PAGE_W = 840
    const val PAGE_H = 1188 // A4 ratio
    const val CANVAS_W = 1200
    const val CANVAS_H = 1600

    private val INK = Scalar(35.0, 35.0, 35.0)
    private val THIN_INK = Scalar(110.0, 110.0, 110.0)
    private val STAMP_RED = Scalar(60.0, 50.0, 200.0)
    private val LOGO_BLUE = Scalar(170.0, 95.0, 35.0)
    private val PAPER = Scalar(236.0, 243.0, 247.0)

    private val WORDS = listOf(
        "invoice", "number", "date", "amount", "total", "payment", "customer", "address", "item",
        "quantity", "price", "tax", "subtotal", "account", "reference", "due", "terms", "service",
    )

    fun build(kind: SceneKind, seed: Int = 7): Scene {
        val rnd = Random(seed)
        val (page, ink, thin, stamp, logo) = drawPage(rnd)
        when (kind) {
            SceneKind.CRUMPLED -> crumple(page, rnd)
            SceneKind.CURVED_BOOK -> bend(listOf(page, ink, thin, stamp, logo))
            else -> Unit
        }

        // Hand-held pose: up to ~17 deg pitch, ~14 deg yaw, ~11 deg roll; page fills ~70% of frame height.
        val cut = kind == SceneKind.CUT_OFF
        val truth = camera.projectA4(
            rx = rnd.nextDouble(-0.3, 0.3),
            ry = rnd.nextDouble(-0.25, 0.25),
            rz = rnd.nextDouble(-0.2, 0.2),
            // CUT_OFF: too close and shifted sideways, so one side runs past the frame edge.
            distanceMm = if (cut) 400.0 else rnd.nextDouble(480.0, 530.0),
            txMm = if (cut) 75.0 else rnd.nextDouble(-15.0, 15.0),
            tyMm = rnd.nextDouble(-15.0, 15.0),
        )
        val canvas = background(kind, rnd)
        composite(canvas, page, truth)
        page.release()

        when (kind) {
            SceneKind.FINGERS -> fingers(canvas, truth)
            SceneKind.SHADOW -> shade(canvas)
            SceneKind.CURVED_BOOK -> spineShading(canvas, truth)
            else -> Unit
        }
        cameraNoise(canvas, rnd)
        return Scene(kind, canvas, truth, camera.focalPx, PAGE_W, PAGE_H, ink, thin, stamp, logo)
    }

    private val camera = Pinhole(CANVAS_W, CANVAS_H, focalPx = 1.2 * CANVAS_H)

    /** Multiplies B,G,R by the given gains (simulates a coloured light cast). */
    fun withColorCast(bgr: Mat, b: Double, g: Double, r: Double): Mat {
        val out = Mat()
        Core.multiply(bgr, Scalar(b, g, r), out)
        return out
    }

    private data class PageLayers(val page: Mat, val ink: Mat, val thin: Mat, val stamp: Mat, val logo: Mat)

    private fun drawPage(rnd: Random): PageLayers {
        val page = Mat(PAGE_H, PAGE_W, CvType.CV_8UC3, PAPER)
        val ink = Mat.zeros(PAGE_H, PAGE_W, CvType.CV_8UC1)
        val thin = Mat.zeros(PAGE_H, PAGE_W, CvType.CV_8UC1)
        val stamp = Mat.zeros(PAGE_H, PAGE_W, CvType.CV_8UC1)
        val logo = Mat.zeros(PAGE_H, PAGE_W, CvType.CV_8UC1)
        val white = Scalar(255.0)
        val font = Imgproc.FONT_HERSHEY_SIMPLEX

        fun text(s: String, x: Int, y: Int, scale: Double, thick: Int, color: Scalar, mask: Mat = ink) {
            Imgproc.putText(page, s, Point(x.toDouble(), y.toDouble()), font, scale, color, thick, Imgproc.LINE_AA)
            Imgproc.putText(mask, s, Point(x.toDouble(), y.toDouble()), font, scale, white, thick, Imgproc.LINE_8)
        }

        // Blue logo block + title
        Imgproc.rectangle(page, Point(60.0, 60.0), Point(170.0, 130.0), LOGO_BLUE, -1)
        Imgproc.rectangle(ink, Point(60.0, 60.0), Point(170.0, 130.0), white, -1)
        Imgproc.rectangle(logo, Point(60.0, 60.0), Point(170.0, 130.0), white, -1)
        text("INVOICE 2026", 210, 115, 1.6, 3, INK)

        // Body text: normal and bold lines
        var y = 200
        while (y < 900) {
            val sb = StringBuilder()
            while (sb.length < 48) sb.append(WORDS[rnd.nextInt(WORDS.size)]).append(' ')
            text(sb.toString().trim(), 70, y, 0.62, if (rnd.nextInt(4) == 0) 2 else 1, INK)
            y += 30
        }
        // Thin, light text: must survive the filters ("tanpa menghilangkan teks tipis").
        text("thin light text line for contrast check 0123456789", 70, 940, 0.55, 1, THIN_INK, thin)
        Core.bitwise_or(ink, thin, ink)

        // Table rule lines
        for (ly in intArrayOf(960, 1000)) {
            Imgproc.line(page, Point(70.0, ly.toDouble()), Point(770.0, ly.toDouble()), INK, 2)
            Imgproc.line(ink, Point(70.0, ly.toDouble()), Point(770.0, ly.toDouble()), white, 2)
        }

        // Red stamp (colour must stay natural)
        val c = Point(620.0, 1080.0)
        Imgproc.circle(page, c, 70, STAMP_RED, 7, Imgproc.LINE_AA)
        Imgproc.circle(stamp, c, 70, white, 7)
        Imgproc.putText(page, "PAID", Point(560.0, 1095.0), font, 1.2, STAMP_RED, 3, Imgproc.LINE_AA)
        Imgproc.putText(stamp, "PAID", Point(560.0, 1095.0), font, 1.2, white, 3)
        Core.bitwise_or(ink, stamp, ink)
        return PageLayers(page, ink, thin, stamp, logo)
    }

    private fun background(kind: SceneKind, rnd: Random): Mat {
        val base = when (kind) {
            SceneKind.LOW_CONTRAST -> Scalar(200.0, 204.0, 207.0) // light grey table
            SceneKind.WHITE_ON_WHITE -> Scalar(222.0, 226.0, 229.0) // white desk, ~15 levels below the paper
            else -> Scalar(70.0, 95.0, 130.0) // wood-ish brown
        }
        val bg = Mat(CANVAS_H, CANVAS_W, CvType.CV_8UC3, base)
        addLowFrequency(bg, rnd, amplitude = if (kind == SceneKind.WHITE_ON_WHITE) 6.0 else 18.0)
        if (kind == SceneKind.BUSY_BACKGROUND) {
            repeat(60) {
                val color = Scalar(rnd.nextDouble(0.0, 255.0), rnd.nextDouble(0.0, 255.0), rnd.nextDouble(0.0, 255.0))
                val p1 = Point(rnd.nextDouble(0.0, CANVAS_W.toDouble()), rnd.nextDouble(0.0, CANVAS_H.toDouble()))
                val p2 = Point(p1.x + rnd.nextDouble(-250.0, 250.0), p1.y + rnd.nextDouble(-250.0, 250.0))
                when (rnd.nextInt(3)) {
                    0 -> Imgproc.rectangle(bg, p1, p2, color, -1)
                    1 -> Imgproc.line(bg, p1, p2, color, rnd.nextInt(2, 12))
                    else -> Imgproc.circle(bg, p1, rnd.nextInt(10, 90), color, -1)
                }
            }
            Imgproc.putText(bg, "NEWSPAPER HEADLINE", Point(40.0, 80.0), Imgproc.FONT_HERSHEY_SIMPLEX, 2.0, Scalar(250.0, 250.0, 250.0), 5)
        }
        return bg
    }

    private fun composite(canvas: Mat, page: Mat, truth: Quad) {
        val src = MatOfPoint2f(
            Point(0.0, 0.0), Point(PAGE_W - 1.0, 0.0), Point(PAGE_W - 1.0, PAGE_H - 1.0), Point(0.0, PAGE_H - 1.0),
        )
        val dst = MatOfPoint2f(*truth.corners.map { Point(it.x, it.y) }.toTypedArray())
        val h = Imgproc.getPerspectiveTransform(src, dst)
        val warped = Mat()
        Imgproc.warpPerspective(page, warped, h, canvas.size(), Imgproc.INTER_LINEAR)
        val mask = Mat.zeros(canvas.size(), CvType.CV_8UC1)
        Imgproc.fillConvexPoly(mask, MatOfPoint(*truth.corners.map { Point(it.x, it.y) }.toTypedArray()), Scalar(255.0), Imgproc.LINE_AA)
        warped.copyTo(canvas, mask)
        listOf(src, dst, h, warped, mask).forEach { it.release() }
    }

    /** Two fingers and a thumb holding the page, covering parts of three edges. */
    private fun fingers(canvas: Mat, truth: Quad) {
        val skin = Scalar(120.0, 155.0, 210.0)
        fun along(a: Vec2, b: Vec2, t: Double) = Point(a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t)
        Imgproc.ellipse(canvas, along(truth.tl, truth.bl, 0.55), Size(70.0, 38.0), 15.0, 0.0, 360.0, skin, -1, Imgproc.LINE_AA)
        Imgproc.ellipse(canvas, along(truth.tl, truth.bl, 0.68), Size(70.0, 36.0), 10.0, 0.0, 360.0, skin, -1, Imgproc.LINE_AA)
        Imgproc.ellipse(canvas, along(truth.bl, truth.br, 0.7), Size(34.0, 75.0), -8.0, 0.0, 360.0, skin, -1, Imgproc.LINE_AA)
        Imgproc.ellipse(canvas, along(truth.tr, truth.br, 0.3), Size(65.0, 34.0), -12.0, 0.0, 360.0, skin, -1, Imgproc.LINE_AA)
    }

    /** A soft hand/phone shadow over the lower-right of the page plus a top-to-bottom falloff. */
    private fun shade(canvas: Mat) {
        val field = Mat(canvas.size(), CvType.CV_32FC1, Scalar(1.0))
        val poly = MatOfPoint(Point(500.0, 1600.0), Point(1200.0, 700.0), Point(1200.0, 1600.0))
        Imgproc.fillPoly(field, listOf(poly), Scalar(0.45))
        Imgproc.GaussianBlur(field, field, Size(0.0, 0.0), 45.0)
        for (row in 0 until canvas.rows()) {
            val falloff = 1.0 - 0.15 * row / canvas.rows()
            val r = field.row(row)
            Core.multiply(r, Scalar(falloff), r)
            r.release()
        }
        multiplyByField(canvas, field)
        field.release()
        poly.release()
    }

    /** Darkening toward the left edge (book spine). */
    private fun spineShading(canvas: Mat, truth: Quad) {
        val field = Mat(canvas.size(), CvType.CV_32FC1, Scalar(1.0))
        val spineX = (truth.tl.x + truth.bl.x) / 2
        for (col in 0 until canvas.cols()) {
            val d = (col - spineX).coerceAtLeast(0.0)
            val v = 1.0 - 0.35 * kotlin.math.exp(-d / 90.0)
            val c = field.col(col)
            c.setTo(Scalar(v))
            c.release()
        }
        multiplyByField(canvas, field)
        field.release()
    }

    /** Book-page curl: rows bow vertically, strongest mid-page (curved text lines for dewarp tests). */
    private fun bend(layers: List<Mat>) {
        val mapX = Mat(PAGE_H, PAGE_W, CvType.CV_32FC1)
        val mapY = Mat(PAGE_H, PAGE_W, CvType.CV_32FC1)
        val xs = FloatArray(PAGE_W * PAGE_H)
        val ys = FloatArray(PAGE_W * PAGE_H)
        for (y in 0 until PAGE_H) for (x in 0 until PAGE_W) {
            val i = y * PAGE_W + x
            xs[i] = x.toFloat()
            ys[i] = (y - 22.0 * sin(PI * x / PAGE_W)).toFloat()
        }
        mapX.put(0, 0, xs)
        mapY.put(0, 0, ys)
        for (m in layers) {
            val tmp = Mat()
            val fill = if (m.channels() == 3) PAPER else Scalar(0.0)
            Imgproc.remap(m, tmp, mapX, mapY, Imgproc.INTER_LINEAR, Core.BORDER_CONSTANT, fill)
            if (m.channels() == 1) Imgproc.threshold(tmp, tmp, 127.0, 255.0, Imgproc.THRESH_BINARY)
            tmp.copyTo(m)
            tmp.release()
        }
        mapX.release()
        mapY.release()
    }

    /** Wrinkled paper: low-frequency shading (+-12%) and a few sharp fold lines. */
    private fun crumple(page: Mat, rnd: Random) {
        val field = Mat(6, 5, CvType.CV_32FC1)
        field.put(0, 0, FloatArray(30) { (1.0 + rnd.nextDouble(-0.12, 0.12)).toFloat() })
        Imgproc.resize(field, field, page.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        repeat(4) {
            val p1 = Point(rnd.nextDouble(0.0, PAGE_W.toDouble()), 0.0)
            val p2 = Point(rnd.nextDouble(0.0, PAGE_W.toDouble()), PAGE_H.toDouble())
            Imgproc.line(field, p1, p2, Scalar(0.82), 3)
        }
        Imgproc.GaussianBlur(field, field, Size(0.0, 0.0), 2.0)
        multiplyByField(page, field)
        field.release()
    }

    private fun addLowFrequency(img: Mat, rnd: Random, amplitude: Double) {
        val f = Mat(4, 4, CvType.CV_32FC1)
        f.put(0, 0, FloatArray(16) { rnd.nextDouble(-amplitude, amplitude).toFloat() })
        Imgproc.resize(f, f, img.size(), 0.0, 0.0, Imgproc.INTER_CUBIC)
        val f3 = Mat()
        Core.merge(listOf(f, f, f), f3)
        val tmp = Mat()
        img.convertTo(tmp, CvType.CV_32FC3)
        Core.add(tmp, f3, tmp)
        tmp.convertTo(img, CvType.CV_8UC3)
        listOf(f, f3, tmp).forEach { it.release() }
    }

    private fun multiplyByField(img: Mat, field: Mat) {
        val f3 = Mat()
        Core.merge(listOf(field, field, field), f3)
        val tmp = Mat()
        img.convertTo(tmp, CvType.CV_32FC3)
        Core.multiply(tmp, f3, tmp)
        tmp.convertTo(img, CvType.CV_8UC3)
        f3.release()
        tmp.release()
    }

    /** Slight lens blur and sensor noise (sigma 3), like a mid-range phone in good light. */
    private fun cameraNoise(img: Mat, rnd: Random) {
        Imgproc.GaussianBlur(img, img, Size(3.0, 3.0), 0.8)
        val noise = Mat(img.size(), CvType.CV_32FC3)
        Core.setRNGSeed(rnd.nextInt())
        Core.randn(noise, 0.0, 3.0)
        val tmp = Mat()
        img.convertTo(tmp, CvType.CV_32FC3)
        Core.add(tmp, noise, tmp)
        tmp.convertTo(img, CvType.CV_8UC3)
        noise.release()
        tmp.release()
    }
}
