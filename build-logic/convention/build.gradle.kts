plugins {
    `kotlin-dsl`
}

group = "dev.hfps.reveal.buildlogic"

dependencies {
    // Plugins applied by the precompiled convention scripts; their versions come from the catalog.
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
    implementation(libs.kotlin.composeGradlePlugin)
    implementation(libs.kotlin.serializationGradlePlugin)
    implementation(libs.ksp.gradlePlugin)
    implementation(libs.hilt.gradlePlugin)
    implementation(libs.detekt.gradlePlugin)
    implementation(libs.ktlint.gradlePlugin)
    implementation(libs.roborazzi.gradlePlugin)
}
