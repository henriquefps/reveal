package dev.hfps.reveal.sync

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.work.ExportJobItem
import dev.hfps.reveal.feature.export.work.ExportJobStore
import dev.hfps.reveal.feature.export.work.ExportWorker
import dev.hfps.reveal.feature.export.work.ItemStatus
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R as LibraryR
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.feature.settings.SettingsTags
import dev.hfps.reveal.feature.settings.sync.SyncTags
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sync in the app, end to end against a local `reveal-sync` (ADR 0018; `scripts/sync/local-server.sh`, `adb reverse`):
 * pair from the Sync screen with a pasted link (the device key goes to the Keystore), see the server's NAS photo under
 * "Server", open it in the editor (the original read by range through `ServerFilesProvider`) and unpair. Arguments:
 * `-e syncLink <pairing link>` and `-e nasName <file name>` (a CC0 RAW the script indexed). Skipped without them.
 * Locale-independent (tags and resources).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SyncFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val sync get() = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).sync()

    @Before
    fun setUp() {
        assumeTrue(
            "needs -e syncLink and -e nasName",
            args.getString("syncLink") != null && args.getString("nasName") != null,
        )
        runBlocking { sync.unpair() }
    }

    @After
    fun tearDown() {
        runBlocking { sync.unpair() }
    }

    /** Synthetic and CC0 content only: device screenshots for the docs (`files/pr-shots`). */
    private fun shot(name: String) {
        compose.waitForIdle()
        Thread.sleep(SETTLE_MS)
        val dir = File(context.filesDir, "pr-shots").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Suppress("LongMethod")
    @Test
    fun pairFromTheSyncScreenOpenANasPhotoAndUnpair() {
        val link = checkNotNull(args.getString("syncLink"))
        val nasName = checkNotNull(args.getString("nasName"))
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(
                hasContentDescription(context.getString(LibraryR.string.library_menu)),
                TIMEOUT_MS,
            )
            compose.onNode(hasContentDescription(context.getString(LibraryR.string.library_menu))).performClick()
            compose.onNodeWithText(context.getString(LibraryR.string.library_menu_settings)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SettingsTags.SYNC), TIMEOUT_MS)
            compose.onNodeWithTag(SettingsTags.SYNC).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.LINK), TIMEOUT_MS)
            shot("sync-unpaired")
            compose.onNodeWithTag(SyncTags.LINK).performTextInput(link)
            compose.onNodeWithTag(SyncTags.PAIR).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.SERVER), TIMEOUT_MS)
            assertThat(sync.account.value).isNotNull()
            // The first sync runs as a job; run one here too so the test does not wait on WorkManager's schedule.
            runBlocking { sync.syncMetadata() }
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.STATUS), TIMEOUT_MS)
            shot("sync-paired")

            Espresso.pressBack()
            Espresso.pressBack()
            val library = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).library()
            val photo = runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }.single {
                it.name ==
                    nasName
            }
            assertThat(photo.remote).isTrue()
            assertThat(photo.cloudOriginal).isTrue()
            // Its capture time comes from its header, read by range (it sorts like a local photo).
            assertThat(photo.captureTime).isNotNull()
            assertThat(photo.sortTime).isEqualTo(photo.captureTime)
            exportServerPhotos(photo.versionId)
            compose.waitUntilAtLeastOneExists(hasText(context.getString(LibraryR.string.library_server)), TIMEOUT_MS)
            compose.onNodeWithText(context.getString(LibraryR.string.library_server)).performClick()
            // The server may hold many photos (other devices'): bring this one into the lazy grid first.
            // (The grid by its tag: on a tablet the sources list beside it scrolls by key too.)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photo.id)
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photo.id)), TIMEOUT_MS)
            shot("library-server")
            compose.onNodeWithTag(LibraryTags.cell(photo.id)).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
            compose.onNodeWithTag(ViewerTags.EDIT).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), EDITOR_TIMEOUT_MS)
            shot("editor-server-photo")
            Espresso.pressBack()
            Espresso.pressBack()
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(
                hasContentDescription(context.getString(LibraryR.string.library_menu)),
                TIMEOUT_MS,
            )
            compose.onNode(hasContentDescription(context.getString(LibraryR.string.library_menu))).performClick()
            compose.onNodeWithText(context.getString(LibraryR.string.library_menu_settings)).performClick()
            compose.onNodeWithTag(SettingsTags.SYNC).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.UNPAIR), TIMEOUT_MS)
            compose.onNodeWithTag(SyncTags.UNPAIR).performScrollTo().performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.UNPAIR_CONFIRM), TIMEOUT_MS)
            compose.onNodeWithTag(SyncTags.UNPAIR_CONFIRM).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.SCAN), TIMEOUT_MS)
            assertThat(sync.account.value).isNull()
            // The account goes first (the screen shows Scan at once); forgetting the server's photos follows.
            val library = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).library()
            compose.waitUntil(TIMEOUT_MS) {
                runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }.isEmpty()
            }
        }
    }

    /**
     * A server original exports (downloaded first); a photo only another device has waits instead of failing
     * (ADR 0018 "Export"). The second photo is a record pushed with this device's key for a file nobody uploaded.
     */
    private fun exportServerPhotos(serverVersion: Long) {
        val sync = this.sync
        val api = checkNotNull(sync.api())
        val photo = java.util.UUID.randomUUID().toString()
        val name = "ELSEWHERE-${photo.take(8)}.JPG"
        val fingerprint = "v1:" + java.security.MessageDigest.getInstance("SHA-256").digest(photo.toByteArray())
            .joinToString("") { "%02x".format(it) }
        api.push(
            listOf(
                dev.hfps.reveal.sync.api.PushChange(
                    "photo",
                    photo,
                    0,
                    System.currentTimeMillis(),
                    data = kotlinx.serialization.json.buildJsonObject {
                        put("fingerprint", kotlinx.serialization.json.JsonPrimitive(fingerprint))
                        put("name", kotlinx.serialization.json.JsonPrimitive(name))
                        put(
                            "files",
                            kotlinx.serialization.json.JsonArray(
                                listOf(
                                    kotlinx.serialization.json.buildJsonObject {
                                        put("fingerprint", kotlinx.serialization.json.JsonPrimitive(fingerprint))
                                        put("role", kotlinx.serialization.json.JsonPrimitive("RENDERED"))
                                        put("name", kotlinx.serialization.json.JsonPrimitive(name))
                                        put("size", kotlinx.serialization.json.JsonPrimitive(4_000_000))
                                    },
                                ),
                            ),
                        )
                    },
                ),
            ),
        )
        runBlocking { sync.syncMetadata() }
        val library = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).library()
        val elsewhere = runBlocking { library.photoList(PhotoQuery(scope = LibraryScope.Server)) }
            .single { it.name == name }
        val items = listOf(ExportJobItem(versionId = serverVersion), ExportJobItem(versionId = elsewhere.versionId))
        val jobId = ExportWorker.enqueue(context, items, ExportOptions(), 1)
        val work = WorkManager.getInstance(context)
        val end = System.currentTimeMillis() + EDITOR_TIMEOUT_MS * 2
        while (work.getWorkInfosByTag(jobId).get().firstOrNull()?.state?.isFinished != true) {
            check(System.currentTimeMillis() < end) { "export timed out" }
            Thread.sleep(SETTLE_MS)
        }
        val store = ExportJobStore(context)
        val states = store.states(jobId)
        assertThat(states.map { it.status }).containsExactly(ItemStatus.DONE, ItemStatus.WAITING).inOrder()
        assertThat(states[1].waitingSince).isNotNull()
        states.mapNotNull {
            it.resultUri
        }.forEach { context.contentResolver.delete(android.net.Uri.parse(it), null, null) }
        work.cancelUniqueWork("reveal-export-wait-$jobId")
        store.delete(jobId)
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val EDITOR_TIMEOUT_MS = 90_000L
        const val SETTLE_MS = 1_000L
    }
}
