package com.ryan.scanme.core.imaging.detect

import com.ryan.scanme.core.imaging.DebugSink
import com.ryan.scanme.core.imaging.geometry.Quad
import com.ryan.scanme.core.imaging.geometry.Vec2
import com.ryan.scanme.core.imaging.releasing
import com.ryan.scanme.core.imaging.resizeLongSide
import com.ryan.scanme.core.imaging.toGray
import org.opencv.core.Mat
import org.opencv.core.MatOfInt
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

/**
 * Parameter profiles for edge-based detection, mirroring the high/low-contrast split seen in the
 * reference app (docs/01-technique-analysis.md, section 4).
 *
 * The Canny high threshold is derived from the Otsu threshold of the blurred frame: Otsu sits between
 * paper and background brightness, so it scales with scene contrast instead of being a fixed number.
 */
enum class DetectorProfile(
    val source: QuadSource,
    /** 0 disables CLAHE. Low-contrast scenes (white page on light table) need local contrast boost. */
    val claheClip: Double,
    val cannyHighFactor: Double,
) {
    HIGH_CONTRAST(QuadSource.CLASSIC_HIGH_CONTRAST, claheClip = 0.0, cannyHighFactor = 1.0),
    LOW_CONTRAST(QuadSource.CLASSIC_LOW_CONTRAST, claheClip = 3.0, cannyHighFactor = 0.5),
}

/**
 * Classic (no ML) document outline detector. Produces several candidates; callers fuse them.
 *
 * Works on a downscaled gray copy (long side [maxSide]); the acceptance criterion is < 50 ms per
 * preview frame at <= 640 px.
 */
