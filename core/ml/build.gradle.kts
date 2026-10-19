plugins {
    alias(libs.plugins.scanme.android.library)
}

android {
    namespace = "com.ryan.scanme.core.ml"
}

// LiteRT (TensorFlow Lite) is added together with the first model (segmentation / dewarp, Phase 3 step 6),
// after its code, weights and training data pass the licence check (docs/02-architecture.md section 9).
dependencies {
    api(project(":core:imaging"))
}
