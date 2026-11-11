plugins {
    id("reveal.quality")
}

// Root `qa`: unit tests (incl. screenshot verification), lint, ktlint and detekt in every module.
tasks.named("qa") {
    dependsOn(subprojects.filter { it.childProjects.isEmpty() }.map { "${it.path}:qa" })
}
