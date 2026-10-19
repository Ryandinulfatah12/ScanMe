plugins {
    alias(libs.plugins.scanme.jvm.library)
}

dependencies {
    // The OpenCV Java API is supplied at runtime by the Android AAR (org.opencv:opencv) in the app,
    // and by the desktop artifact (bundled natives for Windows/Linux/macOS) in JVM tests.
    compileOnly(libs.opencv.desktop)
    testImplementation(libs.opencv.desktop)
}

tasks.test {
    maxHeapSize = "2g"
    System.getProperty("scanme.realFrames")?.let { systemProperty("scanme.realFrames", it) }
    systemProperty("scanme.testOutput", layout.buildDirectory.dir("test-output").get().asFile.absolutePath)
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
