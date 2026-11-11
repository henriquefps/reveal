plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.reveal.ml"
}

dependencies {
    implementation(projects.core.image)
    implementation(libs.kotlinx.coroutines.android)
}
