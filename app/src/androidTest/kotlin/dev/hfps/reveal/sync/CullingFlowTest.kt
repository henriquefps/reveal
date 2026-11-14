package dev.hfps.reveal.sync

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.R as LibraryR
import dev.hfps.reveal.feature.settings.sync.SyncTags
import dev.hfps.reveal.feature.settings.sync.ThisDeviceTags
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.CullingChange
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Culling through hoard-server end to end (Reveal ADR 0027) against a local server (`scripts/sync/local-server.sh`,
 * `adb reverse`): "Hoard" (a second paired client with this phone's model) pairs first; Reveal pairs from the Sync
 * screen, answers the one question ("same phone as Hoard?") and shows Hoard under This device; a rating and a pick
 * Hoard sends to the server appear in Reveal's grid while the app is in the foreground (no manual sync), and a reject
 * made in Reveal reaches the server for Hoard, keeping the stars. Arguments: `-e syncLinks <link>,<link>`. Skipped
 * without them. Locale-independent (tags and resources). Logs `REVEAL_CULLING` (latencies).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class CullingFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val links = InstrumentationRegistry.getArguments().getString("syncLinks").orEmpty().split(',')
        .filter { it.isNotBlank() }
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val run = System.nanoTime() % 1_000_000_000
    private val folder = SyntheticFolder("culling-$run")

    @Before
    fun setUp() {
        assumeTrue("needs -e syncLinks <link>,<link>", links.size >= 2)
        runBlocking { access.sync().unpair() }
    }

    @After
    fun tearDown() {
        runBlocking { access.sync().unpair() }
        folder.delete()
    }

    /** Synthetic content only: device screenshots for the PR (`files/pr-shots`). */
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
    fun aRatingSetThroughTheServerReachesTheGridAndARejectGoesBack() {
        // "Hoard" on this same phone pairs first (its own key, this phone's model: Hoard ADR 0017).
        val other = checkNotNull(PairingLink.parse(links[1]))
        val paired = SyncApi(other.url, null).pair(
            PairRequest(other.token, "Hoard (test)", "android", "test", "hoard", Build.MANUFACTURER, Build.MODEL),
        )
        val hoard = SyncApi(other.url, paired.apiKey)

        val name = "CULL_$run.JPG"
        folder.jpeg(name, 11, base = folder.gradientJpeg(11) + "culling-$run".toByteArray())
        val fingerprint = FileFingerprint.of(File(folder.dir, name).readBytes())
        folder.addAndIndex()
        val photoId = waitForFingerprint(fingerprint)

        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SYNC_STATUS), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.SYNC_STATUS).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.LINK), TIMEOUT_MS)
            compose.onNodeWithTag(SyncTags.LINK).performTextInput(links[0])
            compose.onNodeWithTag(SyncTags.PAIR).performClick()

            // The one question: Hoard is on a phone of this model -> "Same phone".
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.SAME_PHONE_YES), TIMEOUT_MS)
            shot("culling-same-phone")
            compose.onNodeWithTag(SyncTags.SAME_PHONE_YES).performClick()
            compose.waitUntilDoesNotExist(hasTestTag(SyncTags.SAME_PHONE), TIMEOUT_MS)
            compose.onNodeWithTag(SyncTags.THIS_DEVICE).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(ThisDeviceTags.app(paired.deviceId)), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(ThisDeviceTags.CULLING), TIMEOUT_MS)
            shot("culling-this-device")
            Espresso.pressBack()
            Espresso.pressBack()

            // Wait for the first sync (it declares culling); then Hoard rates and picks the shot on the server.
            waitUntil(TIMEOUT_MS) { runBlocking { hoard.device() }.physicalDevice?.devices.orEmpty().size == 2 }
            waitUntil(TIMEOUT_MS) {
                runBlocking { access.sync().api()?.device()?.features.orEmpty().contains("culling") }
            }
            val sent = System.currentTimeMillis()
            val results = hoard.pushCulling(
                listOf(
                    CullingChange(fingerprint, null, "rating", JsonPrimitive(4), 0, sent),
                    CullingChange(fingerprint, null, "flag", JsonPrimitive("pick"), 0, sent),
                ),
            ).results
            assertThat(results.map { it.status }).containsExactly("applied", "applied")
            val itemId = checkNotNull(results.first().itemId)

            // No manual sync: the app pulls every 30 s while it is in the foreground.
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photoId)
            val stars = context.resources.getQuantityString(LibraryR.plurals.library_stars, 4, 4)
            val picked = context.getString(LibraryR.string.library_flag_picked)
            compose.waitUntilAtLeastOneExists(
                hasTestTag(LibraryTags.cell(photoId)) and hasContentDescription(stars, substring = true) and
                    hasContentDescription(picked, substring = true),
                ARRIVAL_MS,
            )
            val arrived = System.currentTimeMillis() - sent
            shot("culling-grid")

            // And back: a reject made in Reveal reaches the server (the change settles 3 s, then a sync).
            val rejected = System.currentTimeMillis()
            runBlocking { access.library().setFlag(listOf(photoId), PickFlag.REJECT) }
            waitUntil(ARRIVAL_MS) {
                hoard.cullingRecord(itemId)?.flag?.value?.jsonPrimitive?.contentOrNull == "reject"
            }
            val back = System.currentTimeMillis() - rejected
            val record = checkNotNull(hoard.cullingRecord(itemId))
            // A reject keeps its stars.
            assertThat(record.rating?.value?.jsonPrimitive?.intOrNull).isEqualTo(4)
            assertThat(record.flag?.deviceId).isEqualTo(access.sync().account.value?.deviceId)
            Log.i(TAG, "server -> grid ${arrived}ms, Reveal -> server ${back}ms")
        }
    }

    /** The photo of [fingerprint] once the folder's background fingerprint pass reached it. */
    private fun waitForFingerprint(fingerprint: String): Long {
        val photos = access.database().photoDao()
        var id: Long? = null
        waitUntil(TIMEOUT_MS) {
            id = runBlocking { photos.filesByFingerprint(listOf(fingerprint)) }.firstOrNull()?.file?.photoId
            id != null
        }
        return checkNotNull(id)
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        error("timed out after $timeoutMs ms")
    }

    private companion object {
        const val TAG = "REVEAL_CULLING"
        const val TIMEOUT_MS = 30_000L
        const val ARRIVAL_MS = 75_000L
        const val POLL_MS = 500L
        const val SETTLE_MS = 600L
    }
}
