// Pure-JVM module (no Android dependencies). For code that must stay free of android.* so it runs on the
// plain JVM; ADR 0001 explains why the pipeline's CPU reference currently lives in an Android library.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("reveal.kotlin.options")
    id("reveal.test")
    id("reveal.quality")
}

java {
    sourceCompatibility = JavaVersion.toVersion(JAVA_VERSION)
    targetCompatibility = JavaVersion.toVersion(JAVA_VERSION)
}
