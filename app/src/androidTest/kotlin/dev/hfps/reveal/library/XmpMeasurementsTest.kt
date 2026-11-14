package dev.hfps.reveal.library

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What XMP portability costs (ADR 0026 "Measurements"), over SAF (the debug documents provider): 1,000 RAW+JPEG pairs;
 * an edit of every photo written to new sidecars, a second edit merged into them, then a second folder with those
 * sidecars indexed from scratch (every edit read back as an "Imported from XMP" step). Logs `REVEAL_XMP`. Measurement:
 * excluded from the full suite, run on its own (`-e class dev.hfps.reveal.library.XmpMeasurementsTest`).
 */
@RunWith(AndroidJUnit4::class)
class XmpMeasurementsTest {
    private val folder = SyntheticFolder("xmp-measure")
    private val copy = SyntheticFolder("xmp-measure-copy")
    private val db = EntryPointAccessors.fromApplication(
        InstrumentationRegistry.getInstrumentation().targetContext,
        DebugAccess::class.java,
    ).database()

    @After
    fun tearDown() {
        folder.delete()
        copy.delete()
    }

    @Test
    fun thousandPhotos() = runBlocking {
        folder.xmp.setWriteEdits(true)
        folder.xmp.setReadEdits(true)
        val preview = folder.gradientJpeg(0, 160, 120, 70)
        repeat(PHOTOS) { folder.pair("DSC%05d".format(it), it, preview) }
        val (sourceId, indexed) = folder.addAndIndex(timeoutMs = 600_000)
        log("indexed ${indexed.photosAdded} photos in ${indexed.totalMs} ms")
        val photos = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(filters = PhotoFilters.folder(sourceId))))
        val versions = folder.edits.versionsOf(photos.map { it.id })
        assertThat(versions).hasSize(PHOTOS)

        // 1. New sidecars: one edit on every photo (a batch, one transaction), then the writer.
        folder.edits.applyEach(versions, HistoryAction.PASTE) {
            it.copy(
                light = Light(exposureEv = 0.5f, contrast = 12f),
                geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)),
            )
        }
        var t = System.nanoTime()
        val created = folder.editSidecars.flush()
        val createMs = ms(t)
        log("created $created sidecars in $createMs ms (${"%.2f".format(createMs.toDouble() / PHOTOS)} ms per photo)")

        // 2. Merged: a second edit rewrites every sidecar.
        folder.edits.applyEach(versions, HistoryAction.PASTE) { it.copy(colour = Colour(vibrance = 15f)) }
        t = System.nanoTime()
        val merged = folder.editSidecars.flush()
        val mergeMs = ms(t)
        log("merged $merged sidecars in $mergeMs ms (${"%.2f".format(mergeMs.toDouble() / PHOTOS)} ms per photo)")
        val bytes = folder.dir.listFiles()!!.filter { it.name.endsWith(".xmp") }.map { it.length() }
        log("sidecar size: median ${bytes.sorted()[bytes.size / 2]} B, max ${bytes.max()} B")

        // 3. Read back: the same files in a new folder, indexed from scratch (another device, no sync).
        folder.dir.listFiles()!!.forEach { it.copyTo(File(copy.dir, it.name)) }
        t = System.nanoTime()
        val (_, read) = copy.addAndIndex(timeoutMs = 600_000, alongside = true)
        log(
            "read back: ${read.sidecarsRead} sidecars, ${read.editsImported} edits imported; sidecar phase " +
                "${read.sidecarMs} ms, index total ${read.totalMs} ms (wall ${ms(t)} ms)",
        )
        assertThat(created).isEqualTo(PHOTOS)
        assertThat(merged).isEqualTo(PHOTOS)
        assertThat(read.editsImported).isEqualTo(PHOTOS)
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000

    private fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val PHOTOS = 1_000
        const val TAG = "REVEAL_XMP"
    }
}
