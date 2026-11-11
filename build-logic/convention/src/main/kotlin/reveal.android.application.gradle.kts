import com.android.build.api.dsl.ApplicationExtension

// Android application: SDK levels, Java 17, lint, version from gradle.properties (ADR 0001, "versionCode").
plugins {
    id("com.android.application")
    id("reveal.kotlin.options")
    id("reveal.test")
    id("reveal.quality")
}

val revealVersionName = providers.gradleProperty("reveal.versionName").get()

/** versionCode = major * 1_000_000 + minor * 10_000 + patch * 100 + build (0..99, a re-upload of the same version). */
fun versionCodeOf(versionName: String, build: Int = 0): Int {
    val (major, minor, patch) = versionName.substringBefore('-').split('.').map { it.toInt() }
    require(minor in 0..99 && patch in 0..99 && build in 0..99) { "Version parts must be 0..99: $versionName" }
    return major * 1_000_000 + minor * 10_000 + patch * 100 + build
}

extensions.configure<ApplicationExtension> {
    compileSdk { version = release(Sdk.COMPILE) }
    defaultConfig {
        minSdk = Sdk.MIN
        targetSdk = Sdk.TARGET
        versionName = revealVersionName
        versionCode = versionCodeOf(revealVersionName)
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
        targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = false
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version")
    }
}
