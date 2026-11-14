plugins {
    id("reveal.android.library")
    id("reveal.compose")
    id("reveal.screenshot")
}

android {
    namespace = "dev.hfps.reveal.designsystem"
    testOptions.unitTests.isIncludeAndroidResources = true
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
