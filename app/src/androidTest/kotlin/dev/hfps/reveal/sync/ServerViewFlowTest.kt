package dev.hfps.reveal.sync

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R as LibraryR
import dev.hfps.reveal.feature.library.ServerViewTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.UploadCreateRequest
import dev.hfps.reveal.sync.engine.FileData
import dev.hfps.reveal.sync.engine.PhotoData
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server's view end to end against a local `reveal-sync` (ADR 0018 FIX/V; `scripts/sync/local-server.sh`, `adb
 * reverse`): this app is one device (link 1), a scripted client is the other (link 2). The Server scope lists only
 * photos that open, a shot whose JPEG alone is on the server opens in the editor, a folder added while paired backs up
 * only edited photos, removing it keeps the backed-up photo under Server, and "Delete from server" deletes it there.
 * Arguments: `-e syncLinks "<link 1>,<link 2>"`; skipped without them. Synthetic content, unique per run;
 * locale-independent (tags and resources).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ServerViewFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val access get() = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val sync get() = access.sync()
    private val library get() = access.library()
    private lateinit var other: SyncApi
    private val run = (System.currentTimeMillis() % RUN_SPACE).toInt()

    @Before
    fun setUp() {
        val links = args.getString("syncLinks")?.split(',')?.map { it.trim() }.orEmpty()
        assumeTrue("needs -e syncLinks <link 1>,<link 2>", links.size >= 2)
        runBlocking { sync.unpair() }
        runBlocking { sync.pair(checkNotNull(PairingLink.parse(links[0]))) }
        val second = checkNotNull(PairingLink.parse(links[1]))
        val key = SyncApi(second.url, null).pair(PairRequest(second.token, "Scripted tablet", "android", "test")).apiKey
        other = SyncApi(second.url, key)
    }

    @After
    fun tearDown() {
        runBlocking { sync.unpair() }
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** The other device backs up [bytes] as an original. */
    private fun upload(bytes: ByteArray) {
        val created = other.createUpload(
            UploadCreateRequest(sha256(bytes), bytes.size.toLong(), "original", FileFingerprint.of(bytes)),
        )
        if (created.status == "exists") return
        other.patchUpload(checkNotNull(created.uploadId), created.offset, bytes.size - created.offset.toInt()) {
            it.write(bytes, created.offset.toInt(), bytes.size - created.offset.toInt())
        }
    }

    /** The other device pushes a RAW + JPEG photo record (nothing uploaded by this call). */
    private fun pushPair(stem: String, raw: ByteArray, jpeg: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val data = PhotoData(
            fingerprint = FileFingerprint.of(raw),
            files = listOf(
                FileData(FileFingerprint.of(raw), "RAW", "$stem.ARW", raw.size.toLong()),
                FileData(FileFingerprint.of(jpeg), "RENDERED", "$stem.JPG", jpeg.size.toLong()),
            ),
            name = "$stem.ARW",
            format = "RAW_PAIR",
        )
        other.push(
            listOf(
                PushChange(
                    "photo",
                    id,
                    0,
                    System.currentTimeMillis(),
                    data = SyncApi.json.encodeToJsonElement(PhotoData.serializer(), data),
                ),
            ),
        )
        return id
    }

    private fun serverNames() =
        runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }.map { it.name }

    private fun openServerScope() {
        compose.waitUntilAtLeastOneExists(hasText(context.getString(LibraryR.string.library_server)), TIMEOUT_MS)
        compose.onNodeWithText(context.getString(LibraryR.string.library_server)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
    }

    @Test
    fun aShotWhoseJpegAloneIsOnTheServerIsListedAndOpensMetadataOnlyIsNot() {
        val folder = SyntheticFolder("server-view-shots")
        val preview = folder.gradientJpeg(run)
        val raw = SyntheticShoot.raw(preview, folder.exif(run))
        val jpeg = SyntheticShoot.jpeg(preview, folder.exif(run))
        val openable = "SVJ$run"
        val described = "SVM$run"
        pushPair(openable, raw, jpeg)
        upload(jpeg) // e.g. the camera JPEGs reached the server another way; the RAW never did
        val other = folder.gradientJpeg(run + 1)
        pushPair(described, SyntheticShoot.raw(other, folder.exif(run + 1)), SyntheticShoot.jpeg(other, folder.exif(1)))
        runBlocking { sync.syncMetadata() }

        assertThat(serverNames()).contains("$openable.ARW")
        assertThat(serverNames()).doesNotContain("$described.ARW")
        val photo = runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }
            .single { it.name == "$openable.ARW" }
        ActivityScenario.launch(MainActivity::class.java).use {
            openServerScope()
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photo.id)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
            compose.onNodeWithTag(ViewerTags.EDIT).performClick()
            // Opened from the JPEG the server holds (0.5.2 asked for the RAW's sibling it never had: 404).
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), EDITOR_TIMEOUT_MS)
            Espresso.pressBack()
            Espresso.pressBack()
        }
        folder.delete()
    }

    @Suppress("LongMethod")
    @Test
    fun aFolderBacksUpEditedPhotosStaysUnderServerWhenRemovedAndCanBeDeletedFromTheServer() {
        val folder = SyntheticFolder("server-view-$run")
        folder.pair("SVE$run", run)
        folder.pair("SVP$run", run + 1)
        val (sourceId, _) = folder.addAndIndex()
        // Added with the default: only photos you edit are backed up.
        assertThat(runBlocking { library.sources().first() }.single { it.id == sourceId }.backup).isFalse()
        val dao = access.database().photoDao()
        compose.waitUntil(TIMEOUT_MS) {
            runBlocking { dao.filesOf(access.database().syncDao().photosOfSource(sourceId).map { it.id }) }.all {
                it.fingerprint !=
                    null
            }
        }
        val edited = runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Source(sourceId))) }
            .single { it.name == "SVE$run.ARW" }
        runBlocking {
            val before = folder.edits.recipe(edited.versionId)
            val after = before.copy(light = before.light.copy(exposureEv = 0.5f))
            folder.edits.commit(edited.versionId, after, RecipeDiff.between(before, after))
            sync.syncMetadata()
            sync.syncBlobs(originalsAllowed = true, unmetered = true)
            sync.syncMetadata()
        }
        val files = runBlocking { dao.filesOf(access.database().syncDao().photosOfSource(sourceId).map { it.id }) }
        val editedRaw = checkNotNull(files.single { it.name == "SVE$run.ARW" }.fingerprint)
        val plainRaw = checkNotNull(files.single { it.name == "SVP$run.ARW" }.fingerprint)
        val looked = other.lookup(listOf(editedRaw, plainRaw))
        assertThat(looked.found.map { it.fingerprint }).containsExactly(editedRaw)

        ActivityScenario.launch(MainActivity::class.java).use {
            val actions = context.getString(LibraryR.string.library_source_actions, folder.name)
            compose.waitUntilAtLeastOneExists(hasContentDescription(actions), TIMEOUT_MS)
            compose.onNode(hasContentDescription(actions)).performClick()
            compose.onNodeWithText(context.getString(LibraryR.string.library_source_remove)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ServerViewTags.REMOVE_DIALOG), TIMEOUT_MS)
            // The folder has an edited photo: "Also delete from the server" starts unchecked.
            compose.onNodeWithTag(ServerViewTags.REMOVE_ALSO_SERVER).assertIsOff()
            compose.onNodeWithTag(ServerViewTags.REMOVE_CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) { runBlocking { library.sources().first() }.none { it.id == sourceId } }
            runBlocking { sync.syncMetadata() }
            // The edited photo stays visible here, under Server, from its backup; the plain one is gone.
            compose.waitUntil(TIMEOUT_MS) { "SVE$run.ARW" in serverNames() }
            assertThat(serverNames()).doesNotContain("SVP$run.ARW")

            val photo = runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }
                .single { it.name == "SVE$run.ARW" }
            openServerScope()
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photo.id)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performTouchInput { longClick() }
            compose.onNode(hasContentDescription(context.getString(LibraryR.string.library_menu))).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ServerViewTags.DELETE_FROM_SERVER), TIMEOUT_MS)
            compose.onNodeWithTag(ServerViewTags.DELETE_FROM_SERVER).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ServerViewTags.DELETE_DIALOG), TIMEOUT_MS)
            compose.onNodeWithTag(ServerViewTags.DELETE_CONFIRM).performClick()
            compose.waitUntil(TIMEOUT_MS) { "SVE$run.ARW" !in serverNames() }
            runBlocking { sync.syncMetadata() }
            assertThat(other.lookup(listOf(editedRaw)).found).isEmpty()
        }
        folder.delete()
    }

    private companion object {
        const val TIMEOUT_MS = 60_000L
        const val EDITOR_TIMEOUT_MS = 120_000L
        const val RUN_SPACE = 100_000L
    }
}
