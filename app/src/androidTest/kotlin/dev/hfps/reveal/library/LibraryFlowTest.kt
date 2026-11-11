package dev.hfps.reveal.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library end to end on a device: a SAF folder (the debug `SyntheticDocumentsProvider`, generated RAW+JPEG pairs,
 * single JPEGs and Hoard XMP sidecars) is added and indexed; the grid shows the pairs as one photo with their stars and
 * flags; the viewer opens, rates (written back to the sidecar) and shows the info sheet; Edit opens the editor on the
 * RAW. Texts come from resources and nodes from test tags, so it passes in any locale.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val folder = SyntheticFolder("flow-test")

    @After
    fun tearDown() = folder.delete()

    private fun text(id: Int) = context.getString(id)

    /** Synthetic content only: device screenshots for the PR (`files/pr-shots`, pulled by hand). */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SHOT_SETTLE_MS)
        val dir = File(context.filesDir, "pr-shots").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test
    fun addFolderBrowsePairsRateInTheViewerAndEdit() {
        folder.pair("DSC00001", 1)
        folder.pair("DSC00002", 2)
        folder.jpeg("IMG_0003.JPG", 3)
        folder.sidecar("DSC00001", rating = 4)
        File(folder.dir, "notes.txt").writeText("not a photo")
        val (sourceId, report) = folder.addAndIndex()
        assertThat(report.files).isEqualTo(5)
        assertThat(report.photosAdded).isEqualTo(3)
        val rows = runBlocking { folder.library.photoList(PhotoQuery(scope = LibraryScope.Source(sourceId))) }
        val pair = rows.single { it.name == "DSC00001.ARW" }
        assertThat(pair.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(pair.rating).isEqualTo(4)
        assertThat(pair.flag).isEqualTo(PickFlag.PICK)

        ActivityScenario.launch(MainActivity::class.java).use {
            // Phones list the sources first; tablets show both panes. Either way the folder row is there.
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.source(sourceId)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.source(sourceId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(pair.id)), TIMEOUT_MS)
            val stars = context.resources.getQuantityString(R.plurals.library_stars, 4, 4)
            compose.onNodeWithTag(LibraryTags.cell(pair.id))
                .assert(hasContentDescription(text(R.string.library_format_raw_pair), substring = true))
                .assert(hasContentDescription(stars, substring = true))
                .assert(hasContentDescription(text(R.string.library_flag_picked), substring = true))
            shot("grid")

            compose.onNodeWithTag(LibraryTags.cell(pair.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.PAGER), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.star(2)), TIMEOUT_MS)
            compose.onNodeWithTag(ViewerTags.star(2)).performClick()
            shot("viewer")
            val sidecar = File(folder.dir, "DSC00001.xmp")
            compose.waitUntil(TIMEOUT_MS) {
                runCatching { XmpReader.read(sidecar.readBytes()).rating == 2 }.getOrDefault(false)
            }
            assertThat(XmpReader.read(sidecar.readBytes()).flag).isEqualTo(PickFlag.PICK)
            assertThat(sidecar.readText()).contains("xmp:CreatorTool=\"Hoard 1.7.2\"")

            compose.onNodeWithTag(ViewerTags.INFO).performClick()
            compose.waitUntilAtLeastOneExists(hasText("SONY ZV-E10"), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasText("E 16-50mm F3.5-5.6 OSS"), TIMEOUT_MS)
            shot("info")
            // A real back key: the sheet's own window takes it first, as on a device. One back closes it, whatever
            // the window height (RevealBottomSheet has no half-height stop for back to collapse to).
            Espresso.pressBack()
            compose.waitUntilDoesNotExist(hasTestTag(ViewerTags.INFO_SHEET), TIMEOUT_MS)

            compose.onNodeWithTag(ViewerTags.EDIT).performClick()
            // The synthetic ARW only carries a preview: the editor opens on the RAW and reports it cannot develop it.
            // The top bar shortens the name in the middle on narrow phones (DSC0…01.ARW); the full name is its
            // content description.
            compose.waitUntilAtLeastOneExists(hasContentDescription("DSC00001.ARW"), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(
                hasTestTag(EditorTags.ERROR) or hasTestTag(EditorTags.DEVELOPED),
                TIMEOUT_MS,
            )
            val opened = runBlocking { folder.library.observePhoto(pair.id).first() }
            assertThat(opened?.lastOpenedAt).isNotNull()
        }
    }

    @Test
    fun aPublicSonyArwInTheFolderDevelopsInTheEditor() {
        val sample = File(context.filesDir, "samples/$ARW_SAMPLE")
        org.junit.Assume.assumeTrue("push $ARW_SAMPLE into files/samples (CLAUDE.md)", sample.isFile)
        sample.copyTo(File(folder.dir, "DSC00002.ARW"))
        folder.jpeg("IMG_0001.JPG", 1)
        val (sourceId, _) = folder.addAndIndex()
        val raw = runBlocking { folder.library.photoList(PhotoQuery(scope = LibraryScope.Source(sourceId))) }
            .single { it.name == "DSC00002.ARW" }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.source(sourceId)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.source(sourceId)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(raw.id)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(raw.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
            compose.onNodeWithTag(ViewerTags.EDIT).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), EDITOR_TIMEOUT_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val SHOT_SETTLE_MS = 1_000L
        const val EDITOR_TIMEOUT_MS = 60_000L
        const val ARW_SAMPLE = "sony-zv-e10.arw"
    }
}
