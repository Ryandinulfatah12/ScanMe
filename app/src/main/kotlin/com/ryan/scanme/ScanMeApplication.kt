package com.ryan.scanme

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.opencv.android.OpenCVLoader

@HiltAndroidApp
class ScanMeApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // :core:imaging is pure JVM and expects the OpenCV natives to be loaded by the host.
        check(OpenCVLoader.initLocal()) { "OpenCV native library failed to load" }
    }
}
