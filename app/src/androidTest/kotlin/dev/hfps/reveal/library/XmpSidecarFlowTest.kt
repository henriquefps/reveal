package dev.hfps.reveal.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.xmp.DevelopState
import dev.hfps.reveal.data.xmp.PhotoFrame
import dev.hfps.reveal.data.xmp.SidecarRecipe
import dev.hfps.reveal.data.xmp.XmpDevelop
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.data.xmp.XmpWriter
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0026 through the real app over SAF (the debug documents provider, its own process): a folder whose sidecar has
 * Lightroom's develop settings is added (the edit comes in as one "Imported from XMP" step, the rating too); an edit
 * reaches the sidecar through the app's own debounced writer (`EditSidecars`, started by the application); the next
 * walk does not read it back; a newer edit written by "another device" into the sidecar comes in on the next index.
 */
@RunWith(AndroidJUnit4::class)
class XmpSidecarFlowTest {
    private val folder = SyntheticFolder("xmp-flow")
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val db = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).database()

    @After
    fun tearDown() {
        folder.xmp.setWriteEdits(true)
        folder.xmp.setReadEdits(true)
        folder.delete()
    }

    private fun waitFor(timeoutMs: Long, what: String, check: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!check()) {
            check(System.currentTimeMillis() < end) { "timed out waiting for $what" }
            Thread.sleep(POLL_MS)
        }
    }

    private fun reindex(sourceId: Long) = runBlocking {
        val before = folder.library.indexReports.value[sourceId]
        folder.library.index(sourceId).join()
        withTimeout(TIMEOUT_MS) { folder.library.indexReports.first { it[sourceId] !== before } }.getValue(sourceId)
    }

    @Test
    fun lightroomEditComesInAndRevealEditsGoOut() = runBlocking {
        folder.xmp.setWriteEdits(true)
        folder.xmp.setReadEdits(true)
        folder.pair("DSC00001", 1)
        val sidecar = File(folder.dir, "DSC00001.xmp")
        sidecar.writeText(LIGHTROOM)
        val (sourceId, report) = folder.addAndIndex()
        assertThat(report.editsImported).isEqualTo(1)

        val photo = db.photoDao().rows(
            PhotoQuerySql.toQuery(PhotoQuery(filters = PhotoFilters.folder(sourceId))),
        ).single()
        val versionId = checkNotNull(db.photoDao().firstVersion(photo.id))
        val imported = folder.edits.recipe(versionId)
        assertThat(imported.light.exposureEv).isEqualTo(0.7f)
        assertThat(imported.light.highlights).isEqualTo(-40f)
        assertThat(imported.colour.vibrance).isEqualTo(20f)
        assertThat(folder.edits.history(versionId).steps.single().label.action).isEqualTo(HistoryAction.XMP_IMPORT)
        assertThat(db.photoDao().get(photo.id)!!.rating).isEqualTo(4)

        // An edit in Reveal reaches the sidecar through the app's writer, merged into Lightroom's file.
        val edit = imported.copy(light = imported.light.copy(contrast = 25f))
        folder.edits.commit(versionId, edit, RecipeDiff.between(imported, edit))
        waitFor(TIMEOUT_MS, "the sidecar write") {
            runCatching {
                XmpDevelop.read(sidecar.readBytes()).reveal?.recipe == edit
            }.getOrDefault(false)
        }
        // The write is recorded (its mtime), so the next walk will not read it back.
        waitFor(TIMEOUT_MS, "the recorded write") {
            runBlocking { db.photoDao().get(photo.id)!!.xmpModified } == sidecar.lastModified()
        }
        val text = sidecar.readText()
        assertThat(text).contains("crs:Contrast2012=\"+25\"")
        assertThat(text).contains("crs:CameraProfile=\"Adobe Color\"")
        assertThat(XmpReader.read(sidecar.readBytes()).rating).isEqualTo(4)
        assertThat(reindex(sourceId).sidecarsRead).isEqualTo(0)

        // Another device (no sync) edits the same folder: its newer edit comes in on the next walk.
        val theirs = edit.copy(colour = Colour(vibrance = 20f, saturation = -30f))
        val state =
            DevelopState(
                theirs,
                PhotoFrame(raw = true),
                listOf(SidecarRecipe.digest(edit)),
                System.currentTimeMillis(),
                "Tab S9",
            )
        sidecar.writeBytes(
            XmpWriter.update(
                sidecar.readBytes(),
                XmpFields(rating = 4),
                "Reveal 1.0.0",
                "2026-11-14T10:00:00Z",
                listOf(XmpDevelop.extension(state)),
            ),
        )
        sidecar.setLastModified(System.currentTimeMillis() + MTIME_STEP_MS)
        val again = reindex(sourceId)
        assertThat(again.editsImported).isEqualTo(1)
        assertThat(folder.edits.recipe(versionId)).isEqualTo(theirs)
        assertThat(folder.edits.history(versionId).steps.last().label.action).isEqualTo(HistoryAction.XMP_IMPORT)
    }

    @Test
    fun switchedOffNothingTravelsButRatings() = runBlocking {
        folder.xmp.setWriteEdits(false)
        folder.xmp.setReadEdits(false)
        folder.pair("DSC00002", 2)
        val sidecar = File(folder.dir, "DSC00002.xmp")
        sidecar.writeText(LIGHTROOM)
        val (sourceId, report) = folder.addAndIndex()
        assertThat(report.editsImported).isEqualTo(0)
        val photo = db.photoDao().rows(
            PhotoQuerySql.toQuery(PhotoQuery(filters = PhotoFilters.folder(sourceId))),
        ).single()
        val versionId = checkNotNull(db.photoDao().firstVersion(photo.id))
        assertThat(folder.edits.recipe(versionId)).isEqualTo(Recipe())
        val before = sidecar.readText()
        folder.edits.commit(
            versionId,
            Recipe(light = Light(exposureEv = 1f)),
            RecipeDiff.between(Recipe(), Recipe(light = Light(exposureEv = 1f))),
        )
        Thread.sleep(SETTLE_MS)
        assertThat(sidecar.readText()).isEqualTo(before)
        folder.library.setRating(listOf(photo.id), 2)
        assertThat(XmpReader.read(sidecar.readBytes()).rating).isEqualTo(2)
        assertThat(sidecar.readText()).doesNotContain("reveal:")
    }

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val POLL_MS = 100L
        const val SETTLE_MS = 4_000L
        const val MTIME_STEP_MS = 10_000L

        /** A Lightroom Classic sidecar (hand-written in its layout; no real file). */
        val LIGHTROOM = """
            <x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 7.0-c000 1.000000, 0000/00/00-00:00:00        ">
             <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
              <rdf:Description rdf:about=""
                xmlns:xmp="http://ns.adobe.com/xap/1.0/"
                xmlns:crs="http://ns.adobe.com/camera-raw-settings/1.0/"
               xmp:Rating="4"
               crs:ProcessVersion="15.4"
               crs:WhiteBalance="As Shot"
               crs:Exposure2012="+0.70"
               crs:Highlights2012="-40"
               crs:Vibrance="+20"
               crs:Sharpness="40"
               crs:ColorNoiseReduction="25"
               crs:CameraProfile="Adobe Color"
               crs:HasSettings="True"
               crs:HasCrop="False"/>
             </rdf:RDF>
            </x:xmpmeta>
        """.trimIndent()
    }
}
