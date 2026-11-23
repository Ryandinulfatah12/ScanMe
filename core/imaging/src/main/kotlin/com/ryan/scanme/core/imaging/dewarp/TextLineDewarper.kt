package com.ryan.scanme.core.imaging.dewarp

import com.ryan.scanme.core.imaging.DebugSink
import com.ryan.scanme.core.imaging.oddAtLeast
import com.ryan.scanme.core.imaging.releasing
import com.ryan.scanme.core.imaging.resizeLongSide
import com.ryan.scanme.core.imaging.toGray
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Vertical displacement field of a curved page, fitted to its text lines (normalized page coordinates,
 * x and y in 0..1, displacement as a fraction of page height):
 *
 *   D(x, y) = sum over j = 1..3, k = 0..kDegree of a[j,k] * (x^j - 1/(j+1)) * y^k
 *
 * Cubic in x follows the curl across the page (a book page bends most near the spine, so the bow is
 * asymmetric); up to quadratic in y lets the curl change from the top of the page to the bottom (lines
 * above and below the camera axis bow in opposite directions). The x^j - 1/(j+1) terms have zero mean over
 * the width, so D carries no pure vertical shift. Output pixel (x, y) samples the page at (x, y + D(x, y)).
 */
class DewarpModel internal constructor(
    private val coeffs: DoubleArray,
    private val kDegree: Int,
    /** Area covered by line samples; D is held constant outside it instead of extrapolating the polynomial. */
    private val xMin: Double,
    private val xMax: Double,
    private val yMin: Double,
    private val yMax: Double,
) {
    fun displacement(x: Double, y: Double): Double = raw(x, y) * edgeTaper(y)

    /**
     * 1 inside the text rows, falling linearly to 0 at the top and bottom image edges. Without it the
     * displacement at the edge rows samples outside the image and BORDER_REPLICATE smears the last row into
     * vertical streaks (clearly visible on real photos).
     */
    private fun edgeTaper(y: Double): Double = when {
        y < yMin -> (y / yMin).coerceIn(0.0, 1.0)
        y > yMax -> ((1 - y) / (1 - yMax)).coerceIn(0.0, 1.0)
        else -> 1.0
    }

    private fun raw(x: Double, y: Double): Double {
        val xc = x.coerceIn(xMin, xMax)
        val yc = y.coerceIn(yMin, yMax)
        var d = 0.0
        var xj = 1.0
        for (j in 1..3) {
            xj *= xc
            var yk = 1.0
            for (k in 0..kDegree) {
                d += coeffs[(j - 1) * (kDegree + 1) + k] * (xj - 1.0 / (j + 1)) * yk
                yk *= yc
            }
        }
        return d
    }

    /** Peak-to-peak of D along any row, as a fraction of page height: how much the lines bow. */
    fun maxBow(): Double = (0..32).maxOf { gy ->
        val values = (0..32).map { gx -> displacement(gx / 32.0, gy / 32.0) }
        values.max() - values.min()
    }

    /**
     * Returns a new Mat with the curl removed. The field is evaluated on a coarse grid (it is a smooth
     * polynomial) and upsampled, so the cost is one resize plus one remap regardless of resolution.
     */
    fun remap(src: Mat, interpolation: Int = Imgproc.INTER_LINEAR): Mat {
        val w = src.cols()
        val h = src.rows()
        val gw = max(2, w / GRID_STEP)
        val gh = max(2, h / GRID_STEP)
        // Grid cell centres, matching the pixel-centre alignment of Imgproc.resize.
        val grid = FloatArray(gw * gh) { i -> (displacement((i % gw + 0.5) / gw, (i / gw + 0.5) / gh) * h).toFloat() }
        val small = Mat(gh, gw, CvType.CV_32FC1).apply { put(0, 0, grid) }
        val rowIndex = Mat(h, 1, CvType.CV_32FC1).apply { put(0, 0, FloatArray(h) { it.toFloat() }) }
        val colIndex = Mat(1, w, CvType.CV_32FC1).apply { put(0, 0, FloatArray(w) { it.toFloat() }) }
        val rows = Mat()
        val mapX = Mat()
        val mapY = Mat()
        val out = Mat()
        releasing(small, rowIndex, colIndex, rows, mapX, mapY) {
            Imgproc.resize(small, mapY, Size(w.toDouble(), h.toDouble()), 0.0, 0.0, Imgproc.INTER_LINEAR)
            Core.repeat(rowIndex, 1, w, rows)
            Core.add(mapY, rows, mapY)
            Core.repeat(colIndex, h, 1, mapX)
            Imgproc.remap(src, out, mapX, mapY, interpolation, Core.BORDER_REPLICATE)
        }
        return out
    }

    private companion object {
        const val GRID_STEP = 8
    }
}

