plugins {
    id("reveal.android.library")
    id("reveal.compose")
}

android {
    namespace = "dev.hfps.reveal.designsystem"
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
