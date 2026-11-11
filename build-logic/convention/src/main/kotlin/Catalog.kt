import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.provider.Provider
import org.gradle.api.artifacts.MinimalExternalModuleDependency
import org.gradle.kotlin.dsl.getByType

/** The `libs` version catalog (`gradle/libs.versions.toml`) for precompiled convention scripts. */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.lib(alias: String): Provider<MinimalExternalModuleDependency> = findLibrary(alias).get()

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion

/** Shared SDK levels. minSdk 29 and latest stable compile/target (ADR 0001). */
internal object Sdk {
    const val MIN = 29
    const val COMPILE = 37
    const val TARGET = 37
}

internal const val JAVA_VERSION = 17
