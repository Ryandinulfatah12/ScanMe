plugins {
    alias(libs.plugins.scanme.jvm.library)
}

dependencies {
    api(project(":core:imaging"))
    implementation(libs.kotlinx.coroutines.core)
}
