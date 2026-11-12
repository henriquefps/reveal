package dev.hfps.reveal.feature.editor.presets

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.createBitmap
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Renders a recipe on the open photo's small preview copy (the presets' live thumbnails). */
interface PresetThumbnailSource {
    /** The thumbnail already rendered under [key], if any (no work). */
    fun cached(key: String): ImageBitmap? = null

    /** The thumbnail of [recipe] (cached under [key]), or null when it cannot be rendered. Suspends until ready. */
    suspend fun render(key: String, recipe: Recipe): ImageBitmap?
}

/**
 * The presets panel's live previews (docs/adr/0019 "Previews"): one small offscreen [GpuDevelopRenderer] (its own GL
 * context and thread, nothing shared with the editor's surface) holding [input], a [PreviewInput] copy of the
 * editor's proxy. Requests are served **newest first** (the cells just scrolled into view), a cell that leaves the
 * screen cancels its request, and bitmaps are kept in a memory LRU by key (photo, base recipe, preset). The renderer
 * is created on the first request and closed by [close].
 */
class PresetThumbnails(private val input: PipelineInput, private val pixelScale: Float) :
    PresetThumbnailSource,
    AutoCloseable {
    private class Request(val key: String, val recipe: Recipe) {
        val result = CompletableDeferred<ImageBitmap?>()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pending = ArrayList<Request>()
    private val signal = Channel<Unit>(Channel.CONFLATED)
    private val cache = object : LruCache<String, ImageBitmap>(CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * BYTES_PER_PIXEL
    }

    @Volatile
    private var renderer: GpuDevelopRenderer? = null

    @Volatile
    private var closed = false

    /** Milliseconds of the last renders (newest last), for the panel measurement. */
    val renderTimes: MutableList<Long> = java.util.Collections.synchronizedList(ArrayList())

    init {
        scope.launch { serve() }
    }

    override fun cached(key: String): ImageBitmap? = cache.get(key)

    @Suppress("ReturnCount")
    override suspend fun render(key: String, recipe: Recipe): ImageBitmap? {
        cache.get(key)?.let { return it }
        if (closed) return null
        val request = Request(key, recipe)
        synchronized(pending) { pending += request }
        signal.trySend(Unit)
        try {
            return request.result.await()
        } finally {
            synchronized(pending) { pending -= request }
        }
    }

    private suspend fun serve() {
        for (ignored in signal) {
            while (!closed) {
                val next = synchronized(pending) { pending.lastOrNull() } ?: break
                val bitmap = cache.get(next.key) ?: renderNow(next.recipe)?.also { cache.put(next.key, it) }
                synchronized(pending) {
                    val done = pending.filter { it.key == next.key }
                    pending.removeAll(done.toSet())
                    done.forEach { it.result.complete(bitmap) }
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun renderNow(recipe: Recipe): ImageBitmap? = try {
        val start = SystemClock.elapsedRealtime()
        val gpu = renderer ?: GpuDevelopRenderer.create().also {
            it.upload(input, pixelScale)
            renderer = it
        }
        val rgba = gpu.renderOutput(recipe)
        val mapping = CpuDevelop.mapping(input, recipe)
        val bitmap = createBitmap(mapping.outputWidth, mapping.outputHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        val ms = SystemClock.elapsedRealtime() - start
        renderTimes += ms
        if (renderTimes.size > MAX_TIMES) renderTimes.removeAt(0)
        Log.d(TAG, "thumbnail ${mapping.outputWidth}x${mapping.outputHeight} in $ms ms")
        bitmap.asImageBitmap()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // A GPU failure leaves the cell with its placeholder; the editor itself is unaffected.
        Log.w(TAG, "thumbnail failed: $e")
        null
    }

    override fun close() {
        closed = true
        signal.close()
        synchronized(pending) {
            pending.forEach { it.result.complete(null) }
            pending.clear()
        }
        scope.cancel()
        renderer?.close()
        renderer = null
        cache.evictAll()
    }

    companion object {
        const val TAG: String = "REVEAL_PRESETS"
        private const val CACHE_BYTES = 40 * 1024 * 1024
        private const val BYTES_PER_PIXEL = 4
        private const val MAX_TIMES = 500

        /** The cache key of [presetId] (at [presetVersion]) applied on [base] of the photo [photoKey]. */
        fun key(
            photoKey: String,
            baseHash: Int,
            presetId: String,
            presetVersion: Int,
            patchHash: Int,
            strength: Float = 1f,
        ): String = "$photoKey|$baseHash|$presetId|$presetVersion|$patchHash|$strength"
    }
}
