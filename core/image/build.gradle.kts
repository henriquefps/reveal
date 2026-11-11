plugins {
    id("reveal.android.library")
}

android {
    namespace = "dev.hfps.image"
    // The image-core worker adds externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    // and the ndk abiFilters; NDK 28.2.13676358 and CMake 3.22.1 are installed (CLAUDE.md).
    ndkVersion = "28.2.13676358"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
