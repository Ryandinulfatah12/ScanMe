plugins {
    alias(libs.plugins.scanme.android.library)
    alias(libs.plugins.scanme.android.hilt)
}

android {
    namespace = "com.ryan.scanme.core.data"
}

dependencies {
    api(project(":core:domain"))
    implementation(libs.opencv.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
}
