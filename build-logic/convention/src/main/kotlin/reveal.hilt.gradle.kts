// Hilt through KSP (ADR 0001, "Dependency injection").
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

dependencies {
    add("implementation", libs.lib("hilt-android"))
    add("ksp", libs.lib("hilt-compiler"))
}

// KSP's outputs are never taken from the build cache: a cached KSP result was restored without a module's
// `*_HiltModules_*` classes (seen 2026-11 with EditorViewModel in a debug build from a fresh clone on the shared
// cache), and the app then fails at runtime with NoSuchMethodException on the ViewModel. Running KSP costs seconds.
tasks.matching { it.name.startsWith("ksp") }.configureEach {
    outputs.doNotCacheIf("stale Hilt factories were restored from the build cache") { true }
}
