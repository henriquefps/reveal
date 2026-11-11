plugins {
    id("reveal.android.library")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.hfps.reveal.pipeline"
}

dependencies {
    implementation(projects.core.image)
    implementation(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
