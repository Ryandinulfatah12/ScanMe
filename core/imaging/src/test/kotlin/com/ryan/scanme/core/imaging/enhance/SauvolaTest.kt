package com.ryan.scanme.core.imaging.enhance

import com.ryan.scanme.core.imaging.testing.OpenCvTestSupport
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgproc.Imgproc
import kotlin.test.Test
import kotlin.test.assertTrue

class SauvolaTest {
    init {
        OpenCvTestSupport.ensureLoaded()
    }

    @Test
    fun `separates text from a strong lighting gradient where a global threshold fails`() {
        val w = 800
        val h = 400
        val img = Mat(h, w, CvType.CV_8UC1)
        // Paper brightness falls from 240 (left) to 90 (right); text is 60 units darker than local paper.
        val row = ByteArray(w) { x -> (240 - 150.0 * x / w).toInt().toByte() }
        for (y in 0 until h) img.put(y, 0, row)
        val textMask = Mat.zeros(h, w, CvType.CV_8UC1)
        for (y in 60 until h step 60) {
            Imgproc.putText(textMask, "Sauvola local threshold test", Point(20.0, y.toDouble()), Imgproc.FONT_HERSHEY_SIMPLEX, 0.9, Scalar(255.0), 2)
        }
        val darker = Mat()
        Core.subtract(img, Scalar(60.0), darker)
        darker.copyTo(img, textMask)

        val binary = Sauvola().binarize(img)
        val inkFound = Mat()
        Core.bitwise_not(binary, inkFound)
        val hit = Mat()
        Core.bitwise_and(inkFound, textMask, hit)
        val recall = Core.countNonZero(hit).toDouble() / Core.countNonZero(textMask)
        val notText = Mat()
        Core.bitwise_not(textMask, notText)
        Core.bitwise_and(inkFound, notText, hit)
        val falseRate = Core.countNonZero(hit).toDouble() / Core.countNonZero(notText)

        val global = Mat()
        Imgproc.threshold(img, global, 0.0, 255.0, Imgproc.THRESH_BINARY + Imgproc.THRESH_OTSU)
        Core.bitwise_not(global, global)
        Core.bitwise_and(global, notText, hit)
        val globalFalse = Core.countNonZero(hit).toDouble() / Core.countNonZero(notText)

        println("sauvola recall=%.3f false=%.4f | otsu false=%.3f".format(recall, falseRate, globalFalse))
        assertTrue(recall > 0.9, "recall $recall")
        assertTrue(falseRate < 0.01, "false ink $falseRate")
        assertTrue(globalFalse > 0.2, "gradient too weak: global Otsu should fail here")
        listOf(img, textMask, darker, binary, inkFound, hit, notText, global).forEach { it.release() }
    }
}
