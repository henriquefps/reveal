plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.export"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(projects.core.data)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.heifwriter)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
}
