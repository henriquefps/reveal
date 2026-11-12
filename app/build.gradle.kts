plugins {
    id("reveal.android.application")
    id("reveal.compose")
    id("reveal.hilt")
    id("reveal.screenshot")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.hfps.reveal"

    defaultConfig {
        applicationId = "dev.hfps.reveal"
    }

    // Release signing reads the keystore from the environment, never from the repo. The values live in the skv
    // vault (group "reveal"): `skv run reveal -- ./gradlew :app:assembleRelease` exports REVEAL_KEYSTORE (path of a
    // temporary copy of the .jks), REVEAL_KEYSTORE_PASSWORD, REVEAL_KEY_ALIAS and REVEAL_KEY_PASSWORD. Without them
    // the release APK is built unsigned (CI, contributors).
    val releaseKeystore: String? = System.getenv("REVEAL_KEYSTORE")
    val releaseSigning = !releaseKeystore.isNullOrEmpty() &&
        file(releaseKeystore).exists() &&
        !System.getenv("REVEAL_KEYSTORE_PASSWORD").isNullOrEmpty() &&
        !System.getenv("REVEAL_KEY_ALIAS").isNullOrEmpty()

    if (releaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = file(checkNotNull(releaseKeystore))
                storePassword = System.getenv("REVEAL_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("REVEAL_KEY_ALIAS")
                keyPassword = System.getenv("REVEAL_KEY_PASSWORD") ?: System.getenv("REVEAL_KEYSTORE_PASSWORD")
            }
        }
    } else if (gradle.startParameter.taskNames.any { it.contains("release", ignoreCase = true) }) {
        logger.warn(
            "Reveal: REVEAL_KEYSTORE / REVEAL_KEYSTORE_PASSWORD / REVEAL_KEY_ALIAS not set: the release APK will be " +
                "UNSIGNED. Use `skv run reveal -- ./gradlew :app:assembleRelease` for a signed build.",
        )
    }

    buildTypes {
        debug {
            // Debug and release coexist on one device.
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (releaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Release-build measurements on the devices (docs/adr/0019 and 0020 "Measurements"): minified and shrunk like
        // release, but its own app id (dev.hfps.reveal.bench: never the release app), signed with the debug key and
        // profileable from the shell. `src/bench` adds an adb-driven BenchActivity. Never distributed.
        create("bench") {
            initWith(getByName("release"))
            applicationIdSuffix = ".bench"
            versionNameSuffix = "-bench"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    // The on-device mask models (ADR 0021) only reach debug builds, through the mask lab, until M4 ships masks.
    debugImplementation(projects.core.ml)
    implementation(projects.core.data)
    implementation(projects.core.sync)
    implementation(projects.feature.library)
    implementation(projects.feature.editor)
    implementation(projects.feature.export)
    implementation(projects.feature.settings)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.serialization.json)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.espresso.core)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.androidx.work.runtime.ktx)
    androidTestImplementation(libs.androidx.room.runtime)
    androidTestImplementation(libs.okhttp)
    androidTestImplementation(libs.okhttp.mockwebserver)
    androidTestImplementation(libs.okhttp.tls)
}

/**
 * Guard for minified builds (M3/F): stale incremental KSP output once left the editor's ViewModel factories out of a
 * release-variant build (debug was fine, so `HiltViewModelsTest` stayed green) and the minified app crashed opening the
 * editor. After R8, every `@HiltViewModel` class in the sources must have its Hilt key module in the R8 mapping.
 */
abstract class VerifyHiltViewModels : DefaultTask() {
    @get:InputFiles
    abstract val sources: ConfigurableFileCollection

    @get:InputFile
    abstract val mapping: RegularFileProperty

    @TaskAction
    fun verify() {
        val classes = sources.files.filter { it.extension == "kt" }.flatMap { file ->
            val text = file.readText()
            if (!text.contains("@HiltViewModel")) return@flatMap emptyList()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            Regex("""@HiltViewModel\s+class\s+(\w+)""").findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList()
        }
        val map = mapping.get().asFile.readText()
        val missing = classes.filter { !map.contains("${it}_HiltModules_KeyModule_Provide_LazyMapKey") }
        check(classes.isNotEmpty()) { "no @HiltViewModel classes found" }
        check(missing.isEmpty()) {
            "Hilt ViewModel factories missing from the minified build: $missing. Stale KSP output: rebuild with " +
                "`--no-build-cache` after deleting the modules' build/generated/ksp (CLAUDE.md)."
        }
    }
}

for (variant in listOf("Bench", "Release")) {
    val verify = tasks.register<VerifyHiltViewModels>("verifyHiltViewModels$variant") {
        sources.from(fileTree(rootDir) { include("feature/*/src/main/kotlin/**/*.kt", "app/src/main/kotlin/**/*.kt") })
        mapping.set(layout.buildDirectory.file("outputs/mapping/${variant.lowercase()}/mapping.txt"))
        dependsOn("minify${variant}WithR8")
        dependsOn("merge${variant}ComposeMapping")
    }
    tasks.matching { it.name == "assemble$variant" }.configureEach { dependsOn(verify) }
}
