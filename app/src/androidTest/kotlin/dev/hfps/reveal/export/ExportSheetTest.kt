package dev.hfps.reveal.export

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.export.ui.ExportTags
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The export sheet from the editor's top bar: the estimate appears, Export runs the job, the result is shown. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ExportSheetTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    @Test
    fun exportFromTheEditorSheetToTheGallery() {
        val name = "reveal-sheet-${System.nanoTime()}"
        val uri = TestPhotos.insert("$name.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            TestPhotos.launchView(uri, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                compose.onNodeWithTag(EditorTags.EXPORT).performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag(ExportTags.SHEET), TIMEOUT_MS)
                compose.onNodeWithTag(ExportTags.DEST_PICTURES).performScrollTo().performClick()
                // The live estimate replaces the "estimating" text with a size (digits whatever the locale).
                compose.waitUntil(TIMEOUT_MS) { runCatching { estimateHasDigits() }.getOrDefault(false) }
                compose.onNodeWithTag(ExportTags.START).performScrollTo().performClick()
                compose.waitUntilAtLeastOneExists(hasTestTag(ExportTags.DONE), TIMEOUT_MS)
                compose.onNodeWithTag(ExportTags.DONE).performClick()
            }
            assertThat(galleryCount("$name-edit")).isEqualTo(1)
        } finally {
            TestPhotos.delete(uri)
            deleteGallery("$name-edit")
        }
    }

    private fun estimateHasDigits(): Boolean = compose.onNodeWithTag(ExportTags.ESTIMATE).fetchSemanticsNode()
        .config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.Text) { null }
        ?.joinToString { it.text }?.any { it.isDigit() } == true

    private fun galleryCount(prefix: String): Int = query(prefix).size

    private fun deleteGallery(prefix: String) = query(prefix).forEach { TestPhotos.delete(it) }

    private fun query(prefix: String): List<Uri> {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val out = mutableListOf<Uri>()
        TestPhotos.context.contentResolver.query(
            collection,
            arrayOf(MediaStore.Images.Media._ID),
            "${MediaStore.Images.Media.DISPLAY_NAME} LIKE ?",
            arrayOf("$prefix%"),
            null,
        )?.use { c -> while (c.moveToNext()) out += Uri.withAppendedPath(collection, c.getLong(0).toString()) }
        return out
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
