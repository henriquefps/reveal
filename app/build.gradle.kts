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
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(projects.core.designsystem)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(projects.core.ml)
    implementation(projects.core.data)
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
}
