// Jetpack Compose: compiler plugin, BOM, tooling. Applied on top of an Android application/library plugin.
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}

dependencies {
    add("implementation", platform(libs.lib("androidx-compose-bom")))
    add("androidTestImplementation", platform(libs.lib("androidx-compose-bom")))
    add("implementation", libs.lib("androidx-compose-ui"))
    add("implementation", libs.lib("androidx-compose-foundation"))
    add("implementation", libs.lib("androidx-compose-material3"))
    add("implementation", libs.lib("androidx-compose-ui-tooling-preview"))
    add("debugImplementation", libs.lib("androidx-compose-ui-tooling"))
}