/**
 * Classic dewarping (option A in AGENTS.md): text lines are the only straight lines guaranteed on a
 * document, so their bow measures the page curl.
 *
 * 1. Work copy at <= [workLongSide] px. Adaptive threshold (window 3% of the width, C 15 so paper texture
 *    and noise stay background while light grey text is still ink).
 * 2. Horizontal closing (2.5% of the width, wider than word gaps, kernel 1 px tall so neighbouring lines
 *    never merge), then a 1x3 vertical opening to cut descender/ascender bridges between lines.
 * 3. Components that are wide (>= 8% of the width) and thin on average (area / width <= 3.5% of the height)
 *    are lines; table rules count too, they bend with the page the same way.
 * 4. Each line is sampled as the ink centroid in column buckets (~1/60 of the width: wide enough to average
 *    over letter shapes).
 * 5. Least-squares fit of [DewarpModel] (one row per sample: observed y minus the line's mean y), then one
 *    pass dropping samples beyond 3x the RMS residual (logos, stray marks) and refitting.
 *
 * The page is left untouched when fewer than [minLines] lines are found (photos, blank pages, vertical
 * text), when the fitted bow is below [minBow] (flat page: avoids resampling for an invisible change),
 * or above [maxBow] (implausible; a bad fit would do more harm than the curl).
 *
 * shortcut: corrects vertical displacement only; the horizontal compression near a book spine stays.
 * Upgrade to a 3D surface model (page_dewarp-style cubic sheet + camera pose) or the ML option B if
 * character widths near the spine matter.
 */
