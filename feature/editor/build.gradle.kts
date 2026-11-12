plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.editor"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    // The on-device mask models (ADR 0021): the editor's AI masks (M4); the mask lab (src/debug) stays debug-only.
    implementation(projects.core.ml)
    implementation(projects.core.data)
    implementation(projects.feature.export)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
