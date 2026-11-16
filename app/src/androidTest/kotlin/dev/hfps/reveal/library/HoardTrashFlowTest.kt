package dev.hfps.reveal.library

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.UnavailableTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.sync.account.PairingLink
import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hoard's trash and lost Server photos in the library (ADR 0018 "R1") on a device: a Server photo whose NAS file is in
 * Hoard's trash is listed only under "In Hoard's trash", as a placeholder, and opens to a note without Edit; one whose
 * file the server lost is an unavailable cell whose tap explains it. The rows are written as the sync engine writes
 * them (no server needed). Nodes by test tag (any locale).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HoardTrashFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val access = EntryPointAccessors.fromApplication(TestPhotos.context, DebugAccess::class.java)
    private val db = access.database()
    private val stem = "HT${System.nanoTime() % 1_000_000_000}"
    private val made = ArrayList<Long>()

    /**
     * A server that answers just enough to pair and run an empty sync: the Sync filters (and so "In Hoard's trash")
     * show only while paired. Everything stays on the device's loopback.
     */
    private val server = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                val body = when {
                    path == "/v1/pair" -> PAIRED
                    path == "/v1/server" -> """{"serverId":"$SERVER","deviceId":"$DEVICE","api":1,"features":[]}"""
                    path == "/v1/health" -> """{"status":"ok","api":1}"""
                    path == "/v1/devices" -> """{"devices":[]}"""
                    path == "/v1/changes" && request.method == "GET" -> NO_CHANGES
                    path == "/v1/changes" -> """{"results":[]}"""
                    path == "/v1/nas/files" -> """{"files":[],"cursor":"n","hasMore":false}"""
                    path == "/v1/fingerprints/lookup" -> """{"found":[],"missing":[]}"""
                    else -> return MockResponse.Builder().code(404).body("""{"error":{"code":"not_found"}}""").build()
                }
                return MockResponse.Builder().code(if (path == "/v1/pair") 201 else 200)
                    .addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        start()
    }

    private fun pair() = runBlocking {
        val url = java.net.URLEncoder.encode(server.url("/").toString(), "UTF-8")
        access.sync().pair(checkNotNull(PairingLink.parse("revealsync://pair?v=1&url=$url&token=t&server=$SERVER")))
    }

    /** A folder with one photo: the library shows its grid (an empty library shows the welcome instead). */
    private val folder = SyntheticFolder("trash-$stem").also {
        it.jpeg("$stem.JPG", 7)
        it.addAndIndex()
    }
    private var madeSource: Long? = null

    @After
    fun tearDown() {
        runBlocking {
            access.library().setLastFilters(PhotoFilters.NONE)
            db.photoDao().deletePhotos(made)
            madeSource?.let { db.sourceDao().delete(it) }
            if (access.sync().account.value != null) access.sync().unpair()
        }
        folder.delete()
        server.close()
    }

    /** The Server source (a sync started by the pairing may create it at the same time: then that one). */
    private suspend fun serverSource(): Long = db.syncDao().serverSource()?.id ?: runCatching {
        db.sourceDao().insert(
            SourceEntity(
                treeUri = ServerTree.SOURCE_TREE, rootDocumentId = "", displayName = "", volumeId = null,
                volumeName = null, state = SourceState.AVAILABLE, writable = false, addedAt = 0,
                kind = SourceKind.SERVER, backup = false,
            ),
        )
    }.getOrNull()?.also { madeSource = it } ?: checkNotNull(db.syncDao().serverSource()).id

    /** A Server photo of one NAS JPEG: [trashed] = its file is in Hoard's trash; [lost] = the server lost it. */
    private fun serverPhoto(name: String, trashed: Boolean, lost: Boolean): Long = runBlocking {
        val source = serverSource()
        val print = "v1:" + name.hashCode().toUInt().toString(16).padStart(64, '0')
        val id = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "NAS/$stem", folderDocumentId = "", stemKey = name.lowercase(),
                name = "$name.JPG", format = PhotoFormat.JPEG, hasRaw = false, sortTime = TOP,
                addedAt = 0,
            ),
        )
        made += id
        if (!lost) {
            db.photoDao().insertFiles(
                listOf(
                    PhotoFileEntity(
                        photoId = id, sourceId = source, relativePath = "nas/$stem/$name.JPG",
                        documentId = ServerTree.documentId(print, "$name.JPG"), name = "$name.JPG", size = 1000,
                        modified = 0, role = FileRole.RENDERED, fingerprint = print, onServer = true,
                        trashedAt = if (trashed) 1_700_000_000_000L else null,
                    ),
                ),
            )
        }
        db.photoDao().insertVersion(VersionEntity(photoId = id, createdAt = 0))
        db.syncDao().putCloud(
            PhotoCloudEntity(
                id,
                originalOnServer = !lost,
                originalSource = "nas".takeIf { !lost },
                checkedAt = 1,
                lostAt = if (lost) 1_700_000_000_000L else null,
            ),
        )
        id
    }

    /** A placeholder's tag inside a cell (the cell merges its children's semantics). */
    private fun waitInCell(tag: String) = compose.waitUntil(TIMEOUT_MS) {
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun aPhotoInHoardsTrashShowsOnlyUnderItsFilterAndOpensToANoteWithoutEdit() {
        pair()
        val trashed = serverPhoto("${stem}T", trashed = true, lost = false)
        runBlocking { access.library().setLastFilters(PhotoFilters(sync = setOf(SyncFilter.IN_TRASH))) }
        assertThat(runBlocking { access.library().isInTrash(trashed) }).isTrue()

        ActivityScenario.launch(MainActivity::class.java).use {
            waitInCell(LibraryTags.trashed(trashed))
            compose.onNodeWithTag(LibraryTags.cell(trashed)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.IN_TRASH), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.INFO), TIMEOUT_MS)
            assertThat(compose.onAllNodes(hasTestTag(ViewerTags.EDIT)).fetchSemanticsNodes()).isEmpty()
        }
        // Without its filter it is not listed.
        runBlocking { access.library().setLastFilters(PhotoFilters.NONE) }
        val listed = runBlocking { access.library().photoList(dev.hfps.reveal.data.model.PhotoQuery()) }
        assertThat(listed.map { it.id }).doesNotContain(trashed)
    }

    @Test
    fun aServerPhotoWhoseFileIsGoneIsUnavailableAndExplainsItself() {
        val lost = serverPhoto("${stem}L", trashed = false, lost = true)
        runBlocking {
            access.library().setLastFilters(
                PhotoFilters(sources = setOf(dev.hfps.reveal.data.model.SourceFilter.ServerOnly)),
            )
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            waitInCell(UnavailableTags.cell(lost))
            compose.onNodeWithTag(LibraryTags.cell(lost)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(UnavailableTags.SHEET), TIMEOUT_MS)
            compose.onNodeWithTag(UnavailableTags.REMOVE).assertExists()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L

        const val SERVER = "858e1b9d-0000-4000-8000-0000000000aa"
        const val DEVICE = "d0000000-0000-4000-8000-000000000001"
        const val KEY = "rvk1_aaaaaaaaaaaaaaaa_kkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkkk"
        const val PAIRED = """{"deviceId":"$DEVICE","apiKey":"$KEY","serverId":"$SERVER","serverName":"Test"}"""
        const val NO_CHANGES = """{"changes":[],"cursor":"c","hasMore":false}"""

        /** Newer than any other photo of the device: first in the grid, so its cell is composed. */
        val TOP = System.currentTimeMillis() + 24L * 60 * 60 * 1000
    }
}
