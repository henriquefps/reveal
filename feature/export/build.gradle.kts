plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.export"
}

dependencies {
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(projects.core.data)
}
