plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.image"
    ndkVersion = "28.2.13676358"

    defaultConfig {
        externalNativeBuild {
            cmake {
                // Phones (arm64) and the emulator on x86 hosts; other ABIs ship without librevealimage and
                // fall back to embedded previews (RawDecoder.isAvailable is false there).
                abiFilters += setOf("arm64-v8a", "x86_64")
                arguments += "-DANDROID_STL=c++_static"
                cppFlags += "-std=c++17"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// Opt-in tests on real camera files (never committed): REVEAL_ARW_DIR=/path/to/copies.
tasks.withType<Test>().configureEach {
    environment("REVEAL_ARW_DIR", System.getenv("REVEAL_ARW_DIR") ?: "")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
