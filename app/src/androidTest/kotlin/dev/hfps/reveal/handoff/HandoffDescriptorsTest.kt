package dev.hfps.reveal.handoff

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.debug.SyntheticDocumentsProvider
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.work.ExportJobItem
import dev.hfps.reveal.feature.export.work.ExportJobStore
import dev.hfps.reveal.feature.export.work.ExportWorker
import dev.hfps.reveal.feature.export.work.ItemStatus
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import java.io.File
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hoard's descriptor rule (integration contract 11, Hoard 2.0.1; ADR 0018 "W3/C") on a device: the debug provider
 * counts the descriptors it hands out until Reveal closes them and refuses a seventh with `busy:`, as Hoard does. A
 * handed-over RAW (`files/samples/sony-zv-e10.arw`, CC0) is opened in the editor (preview, proxy, full resolution for
 * the zoom) and exported: at most 4 descriptors are ever open at once, and none stays open while the editor shows the
 * photo or after the export. Skipped without the sample. GPU: run it on a device.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class HandoffDescriptorsTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = TestPhotos.context
    private val stem = "HD${System.nanoTime() % 1_000_000_000}"
    private val inbox = File(context.filesDir, "${SyntheticDocumentsProvider.DIRECTORY}/inbox-$stem")

    @After
    fun tearDown() {
        SyntheticDocumentsProvider.stopTracking(context.filesDir)
        inbox.deleteRecursively()
    }

    private fun tracking() = SyntheticDocumentsProvider.tracking(context.filesDir)

    @Test
    fun aHandedOverRawIsNeverHeldOpenByTheEditorOrTheExport() {
        val sample = TestPhotos.sample("sony-zv-e10.arw")
        assumeTrue("needs files/samples/sony-zv-e10.arw", sample != null)
        inbox.mkdirs()
        val name = "${stem}_1.ARW"
        File(inbox, name).outputStream().use { out ->
            checkNotNull(sample).inputStream().use { it.copyTo(out) }
            out.write(stem.toByteArray())
        }
        val uri: Uri = DocumentsContract.buildDocumentUri(SyntheticDocumentsProvider.AUTHORITY, "inbox-$stem/$name")
        SyntheticDocumentsProvider.track(context.filesDir)

        val intent = Intent(Intent.ACTION_SEND).setType("image/x-sony-arw").putExtra(Intent.EXTRA_STREAM, uri)
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
            // The full-resolution decode for the zoom follows the proxy; then nothing may stay open.
            Thread.sleep(FULL_RESOLUTION_MS)
            val (open, most) = tracking()
            Log.i(TAG, "editor open: $open descriptors open, at most $most at once")
            assertThat(open).isEqualTo(0)
            assertThat(most).isAtMost(4)
        }

        val item = ExportJobItem(uri = uri.toString(), name = name, recipeJson = RecipeCodec.Default.encode(Recipe()))
        val job = ExportWorker.enqueue(context, listOf(item), ExportOptions(), 1)
        val store = ExportJobStore(context)
        val end = System.currentTimeMillis() + TIMEOUT_MS
        while (store.states(job).any { it.status == ItemStatus.PENDING } && System.currentTimeMillis() < end) {
            Thread.sleep(POLL_MS)
        }
        val (open, most) = tracking()
        Log.i(TAG, "after the export: $open descriptors open, at most $most at once")
        assertThat(store.states(job).single().status).isEqualTo(ItemStatus.DONE)
        assertThat(open).isEqualTo(0)
        assertThat(most).isAtMost(4)
        store.states(job).mapNotNull { it.resultUri }.forEach {
            runCatching { context.contentResolver.delete(Uri.parse(it), null, null) }
        }
        store.delete(job)
    }

    private companion object {
        const val TAG = "REVEAL_W3C"
        const val TIMEOUT_MS = 180_000L
        const val FULL_RESOLUTION_MS = 15_000L
        const val POLL_MS = 500L
    }
}
