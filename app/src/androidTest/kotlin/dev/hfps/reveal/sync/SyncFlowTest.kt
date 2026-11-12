package dev.hfps.reveal.sync

import android.graphics.Bitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToKeyAction
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
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorTags
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
            compose.waitUntilAtLeastOneExists(hasText(context.getString(LibraryR.string.library_server)), TIMEOUT_MS)
            compose.onNodeWithText(context.getString(LibraryR.string.library_server)).performClick()
            // The server may hold many photos (other devices'): bring this one into the lazy grid first.
            compose.waitUntilAtLeastOneExists(hasScrollToKeyAction(), TIMEOUT_MS)
            compose.onNode(hasScrollToKeyAction()).performScrollToKey(photo.id)
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

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val EDITOR_TIMEOUT_MS = 90_000L
        const val SETTLE_MS = 1_000L
    }
}
