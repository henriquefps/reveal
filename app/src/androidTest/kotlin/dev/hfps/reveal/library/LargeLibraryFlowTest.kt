package dev.hfps.reveal.library

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.storage.FileOpens
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.library.LibraryTags
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W3/C on a large library (ADR 0018 "W3/C"), after `LargeLibrarySetup#prepare` (a local hoard-server with 20,000+ NAS
 * photos). Skipped without `-e largeLibrary true`, so the full suite never runs it:
 *
 * - [startUpDoesNotBurst]: the app starts on the library and sits for `-e seconds` (default 60): file opens stay paced
 *   (at most [FileOpens.BACKGROUND_SLOTS] open at once, few per second);
 * - [prefetchThenOffline] in two phases: `-e phase prefetch` stores the library thumbnails (`-e count`, default 300);
 *   then the host stops the server (or removes `adb reverse`) and `-e phase offline` shows the grid of Server photos
 *   with every thumbnail from this device.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class LargeLibraryFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun startUpDoesNotBurst() {
        assumeTrue("needs -e largeLibrary true", args.getString("largeLibrary") == "true")
        val seconds = args.getString("seconds")?.toLongOrNull() ?: DEFAULT_SECONDS
        FileOpens.resetCounters()
        runBlocking { access.library().setLastFilters(PhotoFilters.NONE) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
            Thread.sleep(seconds * MILLIS)
        }
        log(
            "start-up: opens=${FileOpens.opens} peak/s=${FileOpens.peakPerSecond} " +
                "peak open=${FileOpens.peakConcurrent} in $seconds s",
        )
        assertThat(FileOpens.peakConcurrent).isAtMost(FileOpens.BACKGROUND_SLOTS)
        assertThat(FileOpens.peakPerSecond).isAtMost(MAX_OPENS_PER_SECOND)
    }

    @Test
    fun prefetchThenOffline() {
        when (args.getString("phase")) {
            "prefetch" -> {
                val count = args.getString("count")?.toIntOrNull() ?: DEFAULT_COUNT
                val report = runBlocking { access.libraryThumbs().prefetch(maxItems = count) }
                val stats = runBlocking { access.libraryThumbs().stats() }
                log("prefetch: $report; stored ${stats.stored} thumbnails, ${stats.bytes} bytes")
                assertThat(stats.stored).isAtLeast(count)
            }

            "offline" -> offline()

            else -> assumeTrue("needs -e phase prefetch|offline", false)
        }
    }

    private fun offline() {
        val rows = runBlocking { access.library().photoList(PhotoQuery(filters = PhotoFilters.SERVER_ONLY)) }
            .take(OFFLINE_CELLS)
        val thumbnails = access.thumbnails()
        val start = System.nanoTime()
        val shown = rows.count { row -> runBlocking { thumbnails.thumbnail(row.id, row.versionId, null) } != null }
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        log("offline: $shown of ${rows.size} thumbnails in $ms ms")
        assertThat(shown).isEqualTo(rows.size)
        runBlocking { access.library().setLastFilters(PhotoFilters.SERVER_ONLY) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(rows.first().id)), TIMEOUT_MS)
        }
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_W3C"
        const val TIMEOUT_MS = 60_000L
        const val DEFAULT_SECONDS = 60L
        const val DEFAULT_COUNT = 300
        const val OFFLINE_CELLS = 120
        const val MAX_OPENS_PER_SECOND = 12
        const val MILLIS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
