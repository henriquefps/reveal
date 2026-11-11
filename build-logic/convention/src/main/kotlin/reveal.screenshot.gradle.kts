// Roborazzi screenshot tests on the JVM (Robolectric, no emulator). ADR 0001, "Screenshot tests".
// Record: ./gradlew recordRoborazziDebug   Verify: ./gradlew verifyRoborazziDebug (part of `qa` via testDebugUnitTest).
plugins {
    id("io.github.takahirom.roborazzi")
}

dependencies {
    add("testImplementation", libs.lib("robolectric"))
    add("testImplementation", libs.lib("roborazzi"))
    add("testImplementation", libs.lib("roborazzi-compose"))
    add("testImplementation", libs.lib("roborazzi-junit-rule"))
    add("testImplementation", platform(libs.lib("androidx-compose-bom")))
    add("testImplementation", libs.lib("androidx-compose-ui-test-junit4"))
    add("debugImplementation", libs.lib("androidx-compose-ui-test-manifest"))
}
