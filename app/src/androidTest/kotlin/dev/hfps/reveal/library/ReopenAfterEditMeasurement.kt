package dev.hfps.reveal.library

import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorMetrics
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.viewer.ViewerTags
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W3/C's reopen measurement (ADR 0018 "W3/C"): a Server photo (`-e name <file name prefix>`, a RAW the server's NAS
 * holds) is opened from the library the way the owner does (grid cell, viewer, Edit), edited, closed, left alone for
 * `-e idle <s>` (the background jobs: edited thumbnail, edited preview upload), then opened again. Logs `REVEAL_W3C`
 * lines: both open times and what this device holds of the original before the reopen. Skipped without `name`.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class ReopenAfterEditMeasurement {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val args = InstrumentationRegistry.getArguments()

    @Test
    fun reopenAnEditedServerPhoto() {
        val name = args.getString("name")
        assumeTrue("needs -e name <file name prefix>", name != null)
        runBlocking { access.sync().syncMetadata() }
        val row = runBlocking { access.library().photoList(PhotoQuery(filters = PhotoFilters.SERVER_ONLY)) }
            .first { it.name.startsWith(checkNotNull(name)) }
        runBlocking { access.library().setLastFilters(PhotoFilters.SERVER_ONLY) }
        val first = open(row.id) {
            EditorMetrics.applyRecipe?.invoke(Recipe().let { it.copy(light = it.light.copy(exposureEv = 0.5f)) })
            Thread.sleep(SETTLE_MS)
        }
        log("first open: $first ms")
        val idle = args.getString("idle")?.toLongOrNull() ?: DEFAULT_IDLE_S
        Thread.sleep(idle * MILLIS)
        log("before the reopen: ${held(row.id)}")
        val second = open(row.id) {}
        log("reopen after edit: $second ms (first $first ms)")
    }

    /** Opens [photoId]'s editor from the grid; returns ms from the Edit tap to the developed photo. */
    private fun open(photoId: Long, then: () -> Unit): Long = ActivityScenario.launch(MainActivity::class.java).use {
        compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.GRID), TIMEOUT_MS)
        compose.onNodeWithTag(LibraryTags.GRID).performScrollToKey(photoId)
        compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.cell(photoId)), TIMEOUT_MS)
        compose.onNodeWithTag(LibraryTags.cell(photoId)).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(ViewerTags.EDIT), TIMEOUT_MS)
        val start = System.nanoTime()
        compose.onNodeWithTag(ViewerTags.EDIT).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
        val ms = (System.nanoTime() - start) / NANOS_PER_MS
        then()
        ms
    }

    /** What this device holds of [photoId]'s original: range-cache blocks and a kept whole file. */
    private fun held(photoId: Long): String {
        val print = access.database().query(
            "SELECT fingerprint FROM photo_files WHERE photo_id = ? AND fingerprint IS NOT NULL LIMIT 1",
            arrayOf<Any>(photoId),
        ).use { if (it.moveToFirst()) it.getString(0) else null }?.removePrefix("v1:") ?: return "no fingerprint"
        val blocks = File(context.cacheDir, "sync-originals/$print").listFiles()?.sumOf { it.length() } ?: 0L
        val kept = File(context.cacheDir, "sync-kept").listFiles()?.filter { it.name.startsWith(print) }
            ?.sumOf { it.length() } ?: 0L
        return "blocks=$blocks bytes, kept=$kept bytes"
    }

    private fun log(message: String) = Log.i(TAG, message)

    private companion object {
        const val TAG = "REVEAL_W3C"
        const val TIMEOUT_MS = 120_000L
        const val SETTLE_MS = 3_000L
        const val DEFAULT_IDLE_S = 90L
        const val MILLIS = 1_000L
        const val NANOS_PER_MS = 1_000_000L
    }
}
