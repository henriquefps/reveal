// A feature module: Android library + Compose + Hilt + type-safe navigation routes.
plugins {
    id("reveal.android.library")
    id("reveal.compose")
    id("reveal.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

dependencies {
    add("implementation", libs.lib("androidx-navigation-compose"))
    add("implementation", libs.lib("androidx-hilt-navigation-compose"))
    add("implementation", libs.lib("androidx-lifecycle-runtime-compose"))
    add("implementation", libs.lib("kotlinx-serialization-json"))
    add("implementation", libs.lib("kotlinx-coroutines-android"))
}
