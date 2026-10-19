plugins {
    alias(libs.plugins.scanme.android.library)
    alias(libs.plugins.scanme.android.compose)
}

android {
    namespace = "com.ryan.scanme.core.ui"
}

dependencies {
    // Full outline icon set (crop, rotate, flash, scanner...). Release builds shrink it with R8.
    api(libs.androidx.compose.material.icons)
}
