package dev.hfps.reveal.handoff

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.handoff.HandoffCulling
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.library.OriginalReach
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-offs Reveal can no longer read, and one photo across sources (ADR 0011), on a device: a document of the debug
 * provider is shared to Reveal (a real `ACTION_SEND`), its local previews are made while it is readable, then the
 * provider refuses it (as the Samsung Gallery does once the share's grant ended): the library, viewer, editor and
 * export
 * fall back to the previews. And the same file in an added folder makes the two one photo, picks and edits included.
 * Locale-independent (test tags and data only).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UnreadableHandoffTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "UH${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")
    private val refused = mutableListOf<String>()
    private var folder: SyntheticFolder? = null

    @After
    fun tearDown() {
        refused.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it) }
        inbox.deleteRecursively()
        folder?.dir?.deleteRecursively()
    }

    /** A real JPEG in the provider (not an added folder): its document URI, as another app would share it. */
    private fun shared(name: String, bytes: ByteArray): Pair<String, Uri> {
        inbox.mkdirs()
        File(inbox, name).writeBytes(bytes)
        val id = "inbox-$stem/$name"
        return id to DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
    }

    private fun jpeg(seed: Int) = SyntheticFolder("unused-$stem").let { f ->
        f.gradientJpeg(seed, 640, 427).also { f.dir.deleteRecursively() }
    } + "$stem-$seed".toByteArray()

    private fun fromApps() = runBlocking {
        access.library().photoList(PhotoQuery.FROM_APPS).filter { it.name.startsWith(stem) }
    }

    @Test
    fun aSharedPhotoWhoseGrantEndedShowsEditsAndExportsFromItsPreview() {
        val bytes = jpeg(1)
        val (id, uri) = shared("${stem}_1.jpg", bytes)
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(TIMEOUT_MS) { fromApps().size == 1 }
            compose.waitUntil(TIMEOUT_MS) {
                compose.onAllNodes(hasTestTag(EditorTags.PREVIEW) or hasTestTag(EditorTags.DEVELOPED))
                    .fetchSemanticsNodes().isNotEmpty()
            }
        }
        runBlocking { access.handoffPreviewer().idle() }
        val previews = access.handoffPreviewer().previews
        val print = FileFingerprint.of(bytes)
        assertThat(previews.display(print)).isNotNull()
        assertThat(previews.smart(print)).isNotNull()

        // The grant ends.
        SyntheticDocumentsProvider.refuse(context.filesDir, id)
        refused += id
        assertThat(runBlocking { access.library().checkHandoffs() }).isAtLeast(1)
        val row = fromApps().single()
        assertThat(row.unreadable).isTrue()

        // Library and viewer: the local display image.
        val thumbnails = access.thumbnails()
        runBlocking { thumbnails.clear() }
        assertThat(runBlocking { thumbnails.thumbnail(row.id, row.versionId, null) }).isNotNull()
        assertThat(runBlocking { access.photoImages().display(row.id, 1200) }).isNotNull()

        // Editor: the smart preview opens (no full resolution behind it).
        val target = runBlocking { access.library().editTarget(row.versionId) }!!
        assertThat(target.uri.scheme).isEqualTo("file")
        val opened = PhotoOpener(context.contentResolver).open(target.uri, 2560, 1200, CancelToken.NONE) {}
        assertThat(opened.input.width).isEqualTo(640)

        // Export: at preview size, from the preview.
        val export = runBlocking { access.library().exportTarget(row.versionId) }!!
        assertThat(export.reach).isEqualTo(OriginalReach.PREVIEW)
        val renderer = TiledRenderer.create()
        try {
            val result = ExportEngine(context).export(
                ExportItem(checkNotNull(export.previewUri), export.name, Recipe(light = Light(0.4f))),
                ExportOptions(destination = ExportDestination.Share),
                Authorship(),
                renderer,
                cancel = { false },
            )
            assertThat(result.width).isEqualTo(640)
        } finally {
            renderer.close()
        }

        // Shared again (the gallery grants it again): readable, full again, the previews kept.
        SyntheticDocumentsProvider.allow(context.filesDir, id)
        val again = runBlocking { access.handoffs().receive(HandoffRequest(listOf(uri))) }
        assertThat(again).isInstanceOf(HandoffOutcome.OpenEditor::class.java)
        assertThat(fromApps().single().unreadable).isFalse()
        assertThat(previews.smart(print)).isNotNull()
    }

    @Test
    fun aHandOffAndTheSameFileInAFolderAreOnePhotoWithThePicksInTheFoldersSidecar() {
        val card = SyntheticFolder("card-$stem").also { folder = it }
        val bytes = jpeg(2)
        val (_, uri) = shared("${stem}_2.JPG", bytes)
        val outcome = runBlocking {
            access.handoffs().receive(
                HandoffRequest(
                    listOf(uri),
                    title = "Hoard",
                    culling = mapOf(uri to HandoffCulling(flag = PickFlag.PICK)),
                ),
            )
        }
        val version = (outcome as HandoffOutcome.OpenEditor).versionId
        runBlocking {
            val to = Recipe(light = Light(0.5f))
            access.edits().commit(version, to, RecipeDiff.between(Recipe(), to))
        }
        File(card.dir, "${stem}_2.JPG").writeBytes(bytes)

        val (sourceId, _) = card.addAndIndex()
        // The fingerprint pass runs after the walk, then the merge.
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { access.library().photoList(PhotoQuery.ofSource(sourceId)) }
                .singleOrNull()?.flag == PickFlag.PICK
        }
        val photo = runBlocking { access.library().photoList(PhotoQuery.ofSource(sourceId)) }
            .single()
        assertThat(photo.hasEdit).isTrue()
        assertThat(runBlocking { access.edits().recipe(photo.versionId) }.light.exposureEv).isEqualTo(0.5f)
        assertThat(fromApps().map { it.id }).containsExactly(photo.id)
        // The sidecar is created, then written: wait for its content, not its name.
        val sidecar = File(card.dir, "${stem}_2.xmp")
        compose.waitUntil(TIMEOUT_MS) { runCatching { XmpReader.read(sidecar.readBytes()).flag }.getOrNull() != null }
        assertThat(XmpReader.read(sidecar.readBytes()).flag).isEqualTo(PickFlag.PICK)
        runBlocking { access.library().removeSource(sourceId) }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
    }
}
