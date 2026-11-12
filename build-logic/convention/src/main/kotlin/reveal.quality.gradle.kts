import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension

// ktlint + detekt (with the Compose rules) and the per-module `qa` task (ADR 0001, "Quality gates").
plugins {
    id("io.gitlab.arturbosch.detekt")
    id("org.jlleitschuh.gradle.ktlint")
}

extensions.configure<DetektExtension> {
    buildUponDefaultConfig = true
    parallel = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin",
        "src/androidTest/kotlin",
        "src/bench/kotlin",
        "src/main/java",
    )
}

extensions.configure<KtlintExtension> {
    version.set(libs.version("ktlint"))
    android.set(true)
    ignoreFailures.set(false)
}

dependencies {
    add("detektPlugins", libs.lib("detekt-composeRules"))
    add("ktlintRuleset", libs.lib("ktlint-composeRules"))
}

val qa = tasks.register("qa") {
    group = "verification"
    description = "Unit tests, lint, ktlint and detekt for this module."
    dependsOn("ktlintCheck", "detekt")
}

pluginManager.withPlugin("com.android.application") { qa.configure { dependsOn("testDebugUnitTest", "lintDebug") } }
pluginManager.withPlugin("com.android.library") { qa.configure { dependsOn("testDebugUnitTest", "lintDebug") } }
pluginManager.withPlugin("org.jetbrains.kotlin.jvm") { qa.configure { dependsOn("test") } }
