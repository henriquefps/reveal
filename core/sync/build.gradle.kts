plugins {
    id("reveal.android.library")
    id("reveal.hilt")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.hfps.reveal.sync"
    testOptions.unitTests.isIncludeAndroidResources = true
    // The exported Room schemas (core:data) are the upgrade test's fixtures: a version 13 (0.8.0) database.
    sourceSets.getByName("test").assets.directories.add("${project(":core:data").projectDir}/schemas")
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.image)
    implementation(projects.core.pipeline)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.androidx.work.testing)

    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
