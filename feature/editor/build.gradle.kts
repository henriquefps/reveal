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
}
