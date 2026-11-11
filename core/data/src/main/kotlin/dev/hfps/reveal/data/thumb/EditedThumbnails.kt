package dev.hfps.reveal.data.thumb

import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
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

    override suspend fun render(input: ImageInput, recipe: Recipe, longEdge: Int): Bitmap = lock.withLock {
        idle?.cancel()
        val orientation = if (input.type.isRaw) {
            null
        } else {
            runCatching {
                input.inspection.metadata.orientation
            }.getOrNull()
        }
        val proxy = ProxyBuilder.decodeProxy(input, longEdge)
        val pipelineInput = PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation)
        val gpu = renderer ?: GpuDevelopRenderer.create().also { renderer = it }
        // Detail's lengths are full-resolution pixels: a thumbnail is developed at its own scale (ADR 0012).
        val full = maxOf(proxy.stats.fullWidth, proxy.stats.fullHeight).toFloat()
        val scale = (full / maxOf(pipelineInput.width, pipelineInput.height)).coerceAtLeast(1f)
        gpu.upload(pipelineInput, scale)
        val rgba = gpu.renderOutput(recipe)
        val bitmap = createBitmap(pipelineInput.width, pipelineInput.height)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        idle = scope.launch {
            delay(IDLE_MS)
            lock.withLock {
                renderer?.close()
                renderer = null
            }
        }
        bitmap
    }

    private companion object {
        const val IDLE_MS = 5_000L
    }
}
