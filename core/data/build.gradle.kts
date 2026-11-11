plugins {
    id("reveal.android.library")
    id("reveal.hilt")
}

android {
    namespace = "dev.hfps.reveal.data"
    testOptions.unitTests.isIncludeAndroidResources = true
    sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
}

ksp {
    // Room exports the schema JSON so migrations can be tested against every released version.
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(projects.core.pipeline)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
}
