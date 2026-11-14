package dev.hfps.reveal.handoff

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.proxy.ProxySource
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.library.OriginalPlace
import dev.hfps.reveal.data.library.OriginalReach
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.open.PhotoOpener
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.work.ExportJobItem
import dev.hfps.reveal.feature.export.work.ExportJobStore
import dev.hfps.reveal.feature.export.work.ExportWorker
import dev.hfps.reveal.feature.export.work.ItemStatus
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.UnavailableTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.sync.account.PairingLink
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opening hand-offs whose grant is gone (ADR 0011 "Fallbacks"), on a device: a document of the debug provider is shared
 * to Reveal, then the provider refuses it (`SecurityException`), forgets it (`IllegalArgumentException`, rethrown
 * across Binder) or lets it be read once only. A photo with a preview opens it in the editor with the "preview only"
 * note; one with nothing in its place is "unavailable" (its cell explains and offers to share it again); a backed-up
 * one (`-e syncLink`, a local `reveal-sync` with backup storage, `scripts/sync/local-server.sh`) opens, shows and
 * exports the server's original at full size with no local preview at all. Locale-independent (test tags, resources
 * and data only).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffFallbackTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val args = InstrumentationRegistry.getArguments()
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val stem = "HF${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")
    private val ids = mutableListOf<String>()
    private var paired = false

    @After
    fun tearDown() {
        // The library opens on the unfiltered home again: other tests start there.
        runBlocking { access.library().setLastFilters(PhotoFilters.NONE) }
        ids.forEach { SyntheticDocumentsProvider.allow(context.filesDir, it) }
        inbox.deleteRecursively()
        if (paired) runBlocking { access.sync().unpair() }
    }

    /** A real JPEG in the provider (not an added folder): its document id and URI, as another app would share it. */
    private fun shared(name: String, bytes: ByteArray): Pair<String, Uri> {
        inbox.mkdirs()
        File(inbox, name).writeBytes(bytes)
        val id = "inbox-$stem/$name"
        ids += id
        return id to DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, id)
    }

    private fun jpeg(seed: Int) = SyntheticFolder("unused-$stem").let { f ->
        f.gradientJpeg(seed, WIDTH, HEIGHT).also { f.dir.deleteRecursively() }
    } + "$stem-$seed".toByteArray()

    private fun row(): PhotoRow = runBlocking {
        access.library().photoList(PhotoQuery.FROM_APPS).single { it.name.startsWith(stem) }
    }

    /** Shares [uri] to Reveal for real (`ACTION_SEND`, temporary grant): the editor opens and the previews are made. */
    private fun shareAndOpen(uri: Uri) {
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg").putExtra(Intent.EXTRA_STREAM, uri)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntil(TIMEOUT_MS) { runCatching { row() }.isSuccess }
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
        }
        runBlocking { access.handoffPreviewer().idle() }
    }

    /** Opens the photo's editor the way the owner does: Opened from other apps, the cell, the viewer's Edit. */
    private fun openFromLibrary(photo: PhotoRow, badge: String, check: () -> Unit) {
        // The library reopens its last filters: "Opened from other apps".
        runBlocking { access.library().setLastFilters(PhotoFilters.FROM_APPS) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photo.id)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)
            // The cell's badge says which fallback it is (its description is read with the cell).
            compose.onNodeWithTag(LibraryTags.cloudBadge(photo.id, badge), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
            compose.onNodeWithTag(ViewerTags.EDIT).performClick()
            compose.waitUntilAtLeastOneExists(
                hasTestTag(EditorTags.DEVELOPED) or hasTestTag(EditorTags.ERROR),
                TIMEOUT_MS,
            )
            check()
        }
    }

    @Test
    fun aHandOffTheProviderForgotOpensItsPreviewWithTheNote() {
        val bytes = jpeg(1)
        val (id, uri) = shared("${stem}_1.jpg", bytes)
        shareAndOpen(uri)
        assertThat(access.handoffPreviewer().previews.smart(FileFingerprint.of(bytes))).isNotNull()

        // The sender no longer serves the URI: an IllegalArgumentException from its provider.
        SyntheticDocumentsProvider.forget(context.filesDir, id)
        assertThat(runBlocking { access.library().checkHandoffs() }).isAtLeast(1)
        val photo = row()
        assertThat(photo.unreadable).isTrue()
        assertThat(runBlocking { access.library().originalPlace(photo.id) }).isEqualTo(OriginalPlace.HANDOFF_PREVIEW)

        openFromLibrary(photo, "LOCAL_PREVIEW") {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PREVIEW_ONLY), TIMEOUT_MS)
            assertThat(compose.onAllNodes(hasTestTag(EditorTags.ERROR)).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun aHandOffReadOnlyOnceIsUnavailableAndTheGridExplainsItInsteadOfOpeningTheEditor() {
        val (id, uri) = shared("${stem}_2.jpg", jpeg(2))
        // The grant lasts for one read: the hand-off's fingerprint. No preview can be made after it.
        SyntheticDocumentsProvider.refuseAfterOpen(context.filesDir, id)
        runBlocking {
            access.handoffs().receive(HandoffRequest(listOf(uri)))
            access.handoffPreviewer().idle()
        }
        runBlocking { access.library().checkHandoffs() }
        val photo = row()
        assertThat(runBlocking { access.library().originalPlace(photo.id) })
            .isEqualTo(OriginalPlace.HANDOFF_UNREADABLE)
        // The data layer still says "nothing to read it from" (the editor's error, goldens `phone_error_handoff*`)...
        val target = runBlocking { access.library().editTarget(photo.versionId) }!!
        assertThat(target.unreadable).isEqualTo(uri)

        // ...but since 0.5.2 (ADR 0011 "Unavailable photos") such a photo is "unavailable": its cell is the
        // placeholder and a tap opens the sheet that says why and how to share it again, never the viewer or editor.
        runBlocking { access.library().setLastFilters(PhotoFilters.FROM_APPS) }
        assertThat(row().unavailable).isTrue()
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photo.id)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)
            compose.onNodeWithTag(UnavailableTags.cell(photo.id), useUnmergedTree = true).assertExists()
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(UnavailableTags.SHEET), TIMEOUT_MS)
            // The debug provider is Reveal's own: no app to name, not a MediaStore URI.
            assertThat(compose.onAllNodes(hasTestTag(UnavailableTags.OPEN_APP)).fetchSemanticsNodes()).isEmpty()
            assertThat(compose.onAllNodes(hasTestTag(UnavailableTags.GALLERY)).fetchSemanticsNodes()).isEmpty()
            compose.onNodeWithTag(UnavailableTags.TRY_AGAIN).assertExists()
            assertThat(compose.onAllNodes(hasTestTag(ViewerTags.EDIT)).fetchSemanticsNodes()).isEmpty()
            assertThat(compose.onAllNodes(hasTestTag(EditorTags.ERROR)).fetchSemanticsNodes()).isEmpty()
        }
    }

    @Test
    fun aBackedUpHandOffWhoseServerCannotBeReachedOpensAndExportsItsPreview() {
        val bytes = jpeg(4)
        val (id, uri) = shared("${stem}_4.jpg", bytes)
        shareAndOpen(uri)
        val photo = row()
        // The library says the server has the original, but no server answers (not paired here): the provider
        // cannot serve it, as when the server is offline.
        runBlocking {
            access.database().syncDao().putCloud(PhotoCloudEntity(photo.id, originalOnServer = true))
        }
        SyntheticDocumentsProvider.refuse(context.filesDir, id)
        runBlocking { access.library().checkHandoffs() }
        val target = runBlocking { access.library().editTarget(photo.versionId) }!!
        assertThat(target.uri.authority).isEqualTo(ServerTree.authority(context.packageName))
        assertThat(target.fallback?.scheme).isEqualTo("file")

        openFromLibrary(row(), "CLOUD") {
            // The editor tried the server, then opened the smart preview and says so.
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PREVIEW_ONLY), TIMEOUT_MS)
        }

        // The export job does the same: the preview, at its size, flagged.
        val jobId = ExportWorker.enqueue(
            context,
            listOf(ExportJobItem(versionId = photo.versionId)),
            ExportOptions(),
            1,
        )
        val work = WorkManager.getInstance(context)
        val end = System.currentTimeMillis() + TIMEOUT_MS * 2
        while (work.getWorkInfosByTag(jobId).get().firstOrNull()?.state?.isFinished != true) {
            check(System.currentTimeMillis() < end) { "export timed out" }
            Thread.sleep(POLL_MS)
        }
        val store = ExportJobStore(context)
        val state = store.states(jobId).single()
        assertThat(state.status).isEqualTo(ItemStatus.DONE)
        assertThat(state.previewSize).isTrue()
        state.resultUri?.let { context.contentResolver.delete(Uri.parse(it), null, null) }
        store.delete(jobId)
    }

    @Suppress("LongMethod")
    @Test
    fun aBackedUpHandOffOpensShowsAndExportsTheServersOriginal() {
        val link = args.getString("syncLink")
        assumeTrue("needs -e syncLink (scripts/sync/local-server.sh pair)", link != null)
        runBlocking { access.sync().unpair() }
        runBlocking { access.sync().pair(checkNotNull(PairingLink.parse(checkNotNull(link)))) }
        paired = true
        val bytes = jpeg(3)
        val print = FileFingerprint.of(bytes)
        val (id, uri) = shared("${stem}_3.jpg", bytes)
        shareAndOpen(uri)

        // Back the original up (metadata sync looks it up, the blob run uploads it, the next sync records it).
        val photoId = row().id
        val deadline = System.currentTimeMillis() + BACKUP_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            runBlocking {
                access.sync().syncMetadata()
                access.sync().syncBlobs(originalsAllowed = true)
                access.sync().syncMetadata()
            }
            if (runBlocking { access.database().syncDao().cloud(photoId) }?.originalOnServer == true) break
            Thread.sleep(POLL_MS)
        }
        assertThat(runBlocking { access.database().syncDao().cloud(photoId) }?.originalOnServer).isTrue()

        // No local preview at all: only the server can show it. Then the grant ends.
        access.handoffPreviewer().previews.delete(listOf(print))
        SyntheticDocumentsProvider.refuse(context.filesDir, id)
        assertThat(runBlocking { access.library().checkHandoffs() }).isAtLeast(1)
        val photo = row()
        assertThat(photo.unreadable).isTrue()
        assertThat(photo.cloudOriginal).isTrue()
        assertThat(runBlocking { access.library().originalPlace(photo.id) }).isEqualTo(OriginalPlace.HANDOFF_SERVER)

        // Grid and viewer: from the server.
        val thumbnails = access.thumbnails()
        runBlocking { thumbnails.clear() }
        assertThat(runBlocking { thumbnails.thumbnail(photo.id, photo.versionId, null) }).isNotNull()
        assertThat(runBlocking { access.photoImages().display(photo.id, 1200) }).isNotNull()

        // Editor: the server's original, full size (not a smart preview).
        val target = runBlocking { access.library().editTarget(photo.versionId) }!!
        assertThat(target.uri.authority).isEqualTo(ServerTree.authority(context.packageName))
        val opened = PhotoOpener(context.contentResolver).open(target.uri, 2560, 1200, CancelToken.NONE) {}
        assertThat(opened.stats.source).isNotEqualTo(ProxySource.SMART_PREVIEW)
        assertThat(opened.fullWidth).isEqualTo(WIDTH)
        openFromLibrary(photo, "CLOUD") {
            compose.onNodeWithTag(EditorTags.DEVELOPED).assertExists()
            assertThat(compose.onAllNodes(hasTestTag(EditorTags.PREVIEW_ONLY)).fetchSemanticsNodes()).isEmpty()
        }

        // Export: the server's original, full size.
        val export = runBlocking { access.library().exportTarget(photo.versionId) }!!
        assertThat(export.reach).isEqualTo(OriginalReach.SERVER)
        val renderer = TiledRenderer.create()
        try {
            val result = ExportEngine(context).export(
                ExportItem(export.uri, export.name, Recipe(light = Light(0.4f))),
                ExportOptions(destination = ExportDestination.Share),
                Authorship(),
                renderer,
                cancel = { false },
            )
            assertThat(result.width).isEqualTo(WIDTH)
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val BACKUP_TIMEOUT_MS = 120_000L
        const val POLL_MS = 1_000L
        const val WIDTH = 640
        const val HEIGHT = 427
    }
}
