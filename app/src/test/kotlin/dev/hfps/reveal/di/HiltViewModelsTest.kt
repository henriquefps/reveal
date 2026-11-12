package dev.hfps.reveal.di

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/**
 * Guard against Hilt's KSP processor silently skipping a ViewModel (M3/A: a trailing comment on an annotation line of
 * `EditorViewModel` left it without a factory; it compiled, and only failed when the screen opened). Every class
 * annotated `@HiltViewModel` in the project's sources must have its generated `_Factory` and `_HiltModules` on the
 * app's classpath.
 */
class HiltViewModelsTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun hiltViewModels(): List<String> = listOf("app", "core", "feature")
        .map { File(root, it) }
        .flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path }.toList()
        }
        .flatMap { file ->
            val text = file.readText()
            if (!text.contains("@HiltViewModel")) return@flatMap emptyList()
            val pkg = Regex("""^package\s+([\w.]+)""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
                ?: return@flatMap emptyList()
            Regex("""@HiltViewModel[\s\S]*?class\s+(\w+)""").findAll(text).map { "$pkg.${it.groupValues[1]}" }.toList()
        }
        .distinct()

    @Test
    fun everyHiltViewModelHasItsGeneratedFactory() {
        val viewModels = hiltViewModels()
        // The known ones at least (the scan itself must work).
        assertThat(viewModels).containsAtLeast(
            "dev.hfps.reveal.feature.editor.EditorViewModel",
            "dev.hfps.reveal.feature.library.LibraryViewModel",
        )
        val missing = viewModels.flatMap { name ->
            listOf("${name}_Factory", "${name}_HiltModules").filter { generated ->
                runCatching { Class.forName(generated, false, javaClass.classLoader) }.isFailure
            }
        }
        assertThat(missing).isEmpty()
    }
}