class TextLineDewarper(
    private val workLongSide: Int = 1400,
    private val minLines: Int = 3,
    private val minBow: Double = 0.004,
    private val maxBow: Double = 0.15,
) : Dewarper {

    override fun dewarp(page: Mat, debug: DebugSink?): Mat {
        val model = estimate(page, debug) ?: return page.clone()
        return model.remap(page).also { debug?.stage("dewarp/result", it) }
    }

    /** The fitted curl of [page] (BGR or gray), or null if the page should be left as it is. */
    fun estimate(page: Mat, debug: DebugSink? = null): DewarpModel? {
        val gray = toGray(page)
        val (small, _) = releasing(gray) { resizeLongSide(gray, workLongSide) }
        return releasing(small) {
            val lines = textLines(small, debug)
            val model = if (lines.size >= minLines) fit(lines) else null
            if (debug != null) drawFit(small, lines, model, debug)
            model?.takeIf { it.maxBow() in minBow..maxBow }
        }
    }

    private class Line(val xs: DoubleArray, val ys: DoubleArray) {
        val meanY = ys.average()
    }

    private fun textLines(gray: Mat, debug: DebugSink?): List<Line> {
        val w = gray.cols()
        val h = gray.rows()
        val ink = Mat()
        val labels = Mat()
        val stats = Mat()
        val centroids = Mat()
        return releasing(ink, labels, stats, centroids) {
            Imgproc.adaptiveThreshold(
                gray, ink, 255.0, Imgproc.ADAPTIVE_THRESH_MEAN_C, Imgproc.THRESH_BINARY_INV,
                oddAtLeast(w * 0.03, 15), 15.0,
            )
            val close = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(oddAtLeast(w * 0.025, 9).toDouble(), 1.0))
            val open = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(1.0, 3.0))
            releasing(close, open) {
                Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_CLOSE, close)
                Imgproc.morphologyEx(ink, ink, Imgproc.MORPH_OPEN, open)
            }
            debug?.stage("dewarp/line-mask", ink)

            val n = Imgproc.connectedComponentsWithStats(ink, labels, stats, centroids, 8, CvType.CV_32S)
            // Index of each kept component in the accumulators, -1 = not a line.
            val slot = IntArray(n) { -1 }
            var kept = 0
            for (l in 1 until n) {
                val bw = stats.get(l, Imgproc.CC_STAT_WIDTH)[0]
                val area = stats.get(l, Imgproc.CC_STAT_AREA)[0]
                if (bw >= 0.08 * w && area / bw <= 0.035 * h) slot[l] = kept++
            }
            if (kept == 0) return emptyList()

            val step = max(4, w / 60)
            val buckets = (w + step - 1) / step
            val sumY = DoubleArray(kept * buckets)
            val count = IntArray(kept * buckets)
            val lab = IntArray(w * h)
            labels.get(0, 0, lab)
            for (y in 0 until h) {
                val row = y * w
                for (x in 0 until w) {
                    val s = slot[lab[row + x]]
                    if (s < 0) continue
                    val i = s * buckets + x / step
                    sumY[i] += y.toDouble()
                    count[i]++
                }
            }
            (0 until kept).mapNotNull { s ->
                val xs = ArrayList<Double>()
                val ys = ArrayList<Double>()
                for (b in 0 until buckets) {
                    val i = s * buckets + b
                    // At least ~1 px of ink per column, so a bucket clipped by the blob's end is skipped.
                    if (count[i] < step) continue
                    xs += (b + 0.5) * step / w
                    ys += sumY[i] / count[i] / h
                }
                if (xs.size >= 4) Line(xs.toDoubleArray(), ys.toDoubleArray()) else null
            }
        }
    }

    private fun fit(lines: List<Line>): DewarpModel? {
        val yMin = lines.minOf { it.meanY }
        val yMax = lines.maxOf { it.meanY }
        // y terms only where lines cover enough height to constrain them.
        val kDegree = when {
            yMax - yMin < 0.25 -> 0
            yMax - yMin < 0.5 -> 1
            else -> 2
        }
        val m = 3 * (kDegree + 1)

        // Flattened samples with per-line centring of each x^j term.
        val rows = ArrayList<DoubleArray>()
        val targets = ArrayList<Double>()
        for (line in lines) {
            val mean = DoubleArray(4) { j -> line.xs.sumOf { Math.pow(it, j.toDouble()) } / line.xs.size }
            for (i in line.xs.indices) {
                val x = line.xs[i]
                rows += DoubleArray(m) { c ->
                    val j = c / (kDegree + 1) + 1
                    val k = c % (kDegree + 1)
                    (Math.pow(x, j.toDouble()) - mean[j]) * Math.pow(line.meanY, k.toDouble())
                }
                targets += line.ys[i] - line.meanY
            }
        }
        if (rows.size < 3 * m) return null

        var active = BooleanArray(rows.size) { true }
        var coeffs = DoubleArray(m)
        repeat(2) { pass ->
            coeffs = solve(rows, targets, active, m) ?: return null
            val residual = DoubleArray(rows.size) { i -> targets[i] - rows[i].indices.sumOf { rows[i][it] * coeffs[it] } }
            val rms = sqrt(residual.indices.filter { active[it] }.sumOf { residual[it] * residual[it] } / active.count { it })
            if (pass == 0) active = BooleanArray(rows.size) { abs(residual[it]) <= max(3 * rms, 0.003) }
        }
        return DewarpModel(coeffs, kDegree, lines.minOf { it.xs.first() }, lines.maxOf { it.xs.last() }, yMin, yMax)
    }

    private fun solve(rows: List<DoubleArray>, targets: List<Double>, active: BooleanArray, m: Int): DoubleArray? {
        val idx = rows.indices.filter { active[it] }
        if (idx.size < 3 * m) return null
        val a = Mat(idx.size, m, CvType.CV_64FC1)
        val b = Mat(idx.size, 1, CvType.CV_64FC1)
        val x = Mat()
        return releasing(a, b, x) {
            a.put(0, 0, *DoubleArray(idx.size * m) { rows[idx[it / m]][it % m] })
            b.put(0, 0, *DoubleArray(idx.size) { targets[idx[it]] })
            // SVD: robust when the lines leave a term poorly constrained (e.g. text only on part of the page).
            if (!Core.solve(a, b, x, Core.DECOMP_SVD)) return null
            DoubleArray(m) { x.get(it, 0)[0] }
        }
    }

    /** Samples (red) and fitted line shapes (green) on the work copy. */
    private fun drawFit(gray: Mat, lines: List<Line>, model: DewarpModel?, debug: DebugSink) {
        val img = Mat()
        releasing(img) {
            Imgproc.cvtColor(gray, img, Imgproc.COLOR_GRAY2BGR)
            val w = img.cols().toDouble()
            val h = img.rows().toDouble()
            for (line in lines) {
                if (model != null) {
                    // A straight line at meanY maps back to this curve in the input.
                    var prev: Point? = null
                    for (gx in 0..40) {
                        val x = gx / 40.0
                        val p = Point(x * w, (line.meanY + model.displacement(x, line.meanY)) * h)
                        prev?.let { Imgproc.line(img, it, p, Scalar(60.0, 200.0, 60.0), 1) }
                        prev = p
                    }
                }
                for (i in line.xs.indices) Imgproc.circle(img, Point(line.xs[i] * w, line.ys[i] * h), 2, Scalar(40.0, 40.0, 230.0), -1)
            }
            debug.stage("dewarp/fit", img)
        }
    }
}
