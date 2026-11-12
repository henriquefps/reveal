// JVM unit-test stack for every module: JUnit 4 + Truth (+ coroutines-test). ADR 0001, "Test stack".
plugins {
    id("reveal.kotlin.options")
}

dependencies {
    add("testImplementation", libs.lib("junit"))
    add("testImplementation", libs.lib("truth"))
    add("testImplementation", libs.lib("kotlinx-coroutines-test"))
}

// A failing test shows its message and stack trace in the build log (CI keeps no test reports).
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
