plugins {
    alias(libs.plugins.scanme.android.library)
}

android {
    namespace = "com.ryan.scanme.core.camera"
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.opencv.android)
    api(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.kotlinx.coroutines.android)
}
