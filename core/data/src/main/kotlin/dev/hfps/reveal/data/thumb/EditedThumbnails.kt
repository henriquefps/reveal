package dev.hfps.reveal.data.thumb

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.data.mask.MaskInputs
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.heal.RemoveFillStore
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.nio.ByteBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Renders a small image of a recipe (the grid's "edited" thumbnail). */
fun interface EditedThumbnails {
    /** The edit, its Remove spots filled (docs/adr/0023 "FIX/H": from the fills' store, else made at this size). */
    suspend fun render(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap

    /**
     * [render] without waiting for Remove fills that are not ready (the grid's first thumbnail; the complete one
     * follows, docs/adr/0023 "FIX/H").
     */
    suspend fun renderQuick(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap = render(input, recipe, longEdge)

    /**
     * The same from an input already decoded (a hand-off's local smart preview, ADR 0011); [fullLongEdge] is the
     * original's long edge (Detail's lengths). Null when this renderer cannot.
     */
    suspend fun renderPreview(input: PipelineInput, fullLongEdge: Int, recipe: Recipe, longEdge: Int): Bitmap? = null

    /**
     * [render] with the AI mask parts of [recipe] taken from [masks] (docs/adr/0022 "M4/F"; [photoKey] is the masks'
     * cache key of the photo). Without a renderer that knows masks it is the plain render.
     */
    suspend fun renderWithMasks(
        input: ImageInput,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
    ): Bitmap = render(input, recipe, longEdge)
}

/**
 * [EditedThumbnails] through the real develop pipeline: a proxy decode at thumbnail size (RAW: half-size LibRaw
 * decode, area-averaged), mapped like the editor's input, uploaded to one shared [GpuDevelopRenderer] and read back
 * through the output transform (`renderOutputImage`, sRGB RGBA8, at the geometry's output size). The same recipe, the
 * same shaders as the editor, so the thumbnail is the edit. One render at a time; the renderer is closed after
 * [IDLE_MS] without work.
 *
 * Healing (docs/adr/0023 "FIX/H"): spots are computed at the thumbnail's size; Remove spots take the editor's fill
 * from [fillStore] (keyed by the file's fingerprint and the crop on the original), else a patch fill made on the
 * thumbnail itself (fast at this size; an AI spot shows the patch fill until the editor has made its own).
 */
class GpuEditedThumbnails(private val scope: CoroutineScope, fillStore: RemoveFillStore? = null) : EditedThumbnails {
    private val lock = Mutex()
    private var renderer: GpuDevelopRenderer? = null
    private var idle: Job? = null
    private var withMasks = false

    /** Fills of the thumbnails: the store's (read only), else the patch fill on the thumbnail (never a model here). */
    private val fills = RemoveFills(storeWrites = false).also { it.store = fillStore }

    override suspend fun render(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap =
        renderWith(input, recipe, longEdge, AiMaskSource.NONE, "")

    override suspend fun renderQuick(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap =
        renderWith(input, recipe, longEdge, AiMaskSource.NONE, "", waitForFills = false)

    override suspend fun renderWithMasks(
        input: ImageInput,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
    ): Bitmap = renderWith(input, recipe, longEdge, masks, photoKey)

    @Suppress("LongParameterList")
    private suspend fun renderWith(
        input: ImageInput,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
        waitForFills: Boolean = true,
    ): Bitmap = lock.withLock {
        idle?.cancel()
        val orientation = if (input.type.isRaw) {
            null
        } else {
            runCatching {
                input.inspection.metadata.orientation
            }.getOrNull()
        }
        val proxy = ProxyBuilder.decodeProxy(input, longEdge)
        val metadata = runCatching { input.inspection.metadata }.getOrNull()
        val pipelineInput = PipelineInputMapper.map(
            proxy.image,
            proxy.info,
            orientation ?: proxy.info.orientation,
            metadata,
        )
        // Remove's crops are defined on the original (upright), so the store finds the editor's fills by its keys.
        val full = proxy.stats.fullWidth to proxy.stats.fullHeight
        val upright = if ((pipelineInput.width >= pipelineInput.height) == (full.first >= full.second)) {
            full
        } else {
            full.second to full.first
        }
        val heals = HealSetup(
            fileKey = if (removes(recipe)) photoKey.ifEmpty { null } ?: MaskInputs.photoKey(input) else null,
            originalSize = upright,
            wait = waitForFills,
        )
        // Detail's lengths are full-resolution pixels: a thumbnail is developed at its own scale (ADR 0012).
        draw(pipelineInput, maxOf(full.first, full.second), recipe, masks, photoKey, heals)
    }

    /** How a render resolves its Remove spots: the file's fingerprint (store keys), the original's size, waiting. */
    private class HealSetup(val fileKey: String?, val originalSize: Pair<Int, Int>?, val wait: Boolean)

    private fun removes(recipe: Recipe): Boolean = recipe.heals.any { it.renders && it.type == HealKind.REMOVE }

    override suspend fun renderPreview(input: PipelineInput, fullLongEdge: Int, recipe: Recipe, longEdge: Int): Bitmap =
        lock.withLock {
            idle?.cancel()
            val bitmap = draw(input, fullLongEdge, recipe)
            val long = maxOf(bitmap.width, bitmap.height)
            if (long <= longEdge) return@withLock bitmap
            val scale = longEdge.toFloat() / long
            bitmap.scale(
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
            ).also { if (it !== bitmap) bitmap.recycle() }
        }

    /** Develops [input] with [recipe] on the shared renderer (called under [lock]). */
    @Suppress("LongParameterList")
    private suspend fun draw(
        input: PipelineInput,
        fullLongEdge: Int,
        recipe: Recipe,
        masks: AiMaskSource = AiMaskSource.NONE,
        photoKey: String = "",
        heals: HealSetup = HealSetup(null, null, wait = true),
    ): Bitmap {
        val gpu = renderer ?: GpuDevelopRenderer.create().also { renderer = it }
        val scale = (fullLongEdge.toFloat() / maxOf(input.width, input.height)).coerceAtLeast(1f)
        gpu.upload(input, scale)
        // The renderer is shared: every render sets its own source (NONE = AI parts skipped), never the last one's.
        if (masks !== AiMaskSource.NONE || withMasks) gpu.setMaskSource(masks, photoKey)
        withMasks = masks !== AiMaskSource.NONE
        // Healing (docs/adr/0023 "FIX/H"): this photo's Remove fills (the store's, else made on the thumbnail).
        gpu.setHealSource(fills, heals.fileKey, heals.originalSize)
        if (heals.wait && removes(recipe)) gpu.awaitHealFills(recipe, FILL_WAIT_MS)
        // The output's size is the geometry's (a crop, a turn, a transform), not the input's.
        val (width, height, rgba) = gpu.renderOutputImage(recipe)
        val bitmap = createBitmap(width, height)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock {
                renderer?.close()
                renderer = null
                withMasks = false
            }
        }
        return bitmap
    }

    private companion object {
        const val IDLE_MS = 5_000L

        /** Longest a thumbnail waits for its Remove fills (patch fills at thumbnail size take milliseconds). */
        const val FILL_WAIT_MS = 20_000L
    }
}
