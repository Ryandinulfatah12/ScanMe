plugins {
    alias(libs.plugins.scanme.jvm.library)
}

// JavaCPP platform classifier of the machine running the JVM tests.
val desktopPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (arm) "macosx-arm64" else "macosx-x86_64"
        else -> if (arm) "linux-arm64" else "linux-x86_64"
    }
}

dependencies {
    // The OpenCV Java API is supplied at runtime by the Android AAR (org.opencv:opencv) in the app,
    // and by bytedeco's desktop build (official org.opencv Java API + natives) in JVM tests.
    compileOnly(libs.opencv.desktop)
    testImplementation(libs.opencv.desktop)
    testRuntimeOnly(variantOf(libs.opencv.desktop) { classifier(desktopPlatform) })
    testRuntimeOnly(variantOf(libs.openblas.desktop) { classifier(desktopPlatform) })
    testRuntimeOnly(variantOf(libs.javacpp) { classifier(desktopPlatform) })
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
