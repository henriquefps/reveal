package dev.hfps.reveal.data.thumb

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.mask.AiMaskSource
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
    suspend fun render(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap

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
 * through the output transform (`renderOutput`, sRGB RGBA8). The same recipe, the same shaders as the editor, so the
 * thumbnail is the edit. One render at a time; the renderer is closed after [IDLE_MS] without work.
 */
class GpuEditedThumbnails(private val scope: CoroutineScope) : EditedThumbnails {
    private val lock = Mutex()
    private var renderer: GpuDevelopRenderer? = null
    private var idle: Job? = null
    private var withMasks = false

    override suspend fun render(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap =
        renderWith(input, recipe, longEdge, AiMaskSource.NONE, "")

    override suspend fun renderWithMasks(
        input: ImageInput,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
    ): Bitmap = renderWith(input, recipe, longEdge, masks, photoKey)

    private suspend fun renderWith(
        input: ImageInput,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
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
        // Detail's lengths are full-resolution pixels: a thumbnail is developed at its own scale (ADR 0012).
        draw(pipelineInput, maxOf(proxy.stats.fullWidth, proxy.stats.fullHeight), recipe, masks, photoKey)
    }

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
    private suspend fun draw(
        input: PipelineInput,
        fullLongEdge: Int,
        recipe: Recipe,
        masks: AiMaskSource = AiMaskSource.NONE,
        photoKey: String = "",
    ): Bitmap {
        val gpu = renderer ?: GpuDevelopRenderer.create().also { renderer = it }
        val scale = (fullLongEdge.toFloat() / maxOf(input.width, input.height)).coerceAtLeast(1f)
        gpu.upload(input, scale)
        // The renderer is shared: every render sets its own source (NONE = AI parts skipped), never the last one's.
        if (masks !== AiMaskSource.NONE || withMasks) gpu.setMaskSource(masks, photoKey)
        withMasks = masks !== AiMaskSource.NONE
        val rgba = gpu.renderOutput(recipe)
        val bitmap = createBitmap(input.width, input.height)
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
    }
}
