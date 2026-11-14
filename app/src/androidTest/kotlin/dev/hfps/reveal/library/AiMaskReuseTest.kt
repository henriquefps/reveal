package dev.hfps.reveal.library

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.image.ImageInput
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.mask.MaskInputs
import dev.hfps.reveal.data.storage.MaskCacheArea
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.editor.TestPhotos
import dev.hfps.reveal.feature.editor.presets.PresetThumbnails
import dev.hfps.reveal.feature.export.engine.ExportEngine
import dev.hfps.reveal.feature.export.engine.ExportItem
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M4/F (docs/adr/0022 "M4/F"): AI masks are made once per photo and shared by the editor, the library's edited
 * thumbnails and the export, through the masks' disk cache keyed by the file's fingerprint. Runs the real models (the
 * phone); public-domain photos only (the portrait in `assets/masks`, the CC0 ZV-E10 RAW in `files/samples`).
 */
@RunWith(AndroidJUnit4::class)
class AiMaskReuseTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val cacheDir = File(context.cacheDir, MaskCacheArea.DIR)
    private var folder: SyntheticFolder? = null
    private val inserted = mutableListOf<android.net.Uri>()

    @After
    fun tearDown() {
        folder?.delete()
        inserted.forEach(TestPhotos::delete)
    }

    private fun person(id: String, kind: MaskKind, stops: Float) = Mask(
        id = id,
        name = id,
        adjustments = LocalAdjustments(exposure = stops),
        components = listOf(MaskComponent.Ai(model = MaskService.modelFor(kind).key, kind = kind.name)),
    )

    private fun entries() = cacheDir.listFiles { f -> f.isFile }?.size ?: 0

    private fun mean(bitmap: Bitmap): Double {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.sumOf { ((it shr 16 and 0xFF) + (it shr 8 and 0xFF) + (it and 0xFF)) / 3.0 } / pixels.size
    }

    @Test
    fun aGridThumbnailGetsItsPeopleMaskWhenItIsReady() {
        val portrait = InstrumentationRegistry.getInstrumentation().context.assets.open("masks/studio-portrait.jpg")
            .use { it.readBytes() }
        val shoot = SyntheticFolder("ai-thumb-${System.nanoTime()}").also { folder = it }
        shoot.jpeg("portrait.jpg", 1, base = TestPhotos.uniqueContent(portrait))
        val (sourceId, _) = shoot.addAndIndex()
        val db = access.database()
        val (photoId, versionId) = runBlocking {
            val id = db.photoDao().indexedPhotos(sourceId).single().id
            id to checkNotNull(db.photoDao().firstVersion(id))
        }
        // The person is darkened by three stops: the thumbnail without the mask is the plain photo.
        runBlocking {
            access.edits().applyEach(listOf(versionId), HistoryAction.PRESET, "ai thumb") {
                it.copy(masks = listOf(person("people", MaskKind.PEOPLE, -3f)))
            }
        }
        cacheDir.deleteRecursively()
        val thumbnails = access.thumbnails()
        val editTime = System.currentTimeMillis()

        val plain = checkNotNull(runBlocking { thumbnails.thumbnail(photoId, versionId, editTime) })
        // The grid is never blocked: the first thumbnail is there before any model ran.
        assertThat(entries()).isEqualTo(0)

        val started = System.nanoTime()
        val upgraded = checkNotNull(
            runBlocking { withTimeout(TIMEOUT_MS) { thumbnails.withAiMasks(photoId, versionId, editTime) } },
        )
        Log.i(TAG, "thumbnail masks ${(System.nanoTime() - started) / MS} ms, ${entries()} cache entries")
        assertThat(upgraded.width).isEqualTo(plain.width)
        assertThat(mean(upgraded)).isLessThan(mean(plain) - 2.0)
        assertThat(entries()).isAtLeast(1)
        assertThat(thumbnails.inMemory(photoId, editTime)).isSameInstanceAs(upgraded)

        // Once: nothing more to add, and no new model run.
        val files = entries()
        assertThat(runBlocking { thumbnails.withAiMasks(photoId, versionId, editTime) }).isNull()
        assertThat(entries()).isEqualTo(files)
    }

    @Test
    fun aPresetPreviewShowsThePeopleMaskOnceItIsReady() {
        val portrait = InstrumentationRegistry.getInstrumentation().context.assets.open("masks/studio-portrait.jpg")
            .use { it.readBytes() }
        val uri = TestPhotos.insert("ai-preview.jpg", "image/jpeg", portrait).also { inserted += it }
        val recipe = Recipe(masks = listOf(person("people", MaskKind.PEOPLE, -3f)))
        cacheDir.deleteRecursively()
        ImageInput.fromUri(context.contentResolver, uri, "ai-preview.jpg").use { image ->
            val key = checkNotNull(MaskInputs.photoKey(image))
            val small = checkNotNull(MaskInputs.smallInput(image))
            val session = access.aiMasks().session(key, small.width to small.height) { MaskInputs.render(small) }
            val previews = PresetThumbnails(small, 1f)
            try {
                previews.useMasks(session)
                // The mask is not there yet: the preview is the photo (the AI part is skipped, never waited for).
                val plain = runBlocking { previews.render("plain", recipe) }!!.asAndroidBitmap()
                assertThat(runBlocking { withTimeout(TIMEOUT_MS) { session.ready(components(recipe)) } }).isTrue()
                // Its cache key carries which masks are ready, so the panel asks again: now the person is darker.
                val masked = runBlocking { previews.render("masked", recipe) }!!.asAndroidBitmap()
                assertThat(mean(masked)).isLessThan(mean(plain) - 2.0)
            } finally {
                previews.close()
                session.close()
            }
        }
    }

    private fun components(recipe: Recipe) = recipe.masks.flatMap { it.components.filterIsInstance<MaskComponent.Ai>() }

    @Test
    fun anExportFindsTheMasksAnotherMadeAndComputesOnlyWhatIsMissing() {
        val sample = TestPhotos.sample(ARW)
        assumeTrue("push $ARW into files/samples (CLAUDE.md)", sample != null)
        val uri = TestPhotos.insert(ARW, "image/x-sony-arw", checkNotNull(sample)).also { inserted += it }
        val recipe = Recipe(masks = listOf(person("sky", MaskKind.SKY, -1f), person("subject", MaskKind.SUBJECT, 1f)))
        val components = components(recipe)
        val renderer = TiledRenderer.create()
        val engine = ExportEngine(context, aiMasks = access.aiMasks())
        val options = ExportOptions(quality = 85, destination = ExportDestination.Pictures)
        try {
            fun export(): Long {
                // The models are freed when the editor closes: an export starts without them loaded.
                runBlocking { access.aiMasks().release() }
                val started = System.nanoTime()
                val result = engine.export(
                    ExportItem(uri, ARW, recipe),
                    options,
                    Authorship("Ana Example", "© 2026 Ana Example"),
                    renderer,
                    cancel = { false },
                )
                runCatching { context.contentResolver.delete(result.uri, null, null) }
                return (System.nanoTime() - started) / MS
            }

            // The editor's side: its proxy is bigger than the export's, the file is the same.
            cacheDir.deleteRecursively()
            runBlocking {
                ImageInput.fromUri(context.contentResolver, uri, ARW).use { image ->
                    val key = checkNotNull(MaskInputs.photoKey(image))
                    val small = checkNotNull(MaskInputs.smallInput(image, EDITOR_PROXY))
                    val session = access.aiMasks()
                        .session(key, small.width to small.height) { MaskInputs.render(small) }
                    assertThat(withTimeout(TIMEOUT_MS) { session.ready(components) }).isTrue()
                    session.close()
                }
            }
            val made = entries()
            assertThat(made).isAtLeast(components.size)

            val warm = export()
            // Nothing was made for the export: every mask came from the cache.
            assertThat(entries()).isEqualTo(made)

            cacheDir.deleteRecursively()
            val cold = export()
            assertThat(entries()).isEqualTo(made)
            Log.i(TAG, "export with sky + subject masks: cache warm $warm ms, cold $cold ms ($made masks)")
            assertThat(warm).isLessThan(cold)
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_MASKS_REUSE"
        const val ARW = "sony-zv-e10.arw"
        const val EDITOR_PROXY = 3000
        const val TIMEOUT_MS = 180_000L
        const val MS = 1_000_000L
    }
}
