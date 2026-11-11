// Hilt through KSP (ADR 0001, "Dependency injection").
plugins {
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

dependencies {
    add("implementation", libs.lib("hilt-android"))
    add("ksp", libs.lib("hilt-compiler"))
}
