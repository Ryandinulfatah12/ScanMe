plugins {
    alias(libs.plugins.scanme.android.feature)
}

android {
    namespace = "com.ryan.scanme.feature.debug"
}

dependencies {
    implementation(project(":core:imaging"))
    implementation(libs.opencv.android)
    implementation(libs.androidx.activity.compose)
}
