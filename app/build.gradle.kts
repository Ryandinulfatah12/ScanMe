plugins {
    alias(libs.plugins.scanme.android.application)
    alias(libs.plugins.scanme.android.compose)
    alias(libs.plugins.scanme.android.hilt)
}

android {
    namespace = "com.ryan.scanme"

    defaultConfig {
        applicationId = "com.ryan.scanme"
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones only: drops the x86/x86_64 OpenCV natives (~2 x 30 MB) that only emulators need.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // Local device testing only. Replace with a real release keystore before any distribution.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:data"))
    implementation(project(":core:ml"))
    implementation(project(":feature:scanner"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:documents"))
    debugImplementation(project(":feature:debug"))

    implementation(libs.opencv.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
}
