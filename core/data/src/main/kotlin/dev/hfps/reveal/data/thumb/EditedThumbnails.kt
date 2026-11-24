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

/**
 * Renders a small image of a recipe (the grid's "edited" thumbnail, the viewer's developed image) in two steps, so a
 * document from another app is open (holds a `FileOpens` slot, ADR 0018 "W3/C") for the read only: [decode] opens,
 * decodes and closes the file; [render] develops the decoded pixels (GPU, its queue, AI masks, Remove fills) with no
 * file open.
 */
interface EditedThumbnails {
    /**
     * A photo decoded for [render]: the proxy mapped like the editor's input, the original's long edge (Detail's
     * lengths), its upright size (Remove's crops) and, when the recipe has Remove spots, the file's fingerprint (the
     * fills' store keys). The file itself is closed.
     */
    class Decoded(
        val input: PipelineInput,
        val fullLongEdge: Int,
        val originalSize: Pair<Int, Int>?,
        val fileKey: String?,
    )

    /**
     * Opens the file with [open], decodes it at [longEdge] for [recipe] and closes it before returning, also when the
     * decode fails. [alongside] reads the same open input first (the AI masks' small input and key), so the file is
     * opened once. Blocking work: call it on an IO dispatcher.
     */
    suspend fun decode(
        open: () -> ImageInput,
        recipe: Recipe,
        longEdge: Int,
        alongside: (ImageInput) -> Unit = {},
    ): Decoded

    /**
     * The edit of [decoded], its Remove spots filled (docs/adr/0023 "FIX/H": from the fills' store, else made at this
     * size) unless [waitForFills] is false (the grid's first thumbnail; the complete one follows), with the AI mask
     * parts of [recipe] taken from [masks] (docs/adr/0022 "M4/F"; [photoKey] is the masks' cache key of the photo).
     */
    @Suppress("LongParameterList")
    suspend fun render(
        decoded: Decoded,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource = AiMaskSource.NONE,
        photoKey: String = "",
        waitForFills: Boolean = true,
    ): Bitmap

    /**
     * The same from an input already decoded (a hand-off's local smart preview, ADR 0011); [fullLongEdge] is the
     * original's long edge (Detail's lengths). Null when this renderer cannot.
     */
    suspend fun renderPreview(input: PipelineInput, fullLongEdge: Int, recipe: Recipe, longEdge: Int): Bitmap? = null

    /** [decode] then [render]: the file is closed before the render starts. */
    suspend fun renderFile(
        open: () -> ImageInput,
        recipe: Recipe,
        longEdge: Int,
        waitForFills: Boolean = true,
    ): Bitmap = render(decode(open, recipe, longEdge), recipe, longEdge, waitForFills = waitForFills)
}

/**
 * [EditedThumbnails] through the real develop pipeline: a proxy decode at thumbnail size (RAW: half-size LibRaw
 * decode, area-averaged), mapped like the editor's input, uploaded to one shared [GpuDevelopRenderer] and read back
 * through the output transform (`renderOutputImage`, sRGB RGBA8, at the geometry's output size). The same recipe, the
 * same shaders as the editor, so the thumbnail is the edit. One decode or render at a time; the file is open for the
 * decode only (closed before the GPU work); the renderer is closed after [IDLE_MS] without work.
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

    /**
     * Decodes under the renderer's [lock] (one proxy on the heap at a time, as before the split), opening the file
     * only once the lock is held: the lock is always taken before a FileOpens slot, never the other way round, so a
     * caller never holds a file while it queues.
     */
    override suspend fun decode(
        open: () -> ImageInput,
        recipe: Recipe,
        longEdge: Int,
        alongside: (ImageInput) -> Unit,
    ): EditedThumbnails.Decoded = lock.withLock { decodeLocked(open, recipe, longEdge, alongside) }

    /** [decode] and [render] in one turn of the [lock]: the file is closed once decoded, before the GPU work. */
    override suspend fun renderFile(
        open: () -> ImageInput,
        recipe: Recipe,
        longEdge: Int,
        waitForFills: Boolean,
    ): Bitmap = lock.withLock {
        renderLocked(decodeLocked(open, recipe, longEdge) {}, recipe, AiMaskSource.NONE, "", waitForFills)
    }

    private fun decodeLocked(
        open: () -> ImageInput,
        recipe: Recipe,
        longEdge: Int,
        alongside: (ImageInput) -> Unit,
    ): EditedThumbnails.Decoded {
        idle?.cancel()
        return open().use { input ->
            alongside(input)
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
            EditedThumbnails.Decoded(
                input = pipelineInput,
                fullLongEdge = maxOf(full.first, full.second),
                originalSize = upright,
                fileKey = if (removes(recipe)) MaskInputs.photoKey(input) else null,
            )
        }
    }

    @Suppress("LongParameterList")
    override suspend fun render(
        decoded: EditedThumbnails.Decoded,
        recipe: Recipe,
        longEdge: Int,
        masks: AiMaskSource,
        photoKey: String,
        waitForFills: Boolean,
    ): Bitmap = lock.withLock { renderLocked(decoded, recipe, masks, photoKey, waitForFills) }

    private suspend fun renderLocked(
        decoded: EditedThumbnails.Decoded,
        recipe: Recipe,
        masks: AiMaskSource,
        photoKey: String,
        waitForFills: Boolean,
    ): Bitmap {
        idle?.cancel()
        val heals = HealSetup(
            fileKey = if (removes(recipe)) photoKey.ifEmpty { null } ?: decoded.fileKey else null,
            originalSize = decoded.originalSize,
            wait = waitForFills,
        )
        // Detail's lengths are full-resolution pixels: a thumbnail is developed at its own scale (ADR 0012).
        return draw(decoded.input, decoded.fullLongEdge, recipe, masks, photoKey, heals)
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
