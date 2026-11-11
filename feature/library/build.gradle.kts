plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.library"
}

dependencies {
    implementation(projects.core.image)
    implementation(projects.core.data)
}
