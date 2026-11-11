import com.android.build.api.dsl.LibraryExtension

// Android library: SDK levels, Java 17, lint with warnings as errors, the instrumented runner.
plugins {
    id("com.android.library")
    id("reveal.kotlin.options")
    id("reveal.test")
    id("reveal.quality")
}

extensions.configure<LibraryExtension> {
    compileSdk { version = release(Sdk.COMPILE) }
    defaultConfig {
        minSdk = Sdk.MIN
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
        targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        // Version bumps are a deliberate act (ADR 0001, "Versions policy"), not a lint failure.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}
