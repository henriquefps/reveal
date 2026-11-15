// Macrobenchmark and Baseline Profile generation against the bench app (docs/adr/0001 "Baseline Profile"): a test
// module that installs beside `dev.hfps.reveal.bench` and drives it from outside (cold start, the 10,000-photo library,
// the editor open). `bench` measures the minified app; `benchProfile` collects the profile from the unminified twin.
// Never the release app (dev.hfps.reveal). Run with scripts/bench/macrobench.sh.
plugins {
    id("com.android.test")
    id("reveal.kotlin.options")
    id("reveal.quality")
}

android {
    namespace = "dev.hfps.reveal.benchmark"
    compileSdk { version = release(37) }

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        // One per app build type it targets; debuggable is fine for the test APK itself.
        create("bench") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
        create("benchProfile") {
            isDebuggable = true
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("bench", "release")
        }
    }

    targetProjectPath = ":app"
    experimentalProperties["android.experimental.self-instrumenting"] = true

    lint {
        warningsAsErrors = true
        abortOnError = true
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

// Only the benchmark variants (the debug variant would target a debuggable app, which Macrobenchmark refuses).
androidComponents {
    beforeVariants(selector().all()) { it.enable = it.buildType != "debug" }
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
