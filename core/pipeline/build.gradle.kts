plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.reveal.pipeline"
}

dependencies {
    implementation(projects.core.image)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
