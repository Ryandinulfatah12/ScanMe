package com.ryan.scanme.core.imaging.warp

import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import com.ryan.scanme.core.imaging.testing.Pinhole
import com.ryan.scanme.core.imaging.testing.SceneKind
import com.ryan.scanme.core.imaging.testing.SyntheticScenes
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PerspectiveCorrectorTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    private val corrector = PerspectiveCorrector(snapTolerance = 0.0)
    private val camera = Pinhole(width = 3000, height = 4000, focalPx = 1.1 * 4000)
    private val truth = 210.0 / 297.0

    private fun error(est: Double) = abs(est - truth) / truth

    private val generalPoses = listOf(
        doubleArrayOf(0.0, 0.0, 0.0),
        doubleArrayOf(0.45, -0.3, 0.2),
        doubleArrayOf(-0.5, 0.25, -0.15),
        doubleArrayOf(0.3, 0.3, 0.6),
    )

    /** One pair of edges stays parallel in the image: focal length cannot be solved from the quad. */
    private val degeneratePoses = listOf(
        doubleArrayOf(0.35, 0.0, 0.0),
        doubleArrayOf(0.0, 0.4, 0.0),
        doubleArrayOf(0.6, 0.0, 0.1),
    )

    private fun check(poses: List<DoubleArray>, focal: Double?, maxError: Double, label: String, cam: Pinhole = camera) {
        for (p in poses) {
            val q = cam.projectA4(p[0], p[1], p[2], distanceMm = 420.0)
            val est = corrector.estimateAspect(q, cam.width, cam.height, focal)
            println("%-22s pose=(%.2f,%.2f,%.2f) w/h=%.4f err=%.2f%%".format(label, p[0], p[1], p[2], est, error(est) * 100))
            assertTrue(error(est) < maxError, "$label: error ${error(est) * 100}% for pose ${p.toList()}")
        }
    }

    @Test
    fun `exact with focal length from camera metadata`() =
        check(generalPoses + degeneratePoses, camera.focalPx, 0.005, "metadata focal")

    @Test
    fun `solves focal length from the quad for general poses`() =
        check(generalPoses, null, 0.02, "self-calibrated")

    /**
     * Gallery imports without metadata on a degenerate pose: the 0.8 x long-side prior is used. On a typical
     * phone main camera (~26 mm equivalent, f ~ 0.75 x long side) that stays within 3%. On unusual lenses
     * (telephoto) the error grows, which is why the app passes metadata focal length whenever it has it.
     */
    @Test
    fun `degenerate poses fall back to the default focal prior`() =
        check(degeneratePoses, null, 0.03, "default prior", cam = Pinhole(3000, 4000, focalPx = 0.75 * 4000))

    @Test
    fun `naive side ratio is badly biased for a tilted shot`() {
        val q = camera.projectA4(0.6, 0.0, 0.0, distanceMm = 380.0)
        val naive = maxOf(q.tl.distanceTo(q.tr), q.bl.distanceTo(q.br)) / maxOf(q.tl.distanceTo(q.bl), q.tr.distanceTo(q.br))
        println("naive=%.4f (err %.1f%%)".format(naive, error(naive) * 100))
        assertTrue(error(naive) > 0.05, "test pose not tilted enough to be meaningful")
        assertTrue(error(corrector.estimateAspect(q, camera.width, camera.height, camera.focalPx)) < 0.005)
    }

    @Test
    fun `snaps near standard paper sizes in both orientations`() {
        val snapping = PerspectiveCorrector(snapTolerance = 0.03)
        assertEquals(1 / sqrt(2.0), snapping.snap(0.70), 1e-9)
        assertEquals(sqrt(2.0), snapping.snap(1.43), 1e-9)
        assertEquals(8.5 / 11.0, snapping.snap(0.78), 1e-9)
        assertEquals(1.0, snapping.snap(1.0), 1e-9) // square stays square
    }

    @Test
    fun `rectified synthetic photo has A4 proportions`() {
        for (seed in 1..3) {
            val scene = SyntheticScenes.build(SceneKind.FLAT, seed)
            val page = PerspectiveCorrector().correct(scene.image, scene.truth.toNormalized(scene.image.cols(), scene.image.rows()), scene.focalPx)
            assertEquals(1 / sqrt(2.0), page.cols().toDouble() / page.rows(), 0.005)
            page.release()
            scene.release()
        }
    }
}