class ClassicQuadDetector(
    private val maxSide: Int = 640,
    /** Pages smaller than this fraction of the frame are ignored (text blocks, labels, etc.). */
    private val minAreaRatio: Double = 0.12,
    private val profiles: List<DetectorProfile> = DetectorProfile.entries,
    private val scorer: QuadScorer = QuadScorer(),
    private val refiner: QuadRefiner = QuadRefiner(),
    private val fusion: QuadFusion = QuadFusion(),
    private val cutOffPenalty: Double = 0.15,
) {
    private val lines = LineQuadDetector()

    fun detect(image: Mat, debug: DebugSink? = null): DetectionResult {
        val gray = toGray(image)
        val (small, _) = releasing(gray) { resizeLongSide(gray, maxSide) }
        return releasing(small) {
            val w = small.cols()
            val h = small.rows()
            val minArea = minAreaRatio * w * h

            val raw = mutableListOf<Pair<ContourQuad, QuadSource>>()
            for (profile in profiles) {
                edgeQuads(small, profile, minArea, debug).forEach { raw += it to profile.source }
            }
            regionQuads(small, minArea, debug).forEach { raw += it to QuadSource.CLASSIC_REGION }

            val edges = scorer.edgeMap(small)
            val candidates = releasing(edges) {
                debug?.stage("detect/score-edges", edges)
                val edgeData = scorer.context(small, edges)
                val edgePoints = refiner.edgePoints(edges)
                val fromContours = raw.map { (cq, src) ->
                    val refined = if (cq.cutOff) cq.quad else refiner.refine(cq.quad, edgePoints)
                    QuadCandidate(refined.toNormalized(w, h), src, scorer.score(refined, edgeData, ignoreFrameBorder = cq.cutOff), cq.cutOff)
                }
                // Line combinations can number in the hundreds: score all cheaply, refine only the best few.
                val fromLines = lines.detect(edges, edgeData, minArea)
                    .map { it to scorer.score(it.quad, edgeData, ignoreFrameBorder = it.cutOff) }
                    .sortedByDescending { it.second }
                    .take(3)
                    .map { (lq, s) ->
                        val refined = if (lq.cutOff) lq.quad else refiner.refine(lq.quad, edgePoints)
                        val score = if (refined === lq.quad) s else scorer.score(refined, edgeData)
                        QuadCandidate(refined.toNormalized(w, h), QuadSource.CLASSIC_LINES, score, lq.cutOff)
                    }
                (fromContours + fromLines).map {
                    if (it.cutOff || !runsAlongFrameEdge(it.quad)) it else it.copy(cutOff = true)
                }.map {
                    // A cut-off outline rests on three real sides at most: it only wins when no complete
                    // page is convincingly visible.
                    if (it.cutOff) it.copy(score = it.score - cutOffPenalty) else it
                }
            }
            val deduped = dedupe(candidates.sortedByDescending { it.score })
            DetectionResult(fusion.fuse(deduped), deduped)
        }
    }

    private fun edgeQuads(gray: Mat, profile: DetectorProfile, minArea: Double, debug: DebugSink?): List<ContourQuad> {
        val work = Mat()
        val tmp = Mat()
        val edges = Mat()
        return releasing(work, tmp, edges) {
            if (profile.claheClip > 0) {
                Imgproc.createCLAHE(profile.claheClip, Size(8.0, 8.0)).apply(gray, work)
            } else {
                gray.copyTo(work)
            }
            // 5x5 blur suppresses paper texture and JPEG noise at 640 px without moving the page border.
            Imgproc.GaussianBlur(work, work, Size(5.0, 5.0), 0.0)
            val otsu = Imgproc.threshold(work, tmp, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val high = (otsu * profile.cannyHighFactor).coerceIn(20.0, 250.0)
            Imgproc.Canny(work, edges, high * 0.5, high, 3, true)
            // Close 1-2 px gaps (shadows and fingers break the border) so the outline forms one contour.
            val kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(3.0, 3.0))
            releasing(kernel) { Imgproc.dilate(edges, edges, kernel, Point(-1.0, -1.0), 2) }
            debug?.stage("detect/edges-${profile.name.lowercase()}", edges)
            contourQuads(edges, Imgproc.RETR_LIST, minArea)
        }
    }

    /** Paper is usually the largest bright region: Otsu split, then fill text holes. */
    private fun regionQuads(gray: Mat, minArea: Double, debug: DebugSink?): List<ContourQuad> {
        val mask = Mat()
        return releasing(mask) {
            Imgproc.GaussianBlur(gray, mask, Size(5.0, 5.0), 0.0)
            Imgproc.threshold(mask, mask, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
            val close = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(9.0, 9.0))
            val open = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, Size(5.0, 5.0))
            releasing(close, open) {
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_CLOSE, close)
                Imgproc.morphologyEx(mask, mask, Imgproc.MORPH_OPEN, open)
            }
            debug?.stage("detect/region-mask", mask)
            contourQuads(mask, Imgproc.RETR_EXTERNAL, minArea)
        }
    }

    private class ContourQuad(val quad: Quad, val cutOff: Boolean)

    private fun contourQuads(binary: Mat, mode: Int, minArea: Double): List<ContourQuad> {
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        releasing(hierarchy) {
            Imgproc.findContours(binary, contours, hierarchy, mode, Imgproc.CHAIN_APPROX_SIMPLE)
        }
        return contours.mapNotNull { c -> releasing(c) { quadFromContour(c, minArea, binary.cols(), binary.rows()) } }
    }

    /**
     * Fits a quad to the convex hull of a contour. The hull makes partial outlines usable: three detected
     * sides plus the fourth side's endpoints still hull to the full page.
     *
     * A hull touching the frame border means the region continues past the frame (cut-off page). The 4-point
     * approximation must cover the hull within 8% of its area; otherwise the shape was not quad-like and
     * approxPolyDP has chopped a corner off with a diagonal.
     */
    private fun quadFromContour(contour: MatOfPoint, minArea: Double, w: Int, h: Int): ContourQuad? {
        val hullIdx = MatOfInt()
        val pts = contour.toArray()
        val hull = releasing(hullIdx) {
            Imgproc.convexHull(contour, hullIdx)
            hullIdx.toArray().map { pts[it] }
        }
        if (hull.size < 4) return null
        val touchesFrame = hull.count { it.x <= 2 || it.y <= 2 || it.x >= w - 3 || it.y >= h - 3 } >= 2
        val hull2f = MatOfPoint2f(*hull.toTypedArray())
        val approx = MatOfPoint2f()
        return releasing(hull2f, approx) {
            val hullArea = Imgproc.contourArea(hull2f)
            if (hullArea < minArea) return@releasing null
            val perimeter = Imgproc.arcLength(hull2f, true)
            // Increasing epsilon: 2% keeps sharp corners; up to 6% absorbs rounded/dog-eared corners.
            for (f in doubleArrayOf(0.02, 0.03, 0.04, 0.06)) {
                Imgproc.approxPolyDP(hull2f, approx, f * perimeter, true)
                if (approx.rows() == 4) {
                    val quad = Quad.fromUnordered(approx.toArray().map { Vec2(it.x, it.y) })
                    val coverage = quad.area() / hullArea
                    return@releasing if (quad.isConvex() && coverage > 0.92) ContourQuad(quad, touchesFrame) else null
                }
                if (approx.rows() < 4) break
            }
            null
        }
    }

    /**
     * True when a whole side lies on the frame edge (both ends within 1.5%): the page continues past the
     * frame and the contour was closed by the image border, not by the page border.
     */
    private fun runsAlongFrameEdge(quad: Quad): Boolean {
        val tol = 0.015
        return quad.sides().any { (a, b) ->
            (a.x < tol && b.x < tol) || (a.x > 1 - tol && b.x > 1 - tol) ||
                (a.y < tol && b.y < tol) || (a.y > 1 - tol && b.y > 1 - tol)
        }
    }

    private fun dedupe(sorted: List<QuadCandidate>): List<QuadCandidate> {
        val kept = mutableListOf<QuadCandidate>()
        for (c in sorted) {
            if (kept.none { it.source == c.source && it.quad.maxCornerDistance(c.quad) < 0.015 }) kept += c
        }
        return kept
    }
}
