plugins {
    alias(libs.plugins.scanme.android.feature)
}

android {
    namespace = "com.ryan.scanme.feature.scanner"
}

dependencies {
    implementation(project(":core:camera"))
    // Interim: the scanner talks to the processing/export services directly until the editor and
    // document repository (Phase 3 step 5) take over the capture -> save flow.
    implementation(project(":core:data"))
    implementation(libs.opencv.android)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
}
