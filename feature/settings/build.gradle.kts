plugins {
    id("reveal.android.feature")
}

android {
    namespace = "dev.hfps.reveal.feature.settings"
}

dependencies {
    implementation(projects.core.data)
}
