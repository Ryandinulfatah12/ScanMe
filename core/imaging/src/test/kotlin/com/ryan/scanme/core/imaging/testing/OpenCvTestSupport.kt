package com.ryan.scanme.core.imaging.testing

import org.opencv.core.Mat
import org.opencv.imgcodecs.Imgcodecs
import java.io.File

object OpenCvTestSupport {
    init {
        // Loads bytedeco's desktop natives for the official org.opencv Java API.
        org.bytedeco.javacpp.Loader.load(org.bytedeco.opencv.opencv_java::class.java)
    }

    /** Call from tests (e.g. in a companion init) to make sure natives are loaded. */
    fun ensureLoaded() = Unit

    val outputDir: File by lazy {
        File(System.getProperty("scanme.testOutput") ?: "build/test-output").apply { mkdirs() }
    }

    fun write(name: String, image: Mat): File {
        val file = File(outputDir, name)
        Imgcodecs.imwrite(file.absolutePath, image)
        return file
    }
}
