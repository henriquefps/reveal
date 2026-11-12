plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.reveal.ml"
}

dependencies {
    implementation(projects.core.image)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

// The bundled models (docs/adr/0021): fetched at build time by the pinned-checksum script, never committed and never
// downloaded at runtime. The script caches by checksum (~/.cache/reveal-ml), so only the first build downloads.
abstract class FetchMlModels : Exec() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        argumentProviders += CommandLineArgumentProvider { listOf("--out", outputDir.get().asFile.path) }
    }
}

val fetchModels = tasks.register<FetchMlModels>("fetchMlModels") {
    val script = rootProject.file("scripts/ml/fetch_models.py")
    inputs.file(rootProject.file("core/ml/models.json"))
    inputs.file(script)
    outputDir.set(layout.buildDirectory.dir("generated/ml-assets"))
    commandLine("python3", script.path)
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(fetchModels, FetchMlModels::outputDir)
    }
}
