plugins {
    id("reveal.android.library")
    id("reveal.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.hfps.reveal.data"
    testOptions.unitTests.isIncludeAndroidResources = true
    // The exported schemas are the migration tests' fixtures, on the JVM (Robolectric) and on a device.
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
    sourceSets.getByName("test").assets.directories.add("$projectDir/schemas")
}

ksp {
    // Room exports the schema JSON so migrations can be tested against every released version.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    // Import a preset or pack from a link (docs/adr/0019 "Import from a link"); the same client as :core:sync.
    implementation(libs.okhttp)

    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.room.testing)
    androidTestImplementation(libs.truth)
}
