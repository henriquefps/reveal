plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.reveal.ml"
    // The public-domain portraits of the app's tests (faces/*.jpg) for the face landmark tests, not copied.
    sourceSets.getByName("androidTest").assets.directories.add("$rootDir/app/src/androidTest/assets")
}

dependencies {
    implementation(projects.core.image)
    // Faces (BlazeFace) and the saliency map for the subject's seeds and the face landmarks (ADR 0010 / 0021).
    implementation(projects.core.pipeline)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.litert)
    implementation(libs.litert.gpu)

    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

// The bundled models (docs/adr/0021): fetched (or derived from a pinned file) at build time by the pinned-checksum
// script, never committed and never downloaded at runtime. The script caches by checksum (~/.cache/reveal-ml), so only
// the first build downloads. Release builds carry the "bundled" set; debug builds add the mask lab's "lab" models.
abstract class FetchMlModels : Exec() {
    @get:Input
    abstract val variant: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    init {
        argumentProviders += CommandLineArgumentProvider {
            listOf("--variant", variant.get(), "--out", outputDir.get().asFile.path)
        }
    }
}

val mlScripts = listOf("fetch_models.py", "convert_deeplab_sky.py", "convert_fp16_weights.py", "tflite_flatbuffer.py")
    .map { rootProject.file("scripts/ml/$it") }

val fetchModels = listOf("release", "debug").associateWith { set ->
    tasks.register<FetchMlModels>("fetchMlModels${set.replaceFirstChar(Char::uppercase)}") {
        inputs.file(rootProject.file("core/ml/models.json"))
        inputs.files(mlScripts)
        variant.set(set)
        outputDir.set(layout.buildDirectory.dir("generated/ml-assets/$set"))
        commandLine("python3", mlScripts.first().path)
    }
}

androidComponents {
    onVariants { variant ->
        val set = if (variant.buildType == "debug") "debug" else "release"
        variant.sources.assets?.addGeneratedSourceDirectory(fetchModels.getValue(set), FetchMlModels::outputDir)
    }
}
