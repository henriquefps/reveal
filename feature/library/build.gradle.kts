plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.library"
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(projects.core.data)
    implementation(projects.feature.export)
}
