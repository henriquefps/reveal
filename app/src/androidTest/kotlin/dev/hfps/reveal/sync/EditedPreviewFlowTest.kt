package dev.hfps.reveal.sync

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.library.SyntheticFolder
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.engine.EditPreviews
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Edited previews end to end (Hoard ADR 0020, Reveal ADR 0027) against a local hoard-server 0.9.0
 * (`scripts/sync/local-server.sh`, `adb reverse`), through the app's own singletons and its GPU renderer (run it on a
 * real device): a photo edited in Reveal (+2 EV) backs up its original, the server indexes it as an item, and the next
 * blob pass renders and uploads the edit; the server's lookup then lists it in `edits` (primary) and the item's
 * `preview?edit=primary` is the edit's look (much brighter than the camera rendering). `-e syncLink <link>`; skipped
 * without it. Logs `REVEAL_EDITS` (timings).
 */
@RunWith(AndroidJUnit4::class)
class EditedPreviewFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val link = InstrumentationRegistry.getArguments().getString("syncLink")
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val run = System.nanoTime() % 1_000_000_000
    private val folder = SyntheticFolder("edits-$run")

    @Before
    fun setUp() {
        assumeTrue("needs -e syncLink <link>", link != null)
        runBlocking { access.sync().unpair() }
    }

    @After
    fun tearDown() {
        runBlocking { access.sync().unpair() }
        folder.delete()
    }

    @Test
    fun anEditGoesUpAndTheServerShowsItsLook() {
        val sync = access.sync()
        runBlocking { sync.pair(checkNotNull(PairingLink.parse(link!!))) }
        sync.setOptions(sync.options.value.copy(originals = true, originalsWifiOnly = false, editedPreviews = true))
        val name = "EDIT_$run.JPG"
        folder.jpeg(name, 21, base = folder.gradientJpeg(21, 1600, 1066) + "edits-$run".toByteArray())
        val original = File(folder.dir, name).readBytes()
        val fingerprint = FileFingerprint.of(original)
        folder.addAndIndex()
        val photos = access.database().photoDao()
        var photoId: Long? = null
        waitUntil(TIMEOUT_MS) {
            photoId = runBlocking { photos.filesByFingerprint(listOf(fingerprint)) }.firstOrNull()?.file?.photoId
            photoId != null
        }
        val version = checkNotNull(runBlocking { photos.firstVersion(photoId!!) })
        val bright = Recipe(light = Light(exposureEv = 2f))
        runBlocking { access.edits().commit(version, bright, RecipeDiff.between(Recipe(), bright)) }
        // The edit settles: its time is pushed back past the settle delay (a test cannot wait a minute per step).
        access.database().openHelper.writableDatabase.execSQL(
            "UPDATE recipes SET updated_at = updated_at - ? WHERE version_id = ?",
            arrayOf<Any>(EditPreviews.SETTLE_MS * 2, version),
        )

        val start = System.currentTimeMillis()
        val api = checkNotNull(sync.api())
        var itemId: String? = null
        waitUntil(UPLOAD_MS) {
            runBlocking {
                sync.syncMetadata()
                sync.syncBlobs(originalsAllowed = true, unmetered = true)
            }
            val answer = api.itemsLookup(listOf(fingerprint))
            val item = answer.items.firstOrNull()
            itemId = item?.id
            item?.edits?.any { it.primary } == true
        }
        val uploaded = System.currentTimeMillis() - start
        val edit = api.itemsLookup(listOf(fingerprint)).items.single().edits.single()
        assertThat(edit.recipeDigest).startsWith("r")
        assertThat(edit.isVirtualCopy).isFalse()

        var preview: ByteArray? = null
        waitUntil(UPLOAD_MS) {
            preview = api.itemPreview(itemId!!, "primary")
            preview != null
        }
        val shown = mean(BitmapFactory.decodeByteArray(preview, 0, preview!!.size))
        val camera = mean(BitmapFactory.decodeByteArray(original, 0, original.size))
        Log.i(TAG, "edit uploaded in ${uploaded}ms; mean luma: camera $camera, server's edit preview $shown")
        // +2 EV: Reveal's look, not the camera rendering's.
        assertThat(shown).isGreaterThan(camera + 30)
    }

    private fun mean(bitmap: Bitmap): Double {
        var sum = 0.0
        var n = 0
        val step = 8
        for (y in 0 until bitmap.height step step) {
            for (x in 0 until bitmap.width step step) {
                val c = bitmap.getPixel(x, y)
                sum += 0.2126 * (c shr 16 and 0xff) + 0.7152 * (c shr 8 and 0xff) + 0.0722 * (c and 0xff)
                n++
            }
        }
        return sum / n
    }

    private fun waitUntil(timeoutMs: Long, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (runCatching(condition).onFailure { Log.w(TAG, "waiting: $it") }.getOrDefault(false)) return
            Thread.sleep(POLL_MS)
        }
        error("timed out after $timeoutMs ms")
    }

    private companion object {
        const val TAG = "REVEAL_EDITS"
        const val TIMEOUT_MS = 30_000L
        const val UPLOAD_MS = 120_000L
        const val POLL_MS = 1_000L
    }
}
