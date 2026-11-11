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

// Opt-in Camera JPEG match calibration on COPIES of RAW+JPEG pairs (docs/adr/0008): never committed.
tasks.withType<Test>().configureEach {
    environment("REVEAL_CALIBRATION_DIR", System.getenv("REVEAL_CALIBRATION_DIR") ?: "")
    environment("REVEAL_RENDER_DUMP", System.getenv("REVEAL_RENDER_DUMP") ?: "")
    environment("REVEAL_RENDER_OUT", System.getenv("REVEAL_RENDER_OUT") ?: "")
    environment("REVEAL_AUTO_FIT", System.getenv("REVEAL_AUTO_FIT") ?: "")
    environment("REVEAL_AUTO_SHEET_DIR", System.getenv("REVEAL_AUTO_SHEET_DIR") ?: "")
    // Opt-in Detail 100 % crops on public CC0 RAWs (docs/adr/0012).
    for (name in listOf(
        "REVEAL_DETAIL_DUMP",
        "REVEAL_DETAIL_OUT",
        "REVEAL_DETAIL_CROP",
        "REVEAL_DETAIL_EV",
        "REVEAL_DETAIL_SWEEP",
    )) {
        environment(name, System.getenv(name) ?: "")
    }
    maxHeapSize = "2g"
}
