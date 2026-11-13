@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.EGLContext
import android.opengl.GLES30
import android.os.Handler
import android.util.Log
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.zoom.ZoomGrid
import dev.hfps.reveal.pipeline.zoom.ZoomView
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.roundToInt

/** What the zoom has done so far (tests, logs and the ADR's measurements). */
data class ZoomStats(
    /** Tiles developed since the source was set. */
    val tiles: Int = 0,
    /** Milliseconds of the last tile (upload, develop, Detail, copy into the atlas). */
    val lastTileMs: Long = 0,
    /** Mean milliseconds per tile. */
    val meanTileMs: Float = 0f,
    /** Milliseconds of the last grid pre-pass (0 when the grids were still valid). */
    val lastPrepareMs: Long = 0,
    /** Tiles of the current edit shown (or fading in) in the last frame. */
    val shown: Int = 0,
    /** Tiles the last frame wanted (visible, current edit). */
    val wanted: Int = 0,
    /** Milliseconds from the edit settling (or the first zoomed frame) to the last wanted tile being ready. */
    val lastSettleMs: Long = 0,
    /** True when the full-resolution source is set. */
    val hasSource: Boolean = false,
)

/** A pane the zoom may draw tiles into: its view, the window it is drawn in, and its clip. */
internal data class TilePane(val view: ViewTransform, val windowWidth: Int, val windowHeight: Int, val clip: ViewFrame?)

/** The uniforms of the output pass's tile path for one pane (`Shaders.OUTPUT_FRAG`). */
@Suppress("LongParameterList")
internal class TileUniforms(
    val k: Float,
    val fullOutputWidth: Int,
    val fullOutputHeight: Int,
    val proxyOutputWidth: Int,
    val proxyOutputHeight: Int,
    val full: GeometryMapping,
    val imageWidth: Int,
    val imageHeight: Int,
    val atlasWidth: Int,
    val atlasHeight: Int,
    val indexWidth: Int,
    val indexHeight: Int,
    val mode: Int,
    val snap: Boolean,
    val lod: Float,
    val nearest: Boolean,
)

/**
 * Full-resolution detail for the editor's zoom (docs/adr/0012), on the editor renderer's GL thread.
 *
 * The full-resolution [TileSource] (decoded once in the background after open) is developed in tiles of
 * [ZoomGrid.TILE] px by a [TiledRenderer] whose context shares this one's textures (its own thread: a tile never
 * blocks a frame), with the export's code: the full-resolution Light grids built once per scene
 * ([TiledRenderer.prepareZoom]),
 * the same scene/develop/Detail shaders at scale 1. Each tile goes into a slot of an RGBA16F **atlas** (two levels:
 * the 2x2 means serve views between 50 % and 100 %), found through a small **index** texture (one RGBA8 texel per
 * tile: slot, fade-in alpha, ready). The output pass reads the proxy as before and, where the index has a tile of the
 * current edit, the atlas instead.
 *
 * Tiles are developed only for the **settled** edit: while the recipe keeps changing (a slider drag) the proxy shows
 * alone, [IDLE_MS] after the last change the visible tiles are developed nearest to the centre first, one at a time,
 * and fade in over [FADE_MS]. Slots are reused least-recently-shown first; before a worker overwrites a slot it waits
 * on a fence placed after this context's last frame.
 *
 * Memory (docs/adr/0012, "Memory"): the atlas holds [SLOTS_X] x [SLOTS_Y] slots (134 MB at RGBA16F with level 1), the
 * worker's tile textures about 60 MB at its 1024 px tile, its grids about 20 MB at 24 MP; the source is the caller's
 * (LibRaw's 16-bit buffer, 6 bytes per pixel).
 */
