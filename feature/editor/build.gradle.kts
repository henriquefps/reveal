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
    implementation(projects.core.ml)
    implementation(projects.core.data)

    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.truth)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
