import java.util.Properties

plugins {
    alias(libs.plugins.scanme.android.application)
    alias(libs.plugins.scanme.android.compose)
    alias(libs.plugins.scanme.android.hilt)
}

// Release signing from keystore.properties (git-ignored): storeFile, storePassword, keyAlias, keyPassword.
val keystoreProps = rootProject.file("keystore.properties").takeIf { it.exists() }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

android {
    namespace = "com.ryan.scanme"

    defaultConfig {
        applicationId = "com.ryan.scanme"
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystoreProps != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Phones only: drops the x86/x86_64 OpenCV natives (~2 x 30 MB) that only emulators need.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
            // Without keystore.properties the build is debug-signed: fine for sideloading, rejected by Play.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("keystore.properties not found: release build is signed with the DEBUG key (not for Play).")
            }
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