@Suppress("TooManyFunctions")
internal class ZoomTiles(
    private val handler: Handler,
    private val requestFrame: () -> Unit,
    private val shareContext: () -> EGLContext,
) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "reveal-zoom") }

    @Volatile
    private var worker: TiledRenderer? = null
    private var source: TileSource? = null
    private var grid: ZoomGrid? = null
    private var k = 1f
    private var atlas = 0
    private var index = 0
    private var indexBytes = ByteArray(0)
    private var indexShown = ByteArray(0)
    private var slotTile = IntArray(SLOTS) { NO_TILE }
    private var slotUsed = LongArray(SLOTS)
    private val tiles = HashMap<Int, Tile>()
    private var generation = 0
    private var genParams: DevelopParams? = null
    private var genRecipe: Recipe = Recipe.DEFAULT
    private var genChangedAt = 0L
    private var settleStart = 0L
    private var inFlight = false
    private var frameNo = 0L
    private var released = false
    private var wanted: List<Int> = emptyList()
    private val waiters = ArrayList<() -> Boolean>()

    /** Where the tiles' AI masks come from (docs/adr/0022); set by the renderer. */
    var maskContext: MaskContext = MaskContext.NONE

    /** Bumped when an AI mask arrives: the tiles develop again. */
    var maskEpoch: Int = 0
    private var genEpoch = 0

    /** Pane used for scheduling when there is no window (offscreen tests and benchmarks). */
    var testPane: TilePane? = null

    @Volatile
    var stats: ZoomStats = ZoomStats()
        private set

    private var tileMsTotal = 0L

    private class Tile(val slot: Int, var generation: Int, var readyAt: Long, var ready: Boolean)

    /** True when a full-resolution source is set. */
    val active: Boolean get() = source != null && atlas != 0

    /**
     * Sets the full-resolution [source] of the photo whose proxy is [k] times smaller (takes ownership: closed on
     * replacement or [release]); `null` drops it.
     */
    fun setSource(newSource: TileSource?, k: Float) {
        val old = source
        clearTiles()
        source = newSource
        this.k = k
        grid = newSource?.let { ZoomGrid(it.width, it.height) }
        genParams = null
        executor.execute {
            old?.close()
            if (newSource != null && worker == null) {
                worker = runCatching { TiledRenderer.create(WORKER_TILE, shareContext()) }
                    .onFailure { Log.w(TAG, "zoom worker unavailable", it) }
                    .getOrNull()
            }
            handler.post { if (!released) dispatch(System.nanoTime()) }
        }
        if (newSource != null) allocate(checkNotNull(grid))
        stats = ZoomStats(hasSource = newSource != null)
        requestFrame()
    }

    private fun allocate(g: ZoomGrid) {
        if (atlas == 0) {
            val ids = IntArray(1)
            GLES30.glGenTextures(1, ids, 0)
            atlas = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 2, GLES30.GL_RGBA16F, ATLAS_WIDTH, ATLAS_HEIGHT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAX_LEVEL, 1)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GlSupport.checkError("zoom atlas")
        }
        if (index != 0) GLES30.glDeleteTextures(1, intArrayOf(index), 0)
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        index = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, index)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, g.columns, g.rows)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        indexBytes = ByteArray(g.columns * g.rows * RGBA)
        indexShown = ByteArray(indexBytes.size) { 1 }
        GlSupport.checkError("zoom index")
    }

    private fun clearTiles() {
        tiles.clear()
        slotTile.fill(NO_TILE)
        slotUsed.fill(0)
        wanted = emptyList()
    }

    /**
     * Before drawing a frame: follows the edit ([recipe]; a change hides every tile until the edit settles), works out
     * the wanted tiles of [panes] and refreshes the index. Returns true while tiles are fading in (keep drawing).
     */
    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LoopWithTooManyJumpStatements")
    fun beforeFrame(recipe: Recipe, mapping: GeometryMapping, panes: List<TilePane>, now: Long): Boolean {
        val src = source ?: return false
        val g = grid ?: return false
        frameNo++
        // Tiles are in image space: a crop or turn keeps them, but the mapping must follow the geometry at once.
        geometry = recipe.geometry
        optics = recipe.optics
        val params = DevelopParams.from(src.colour, recipe)
        if (params != genParams || (params.masks.active && maskEpoch != genEpoch)) {
            genParams = params
            genEpoch = maskEpoch
            genRecipe = recipe
            generation++
            genChangedAt = now
            settleStart = now
        }
        val view = zoomView(mapping, src)
        val keys = LinkedHashSet<Int>()
        for (pane in panes) {
            view.visibleTiles(pane.view, pane.windowWidth, pane.windowHeight, pane.clip, g).forEach { (tx, ty) ->
                keys += g.key(tx, ty)
            }
        }
        if (keys.isNotEmpty() && wanted.isEmpty()) settleStart = maxOf(settleStart, now)
        wanted = keys.toList()
        var fading = false
        var shown = 0
        indexBytes.fill(0)
        for (key in wanted) {
            val tile = tiles[key] ?: continue
            slotUsed[tile.slot] = frameNo
            if (!tile.ready || tile.generation != generation) continue
            val alpha = min(1f, (now - tile.readyAt).toFloat() / (FADE_MS * NANOS_PER_MS))
            if (alpha < 1f) fading = true
            val o = key * RGBA
            indexBytes[o] = (tile.slot % SLOTS_X).toByte()
            indexBytes[o + 1] = (tile.slot / SLOTS_X).toByte()
            indexBytes[o + 2] = (alpha * MAX_BYTE).roundToInt().toByte()
            indexBytes[o + 3] = MAX_BYTE.toInt().toByte()
            shown++
        }
        if (!indexBytes.contentEquals(indexShown)) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, index)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, g.columns, g.rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE,
                ByteBuffer.wrap(indexBytes),
            )
            indexBytes.copyInto(indexShown)
        }
        stats = stats.copy(shown = shown, wanted = wanted.size)
        return fading
    }

    /** The tile uniforms of a pane of [view] (null when it shows no tiles: below 50 % or no source). */
    @Suppress("ReturnCount")
    fun uniforms(view: ViewTransform, mapping: GeometryMapping): TileUniforms? {
        val src = source ?: return null
        val g = grid ?: return null
        if (atlas == 0 || index == 0) return null
        val zv = zoomView(mapping, src)
        val s = zv.fullScale(view.scale)
        if (s < ZoomGrid.MIN_SCALE) return null
        val aligned = zv.full.axisAligned
        return TileUniforms(
            k = k,
            fullOutputWidth = zv.full.outputWidth,
            fullOutputHeight = zv.full.outputHeight,
            proxyOutputWidth = mapping.outputWidth,
            proxyOutputHeight = mapping.outputHeight,
            full = zv.full,
            imageWidth = g.imageWidth,
            imageHeight = g.imageHeight,
            atlasWidth = ATLAS_WIDTH,
            atlasHeight = ATLAS_HEIGHT,
            indexWidth = g.columns,
            indexHeight = g.rows,
            mode = OutputSampling.mode(s, aligned),
            snap = OutputSampling.snapToOutputPixels(s, aligned),
            lod = min(1f, OutputSampling.lod(s)),
            nearest = OutputSampling.nearestMagnification(s),
        )
    }

    /** Binds the atlas (unit 1, with the pane's magnification filter) and the index (unit 2). */
    fun bind(nearest: Boolean) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, index)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, atlas)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            if (nearest) GLES30.GL_NEAREST else GLES30.GL_LINEAR,
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * After the frame was submitted: once the edit has settled, hands the most central wanted tile that is missing
     * to the worker (one at a time); otherwise asks for a frame when it will have settled.
     */
    fun afterFrame(now: Long) = dispatch(now)

    @Suppress("CyclomaticComplexMethod", "ReturnCount", "LongMethod")
    private fun dispatch(now: Long) {
        val src = source ?: return
        val w = worker
        if (inFlight || w == null) {
            if (w == null && wanted.isNotEmpty()) handler.postDelayed({ requestFrame() }, WORKER_POLL_MS)
            return
        }
        val waitNs = IDLE_MS * NANOS_PER_MS - (now - genChangedAt)
        if (waitNs > 0) {
            // Come back when the edit has settled: for the wanted tiles, or to prefetch the grids.
            if (wanted.isNotEmpty() || preparedGeneration != generation) {
                handler.postDelayed({ dispatch(System.nanoTime()) }, waitNs / NANOS_PER_MS + 1)
            }
            return
        }
        val g = checkNotNull(grid)
        val key = wanted.firstOrNull { k -> tiles[k]?.let { it.generation != generation } ?: true }
        if (key == null) {
            prefetchGrids(w, src)
            if (wanted.isNotEmpty() && settleStart != 0L) {
                stats = stats.copy(lastSettleMs = (now - settleStart) / NANOS_PER_MS)
                Log.i(BENCH, "zoom settled: ${wanted.size} tiles in ${stats.lastSettleMs} ms")
                settleStart = 0L
            }
            notifyWaiters()
            return
        }
        val slot = tiles[key]?.slot ?: freeSlot() ?: return
        tiles.remove(slotTile[slot])
        slotTile[slot] = key
        val tile = Tile(slot, generation, 0L, ready = false)
        tiles[key] = tile
        val fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        inFlight = true
        val gen = generation
        val recipe = genRecipe
        val core = g.core(key % g.columns, key / g.columns)
        val slotX = (slot % SLOTS_X) * ZoomGrid.SLOT
        val slotY = (slot / SLOTS_X) * ZoomGrid.SLOT
        val atlasTex = atlas
        val masks = maskContext
        val epoch = maskEpoch
        executor.execute {
            var tileMs = -1L
            var prepareMs = 0L
            w.maskContext = masks
            w.maskEpoch = epoch
            w.onAiReady = { handler.post { if (!released) aiReady() } }
            try {
                prepareMs = w.prepareZoom(src, recipe)
                w.waitFence(fence)
                tileMs = w.developTile(src, recipe, core, ZoomGrid.BORDER, atlasTex, slotX, slotY, ZoomGrid.SLOT)
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                Log.w(TAG, "zoom tile failed", e)
            }
            handler.post { onTileDone(key, tile, gen, tileMs, prepareMs) }
        }
    }

    /**
     * With nothing to develop, builds the settled edit's full-resolution grids in the background, so the first zoom
     * (and the first tiles after an edit) do not wait for the pre-passes.
     */
    private fun prefetchGrids(w: TiledRenderer, src: TileSource) {
        if (preparedGeneration == generation) return
        inFlight = true
        val gen = generation
        val recipe = genRecipe
        val masks = maskContext
        val epoch = maskEpoch
        executor.execute {
            w.maskContext = masks
            w.maskEpoch = epoch
            val ms = runCatching { w.prepareZoom(src, recipe) }.onFailure { Log.w(TAG, "zoom grids failed", it) }
            handler.post {
                if (released) return@post
                inFlight = false
                if (ms.isSuccess) preparedGeneration = gen
                if ((ms.getOrNull() ?: 0L) > 0L) Log.i(BENCH, "zoom grids (prefetch): ${ms.getOrNull()} ms")
                dispatch(System.nanoTime())
            }
        }
    }

    private var preparedGeneration = -1

    /** An AI mask a tile asked for is ready: develop the tiles again. */
    private fun aiReady() {
        maskEpoch++
        requestFrame()
    }

    private fun onTileDone(key: Int, tile: Tile, gen: Int, tileMs: Long, prepareMs: Long) {
        if (released) return
        inFlight = false
        if (tileMs >= 0) preparedGeneration = gen
        if (tileMs >= 0 && tiles[key] === tile) {
            tile.ready = gen == generation
            tile.generation = gen
            tile.readyAt = System.nanoTime()
            tileMsTotal += tileMs
            val n = stats.tiles + 1
            stats = stats.copy(
                tiles = n,
                lastTileMs = tileMs,
                meanTileMs = tileMsTotal.toFloat() / n,
                lastPrepareMs = if (prepareMs > 0) prepareMs else stats.lastPrepareMs,
            )
            if (prepareMs > 0) Log.i(BENCH, "zoom grids: $prepareMs ms")
            Log.i(BENCH, "zoom tile ${ZoomGrid.TILE}px: $tileMs ms")
        } else if (tiles[key] === tile) {
            tiles.remove(key)
            slotTile[tile.slot] = NO_TILE
        }
        requestFrame()
        // The next tile right away (the wanted list is the last frame's), not one frame later.
        dispatch(System.nanoTime())
    }

    /** A slot without a tile, else the one shown longest ago that is not wanted now. */
    private fun freeSlot(): Int? {
        val wantedSet = wanted.toHashSet()
        var best = -1
        for (i in 0 until SLOTS) {
            if (slotTile[i] == NO_TILE) return i
            if (slotTile[i] in wantedSet && tiles[slotTile[i]]?.generation == generation) continue
            if (best < 0 || slotUsed[i] < slotUsed[best]) best = i
        }
        return best.takeIf { it >= 0 }
    }

    /** Registers [done], checked after each tile and frame until it returns true (tests). */
    fun whenIdle(done: () -> Boolean) {
        waiters += done
        requestFrame()
    }

    private fun notifyWaiters() {
        val now = System.nanoTime()
        val faded = tiles.values.all { !it.ready || now - it.readyAt >= FADE_MS * NANOS_PER_MS }
        if (!faded) {
            handler.postDelayed({ requestFrame() }, FADE_MS)
            return
        }
        waiters.removeAll { it() }
    }

    private fun zoomView(mapping: GeometryMapping, src: TileSource): ZoomView =
        ZoomView(k, mapping.outputWidth, mapping.outputHeight, fullMapping(src))

    private var geometry: Geometry = Geometry()
    private var optics: Optics = Optics()
    private var fullGeometry: Pair<Geometry, Optics>? = null
    private var fullMappingCache: GeometryMapping? = null

    private fun fullMapping(src: TileSource): GeometryMapping {
        val key = geometry to optics
        val cached = fullMappingCache
        if (cached != null && key == fullGeometry) return cached
        return GeometryMapping.of(key.first, src.width, src.height, src.colour.lens, key.second).also {
            fullMappingCache = it
            fullGeometry = key
        }
    }

    /** Frees everything; waits for the worker to finish its tile. GL thread. */
    fun release() {
        released = true
        val old = source
        source = null
        executor.execute {
            worker?.close()
            worker = null
            old?.close()
        }
        executor.shutdown()
        runCatching { executor.awaitTermination(SHUTDOWN_S, TimeUnit.SECONDS) }
        if (atlas != 0) GLES30.glDeleteTextures(1, intArrayOf(atlas), 0)
        if (index != 0) GLES30.glDeleteTextures(1, intArrayOf(index), 0)
        atlas = 0
        index = 0
    }

    companion object {
        /** Slots across and down the atlas: 48 slots of 528 px, 4224 x 3168 RGBA16F + level 1 = 134 MB. */
        const val SLOTS_X: Int = 8
        const val SLOTS_Y: Int = 6
        private const val SLOTS = SLOTS_X * SLOTS_Y
        const val ATLAS_WIDTH: Int = SLOTS_X * ZoomGrid.SLOT
        const val ATLAS_HEIGHT: Int = SLOTS_Y * ZoomGrid.SLOT

        /** The worker's tile edge: a core, its border and the Detail apron fit (512 + 2 * (8 + 72) = 672). */
        const val WORKER_TILE: Int = 1024

        /** The edit must be unchanged this long before tiles are developed (a slider drag shows the proxy). */
        const val IDLE_MS: Long = 150

        /** Fade-in of a new tile. */
        const val FADE_MS: Long = 150

        private const val WORKER_POLL_MS = 50L
        private const val SHUTDOWN_S = 10L
        private const val NO_TILE = -1
        private const val RGBA = 4
        private const val MAX_BYTE = 255f
        private const val NANOS_PER_MS = 1_000_000L
        private const val TAG = "RevealZoom"

        /** Logcat tag of the measurements the orchestrator collects on the phone. */
        const val BENCH: String = "REVEAL_BENCH"
    }
}
