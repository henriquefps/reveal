@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.LocalWhiteBalance
import dev.hfps.reveal.pipeline.mask.AiMask
import dev.hfps.reveal.pipeline.mask.DabBins
import dev.hfps.reveal.pipeline.mask.Dabs
import dev.hfps.reveal.pipeline.mask.MaskParams
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * The masks on the GL thread (docs/adr/0022): the brush layers, the AI layers, the coverage layers of one region and
 * the uniform blocks of the rendered masks (`Masks`, the develop rows, the Detail rows), for the develop pass and
 * Detail's passes. One instance per GL context: the preview keeps everything between frames (`cache`), the tiled
 * renderer redoes it per tile.
 *
 * **Layers are cells of one 2D texture** ([LayerGrid]: side by side, then in rows, within `GL_MAX_TEXTURE_SIZE`), never
 * a 2D array texture: some drivers (the Android emulator's GL translator) cap a 2D array's width and height at the 3D
 * texture limit (2048), smaller than the editor's proxy.
 *
 * **Caching** (the preview; docs/adr/0022 "Speed (M4/P)"): a brush layer whose dabs are unchanged is kept; one whose
 * dab list grew or changed at the end (a stroke in progress) re-evaluates only the 64 x 64 bins the changed dabs reach,
 * scissored; an auto-mask layer is redone when the pre-local image changes. The coverage is recomputed only when its
 * inputs changed: the shapes or the AI masks (every layer), the brush layers (the rectangle their changed bins cover),
 * the pre-local image (only the masks that read it, the other channels kept by the colour mask). The uniform blocks
 * are uploaded only when their contents change.
 */
@Suppress("TooManyFunctions", "LargeClass")
internal class MaskPasses {
    private var coverageProgram = 0
    private var brushProgram = 0
    private var coloursProgram = 0

    /**
     * The fused range coverage (`MaskShaders.FUSED_COVERAGE_FRAG`, compiled and attached to the develop stage's blocks
     * by the preview renderer: [attachFused]); 0 without. [fuseNow]: the current [coverage] call uses it.
     */
    private var fusedProgram = 0
    private var fuseNow = false

    /** Binds the scene, the guide and the develop uniforms for the fused pass; called once, before its first draw. */
    private var prepareFused: (() -> Unit)? = null
    private var ubo = 0
    private var developUbo = 0
    private var detailUbo = 0
    private var wbTex = 0
    private var shapesTex = 0
    private var dabsTex = 0
    private var binsTex = 0
    private var indexTex = 0
    private var dummy2d = 0
    private var maxSize = 0

    private var regionWidth = 0
    private var regionHeight = 0
    private var allocWidth = 0
    private var allocHeight = 0
    private var coverageTex = 0
    private var coverageFbo = 0
    private var coverageGrid: LayerGrid? = null
    private var brushTex = 0
    private var brushFbo = 0
    private var brushGrid: LayerGrid? = null
    private var aiTex = 0
    private var aiGrid: LayerGrid? = null

    // The auto-mask dabs' reference colours: rows of DATA_WIDTH texels, brush layer l from row coloursRow[l].
    private var coloursTex = 0
    private var coloursFbo = 0
    private var coloursCapacity = 0
    private var coloursRow = IntArray(0)

    private var brushDabs: Array<Dabs?> = emptyArray()
    private var brushPre = IntArray(0)
    private var aiShown: List<AiMask?> = emptyList()
    private var coverageKey: Any? = null
    private var coveragePre = -1
    private val locations = HashMap<Int, HashMap<String, Int>>()
    private var blockData: FloatArray? = null
    private var developData: FloatArray? = null
    private var detailData: FloatArray? = null

    /** The coverage texture of the last [coverage] call (0 when none); its layers are [coverageGrid]'s cells. */
    var coverageTexture: Int = 0
        private set

    /** What the last [coverage] call did, for the benchmark and tests. */
    var lastStats: Stats = Stats()
        private set

    /**
     * Work of one [coverage] call: brush bins re-rasterised, brush layers redone whole, coverage layers drawn,
     * nanoseconds of GL-thread time, and [dirty] the rectangle of the region whose coverage changed (null = all of it,
     * or nothing when [coverageLayers] is 0).
     */
    data class Stats(
        val brushBins: Int = 0,
        val brushFull: Int = 0,
        val coverageLayers: Int = 0,
        val nanos: Long = 0,
        val dirty: IntArray? = null,
    )

    private fun uniform(program: Int, name: String): Int =
        locations.getOrPut(program) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(program, name) }

    fun init() {
        val limit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, limit, 0)
        maxSize = limit[0]
        coverageProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, MaskShaders.COVERAGE_FRAG)
        brushProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, MaskShaders.BRUSH_FRAG)
        coloursProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, MaskShaders.DAB_COLOURS_FRAG)
        GLES30.glUseProgram(coverageProgram)
        GLES30.glUniform1i(uniform(coverageProgram, "uPre"), UNIT_A)
        GLES30.glUniform1i(uniform(coverageProgram, "uBrush"), UNIT_B)
        GLES30.glUniform1i(uniform(coverageProgram, "uAi"), UNIT_C)
        GLES30.glUniform1i(uniform(coverageProgram, "uShapes"), UNIT_D)
        GLES30.glUseProgram(brushProgram)
        GLES30.glUniform1i(uniform(brushProgram, "uDabs"), UNIT_A)
        GLES30.glUniform1i(uniform(brushProgram, "uBins"), UNIT_B)
        GLES30.glUniform1i(uniform(brushProgram, "uIndex"), UNIT_C)
        GLES30.glUniform1i(uniform(brushProgram, "uPre"), UNIT_D)
        GLES30.glUniform1i(uniform(brushProgram, "uDabColours"), UNIT_E)
        GLES30.glUseProgram(coloursProgram)
        GLES30.glUniform1i(uniform(coloursProgram, "uDabs"), UNIT_A)
        GLES30.glUniform1i(uniform(coloursProgram, "uPre"), UNIT_D)
        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        ubo = ids[0]
        developUbo = ids[1]
        detailUbo = ids[2]
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, MaskShaders.BINDING, ubo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, MaskShaders.DEVELOP_ROWS_BINDING, developUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, MaskShaders.DETAIL_ROWS_BINDING, detailUbo)
        uploadBlocks(MaskParams.NONE, 0, 1f, 1f, null)
        shapesTex = dataTexture()
        dabsTex = dataTexture()
        binsTex = dataTexture()
        indexTex = dataTexture()
        wbTex = dataTexture()
        val table = FloatArray(LocalWhiteBalance.NODES * LocalWhiteBalance.NODES * RGBA)
        for (i in 0 until LocalWhiteBalance.NODES * LocalWhiteBalance.NODES) {
            for (c in 0 until 3) table[i * RGBA + c] = LocalWhiteBalance.table[i * 3 + c]
        }
        upload2d(wbTex, GLES30.GL_RGBA32F, GLES30.GL_RGBA, LocalWhiteBalance.NODES, LocalWhiteBalance.NODES, table)
        dummy2d = dataTexture()
        upload2d(dummy2d, GLES30.GL_RGBA32F, GLES30.GL_RGBA, 1, 1, FloatArray(RGBA))
        bindDevelopUnits(null)
        GlSupport.checkError("mask setup")
    }

    /**
     * Takes the fused range-coverage [program] (its develop blocks bound by the caller): [coverage] with `fused` then
     * computes the pre-local colour in the coverage pass; `fused` binds the scene, guide and develop uniforms (units
     * 0-3) and is called only when a layer that reads the pre-local image is drawn.
     */
    fun attachFused(program: Int) {
        fusedProgram = program
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(uniform(program, "uBrush"), UNIT_B)
        GLES30.glUniform1i(uniform(program, "uAi"), UNIT_C)
        GLES30.glUniform1i(uniform(program, "uShapes"), UNIT_D)
    }

    /**
     * Uploads the `Masks` block and the develop and Detail rows of [params] (the first [MaskParams.masks] slots of the
     * coverage; each only when it changed) and binds the coverage (or a stand-in) and the white-balance gains on their
     * units. With [off] the block says no mask renders (the pre-local develop pass).
     */
    fun bind(params: DevelopParams, off: Boolean = false) {
        val count = if (off || coverageTexture == 0) 0 else params.masks.masks.size
        uploadBlocks(params.masks, count, params.detail.sharpenAtt, params.effects.textureAtt, coverageGrid)
        bindDevelopUnits(if (count > 0) coverageTexture else null)
    }

    private fun bindDevelopUnits(coverage: Int?) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + MaskShaders.COVERAGE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, coverage ?: if (coverageTexture != 0) coverageTexture else dummy2d)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + MaskShaders.WB_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, wbTex)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /** Binds the coverage texture on [MaskShaders.COVERAGE_UNIT] (the output pass's overlay). */
    fun bindCoverage() {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + MaskShaders.COVERAGE_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (coverageTexture != 0) coverageTexture else dummy2d)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    /**
     * Where coverage slot [slot] lives for the overlay: (cell origin x, y, texture width, height) in texels, and its
     * channel; null without coverage.
     */
    @Suppress("ReturnCount")
    fun overlayCell(slot: Int): Pair<FloatArray, Int>? {
        val g = coverageGrid ?: return null
        if (coverageTexture == 0) return null
        val (ox, oy) = g.origin(slot / MaskPlan.SLOTS)
        return floatArrayOf(ox.toFloat(), oy.toFloat(), g.width.toFloat(), g.height.toFloat()) to slot % MaskPlan.SLOTS
    }

    private fun uploadBlocks(m: MaskParams, count: Int, sharpenAtt: Float, textureAtt: Float, grid: LayerGrid?) {
        val block = FloatArray(BLOCK_FLOATS)
        val ints = intArrayOf(
            count,
            m.needs,
            0,
            0,
            grid?.cellWidth ?: 1,
            grid?.cellHeight ?: 1,
            grid?.columns ?: 1,
            0,
        )
        for (i in ints.indices) block[i] = java.lang.Float.intBitsToFloat(ints[i])
        for (i in 0 until MaskParams.GLOBALS) {
            block[ints.size + i] = when (i) {
                10 -> sharpenAtt
                11 -> textureAtt
                else -> m.global[i]
            }
        }
        if (!block.contentEquals(blockData)) {
            upload(ubo, block)
            blockData = block
        }
        // The rows of the rendered masks, the rest zero (the block's full size is always bound).
        val dev = FloatArray(MaskParams.MAX_MASKS * MaskParams.DEVELOP_ROWS * RGBA)
        val det = FloatArray(MaskParams.MAX_MASKS * MaskParams.DETAIL_ROWS * RGBA)
        val split = MaskParams.DEVELOP_ROWS * RGBA
        for (i in 0 until count) {
            val o = i * MaskParams.VECTOR
            m.adjust.copyInto(dev, i * split, o, o + split)
            m.adjust.copyInto(det, i * (MaskParams.VECTOR - split), o + split, o + MaskParams.VECTOR)
        }
        if (!dev.contentEquals(developData)) {
            upload(developUbo, dev)
            developData = dev
        }
        if (!det.contentEquals(detailData)) {
            upload(detailUbo, det)
            detailData = det
        }
    }

    private fun upload(buffer: Int, data: FloatArray) {
        val b = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        b.asFloatBuffer().put(data)
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, b.capacity(), b, GLES30.GL_DYNAMIC_DRAW)
    }

    /**
     * The coverage of [plan]'s masks over the region ([x], [y], [width] x [height] pixels of the plan's image) into the
     * coverage array ([coverageTexture]); [pre] is the pre-local OkLab texture of the region (0 when nothing reads it),
     * [preVersion] changes whenever its content does, [ai] the AI layers' masks. With [cache] (the preview) unchanged
     * inputs are kept and a changed brush re-rasterises only its bins; the auto-mask dabs' reference colours are then
     * taken from [pre] (the whole image). Without [cache] (tiles) they must have been filled by [dabColours] over the
     * whole image first. Returns true when the coverage changed ([lastStats] says where). The caller binds its own
     * framebuffer, viewport and scissor next.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod", "ReturnCount", "NestedBlockDepth")
    fun coverage(
        plan: MaskPlan,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        pre: Int,
        preVersion: Int,
        ai: List<AiMask?>,
        cache: Boolean,
        capacity: Int = 0,
        fused: (() -> Unit)? = null,
    ): Boolean {
        val start = System.nanoTime()
        fuseNow = fused != null && fusedProgram != 0
        prepareFused = fused
        if (plan.masks.isEmpty()) {
            coverageTexture = 0
            coverageKey = null
            return false
        }
        // The textures are [capacity] square when given (the tiled renderer's tiles: no reallocation per tile size).
        val aw = if (capacity > 0) max(capacity, width) else width
        val ah = if (capacity > 0) max(capacity, height) else height
        if (aw != allocWidth || ah != allocHeight) {
            releaseRegion()
            allocWidth = aw
            allocHeight = ah
        }
        if (width != regionWidth || height != regionHeight || !cache) {
            regionWidth = width
            regionHeight = height
            brushDabs = emptyArray()
            coverageKey = null
            aiShown = emptyList()
        }
        ensureCoverageLayers(plan.layers)
        ensureBrushLayers(plan.brushes.size)
        if (cache && plan.brushes.any { it.autoMask }) ensureColours(plan)
        // 1. Brush layers: kept, re-rasterised in their changed bins, or redone.
        var bins = 0
        var full = 0
        var dirty: IntArray? = null
        if (brushDabs.size != plan.brushes.size) {
            brushDabs = arrayOfNulls(plan.brushes.size)
            brushPre = IntArray(plan.brushes.size) { -1 }
        }
        for ((layer, dabs) in plan.brushes.withIndex()) {
            val old = brushDabs[layer]
            val same = old != null && old.size == dabs.size && old.data.contentEquals(dabs.data)
            // An auto-mask layer also depends on the pre-local image (its colours and every pixel's).
            val preStale = dabs.autoMask && brushPre[layer] != preVersion
            if (same && !preStale) continue
            val binned = DabBins.of(dabs, x, y, width, height)
            uploadDabs(dabs, binned)
            if (cache && dabs.autoMask) drawColours(plan, layer, dabs.size, pre, x, y, width, height)
            val prefix = if (old != null && cache && !preStale) dabs.commonPrefix(old) else 0
            val rect = if (prefix > 0) {
                val changed = changedBins(binned, dabs, checkNotNull(old), prefix, x, y)
                bins += rasterBins(layer, binned, changed, pre)
                changed.bounds
            } else {
                full++
                rasterBins(layer, binned, null, pre)
                null
            }
            dirty = when {
                rect == null -> FULL
                dirty == null -> rect
                dirty === FULL -> FULL
                else -> union(dirty, rect)
            }
            brushDabs[layer] = dabs
            brushPre[layer] = preVersion
        }
        // 2. AI layers (re-uploaded when a mask arrives or changes).
        val aiChanged = ai.size != aiShown.size || ai.indices.any { ai[it] !== aiShown[it] }
        if (aiChanged) {
            uploadAi(ai)
            aiShown = ai.toList()
        }
        // 3. The coverage, when anything it reads changed: everything, only the masks that read the pre-local image,
        // or the rectangle of the changed brush bins.
        // Brush strokes are left out of the key: their layers report where they changed (dirty).
        val shapes = plan.masks.map { m ->
            m.components.map { if (it is MaskComponent.Brush) it.copy(strokes = emptyList()) else it }
        }
        val key = Triple(shapes, (x to y), (plan.imageWidth to plan.imageHeight))
        val shapesChanged = key != coverageKey || aiChanged
        val preChanged = plan.needsPre && preVersion != coveragePre
        if (!shapesChanged && !preChanged && dirty == null) {
            lastStats = Stats(nanos = System.nanoTime() - start)
            return false
        }
        uploadShapes(plan, ai)
        val rect = if (shapesChanged || dirty === FULL) null else dirty
        val layers = when {
            shapesChanged || (preChanged && dirty != null) -> drawCoverage(plan, x, y, pre, null, null)

            preChanged -> drawCoverage(
                plan,
                x,
                y,
                pre,
                null,
                BooleanArray(plan.masks.size) {
                    MaskPlan.readsPre(plan.masks[it])
                },
            )

            else -> drawCoverage(plan, x, y, pre, rect, null)
        }
        coverageKey = key
        coveragePre = preVersion
        val changedRect = if (shapesChanged || preChanged) null else rect
        lastStats = Stats(bins, full, layers, System.nanoTime() - start, changedRect)
        return true
    }

    /**
     * Tiled renders: the auto-mask dabs' reference colours of [plan] from the pre-local OkLab texture [pre] of the
     * region ([x], [y], [width] x [height]), for the dabs whose centre lies in it; call it for pre-pass tiles covering
     * the whole image before the coverage of any tile ([reset] on the first).
     */
    @Suppress("LongParameterList")
    fun dabColours(plan: MaskPlan, pre: Int, x: Int, y: Int, width: Int, height: Int, reset: Boolean) {
        if (reset) ensureColours(plan)
        for ((layer, dabs) in plan.brushes.withIndex()) {
            if (!dabs.autoMask) continue
            uploadDabsOnly(dabs)
            drawColours(plan, layer, dabs.size, pre, x, y, width, height)
        }
    }

    /** Allocates the colour rows of [plan]'s brush layers (one row block per layer). */
    private fun ensureColours(plan: MaskPlan) {
        val w = MaskShaders.DATA_WIDTH
        val rows = IntArray(plan.brushes.size)
        var total = 0
        for ((layer, dabs) in plan.brushes.withIndex()) {
            rows[layer] = total
            total += (max(dabs.size, 1) + w - 1) / w
        }
        if (!rows.contentEquals(coloursRow)) brushPre.fill(-1)
        coloursRow = rows
        if (coloursTex != 0 && coloursCapacity >= total) return
        deleteTarget(coloursTex, coloursFbo)
        val capacity = capacity(max(total, 1))
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, w, capacity)
        for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, param, GLES30.GL_NEAREST)
        }
        coloursTex = id[0]
        coloursFbo = targetFbo(coloursTex)
        coloursCapacity = capacity
        // A new layout: every auto layer redoes its colours.
        brushPre.fill(-1)
    }

    /** The reference colours of brush [layer]'s [count] dabs (uploaded in the dab texture) found in the region. */
    @Suppress("LongParameterList")
    private fun drawColours(plan: MaskPlan, layer: Int, count: Int, pre: Int, x: Int, y: Int, width: Int, height: Int) {
        val w = MaskShaders.DATA_WIDTH
        val row = coloursRow[layer]
        val rows = (max(count, 1) + w - 1) / w
        GLES30.glUseProgram(coloursProgram)
        GLES30.glUniform1i(uniform(coloursProgram, "uCount"), count)
        GLES30.glUniform1i(uniform(coloursProgram, "uRow"), row)
        GLES30.glUniform2i(uniform(coloursProgram, "uOrigin"), x, y)
        GLES30.glUniform2i(uniform(coloursProgram, "uSize"), width, height)
        GLES30.glUniform2i(uniform(coloursProgram, "uImage"), plan.imageWidth, plan.imageHeight)
        bind2d(UNIT_A, dabsTex)
        bind2d(UNIT_D, if (pre != 0) pre else dummy2d)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coloursFbo)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glViewport(0, row, w, rows)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("dab colours")
    }

    /** The bins of [binned] that a dab after [prefix] of [dabs] or of [old] reaches, and their pixel bounds. */
    @Suppress("NestedBlockDepth")
    private fun changedBins(binned: DabBins, dabs: Dabs, old: Dabs, prefix: Int, x: Int, y: Int): ChangedBins {
        val flags = BooleanArray(binned.binsX * binned.binsY)
        val box = IntArray(4)
        for (list in listOf(dabs, old)) {
            for (k in prefix until list.size) {
                list.box(k, box)
                val x0 = max(box[0] - x, 0)
                val y0 = max(box[1] - y, 0)
                val x1 = min(box[2] - x, binned.width - 1)
                val y1 = min(box[3] - y, binned.height - 1)
                if (x0 > x1 || y0 > y1) continue
                for (j in y0 / DabBins.BIN..y1 / DabBins.BIN) {
                    for (i in x0 / DabBins.BIN..x1 / DabBins.BIN) flags[j * binned.binsX + i] = true
                }
            }
        }
        var bx0 = Int.MAX_VALUE
        var by0 = Int.MAX_VALUE
        var bx1 = -1
        var by1 = -1
        for (j in 0 until binned.binsY) {
            for (i in 0 until binned.binsX) {
                if (!flags[j * binned.binsX + i]) continue
                bx0 = min(bx0, i)
                by0 = min(by0, j)
                bx1 = max(bx1, i)
                by1 = max(by1, j)
            }
        }
        val bounds = if (bx1 < 0) {
            intArrayOf(0, 0, 0, 0)
        } else {
            intArrayOf(
                bx0 * DabBins.BIN,
                by0 * DabBins.BIN,
                min((bx1 + 1) * DabBins.BIN, binned.width),
                min((by1 + 1) * DabBins.BIN, binned.height),
            )
        }
        return ChangedBins(flags, bounds)
    }

    private class ChangedBins(val flags: BooleanArray, val bounds: IntArray)

    /**
     * Rasterises brush [layer]: every bin, or the runs of [changed] bins per bin row (scissored). Returns bins drawn.
     */
    private fun rasterBins(layer: Int, binned: DabBins, changed: ChangedBins?, pre: Int): Int {
        val (lx, ly) = checkNotNull(brushGrid).origin(layer)
        val auto = pendingAuto
        GLES30.glUseProgram(brushProgram)
        GLES30.glUniform2i(uniform(brushProgram, "uOrigin"), binned.x, binned.y)
        GLES30.glUniform2i(uniform(brushProgram, "uLayerOrigin"), lx, ly)
        GLES30.glUniform1i(uniform(brushProgram, "uAuto"), if (auto) 1 else 0)
        GLES30.glUniform1i(uniform(brushProgram, "uColourRow"), coloursRow.getOrElse(layer) { 0 })
        bind2d(UNIT_A, dabsTex)
        bind2d(UNIT_B, binsTex)
        bind2d(UNIT_C, indexTex)
        bind2d(UNIT_D, if (auto && pre != 0) pre else dummy2d)
        bind2d(UNIT_E, if (auto && coloursTex != 0) coloursTex else dummy2d)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, brushFbo)
        GLES30.glViewport(lx, ly, binned.width, binned.height)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        if (changed == null) {
            GLES30.glScissor(lx, ly, binned.width, binned.height)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            GlSupport.checkError("brush raster")
            return binned.binsX * binned.binsY
        }
        var drawn = 0
        for (j in 0 until binned.binsY) {
            var i = 0
            while (i < binned.binsX) {
                if (!changed.flags[j * binned.binsX + i]) {
                    i++
                    continue
                }
                val start = i
                while (i < binned.binsX && changed.flags[j * binned.binsX + i]) i++
                val x0 = start * DabBins.BIN
                val y0 = j * DabBins.BIN
                GLES30.glScissor(
                    lx + x0,
                    ly + y0,
                    min(i * DabBins.BIN, binned.width) - x0,
                    min(DabBins.BIN, binned.height - y0),
                )
                GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
                drawn += i - start
            }
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GlSupport.checkError("brush bins")
        return drawn
    }

    /** Whether the dabs last uploaded ([uploadDabs]) include auto-mask dabs. */
    private var pendingAuto = false

    private fun uploadDabs(dabs: Dabs, binned: DabBins) {
        uploadDabsOnly(dabs)
        val w = MaskShaders.DATA_WIDTH
        val binData = FloatArray(binned.binsX * binned.binsY * RGBA)
        for (b in 0 until binned.binsX * binned.binsY) {
            binData[b * RGBA] = binned.offsets[b].toFloat()
            binData[b * RGBA + 1] = binned.counts[b].toFloat()
        }
        upload2d(binsTex, GLES30.GL_RGBA32F, GLES30.GL_RGBA, binned.binsX, binned.binsY, binData)
        val indexRows = (max(binned.indices.size, 1) + w - 1) / w
        val indexData = FloatArray(w * indexRows)
        for (i in binned.indices.indices) indexData[i] = binned.indices[i].toFloat()
        upload2d(indexTex, GLES30.GL_R32F, GLES30.GL_RED, w, indexRows, indexData)
    }

    /** The dab texture: dab `k` is texels `2k` (x, y, radius, inner) and `2k + 1` (flow, erase, density, auto). */
    private fun uploadDabsOnly(dabs: Dabs) {
        val w = MaskShaders.DATA_WIDTH
        val dabTexels = max(dabs.size * 2, 1)
        val dabRows = (dabTexels + w - 1) / w
        val dabData = FloatArray(w * dabRows * RGBA)
        dabs.data.copyInto(dabData, 0, 0, dabs.size * Dabs.DAB_FLOATS)
        upload2d(dabsTex, GLES30.GL_RGBA32F, GLES30.GL_RGBA, w, dabRows, dabData)
        pendingAuto = dabs.autoMask
    }

    private fun uploadAi(ai: List<AiMask?>) {
        val ready = ai.filterNotNull()
        val w = max(ready.maxOfOrNull { it.width } ?: 1, 1)
        val h = max(ready.maxOfOrNull { it.height } ?: 1, 1)
        val grid = LayerGrid.of(w, h, max(ai.size, 1), maxSize)
        if (aiTex == 0 || grid != aiGrid) {
            if (aiTex != 0) GLES30.glDeleteTextures(1, intArrayOf(aiTex), 0)
            aiTex = gridTexture(GLES30.GL_R32F, grid, GLES30.GL_NEAREST)
            aiGrid = grid
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, aiTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for ((layer, mask) in ai.withIndex()) {
            if (mask == null) continue
            val (ox, oy) = grid.origin(layer)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, ox, oy, mask.width, mask.height, GLES30.GL_RED, GLES30.GL_FLOAT,
                FloatBuffer.wrap(mask.values),
            )
        }
        GlSupport.checkError("ai layers")
    }

    /** The component table: one row per component of every mask (head, data, AI ratios); skips not-ready AI layers. */
    private fun uploadShapes(plan: MaskPlan, ai: List<AiMask?>) {
        val rows = max(plan.components.sumOf { it.size }, 1)
        val texels = MaskShaders.SHAPE_TEXELS
        val data = FloatArray(rows * texels * RGBA)
        var row = 0
        for (list in plan.components) {
            for (c in list) {
                val o = row * texels * RGBA
                val aiMask = if (c.type == MaskPlan.TYPE_AI) ai.getOrNull(c.layer) else null
                val type = if (c.type == MaskPlan.TYPE_AI && aiMask == null) MaskPlan.TYPE_SKIP else c.type
                data[o] = type.toFloat()
                data[o + 1] = c.op.toFloat()
                data[o + 2] = if (c.invert) 1f else 0f
                data[o + 3] = c.layer.toFloat()
                c.data.copyInto(data, o + RGBA)
                if (aiMask != null) {
                    val a = o + (texels - 1) * RGBA
                    data[a] = aiMask.width.toFloat() / plan.imageWidth
                    data[a + 1] = aiMask.height.toFloat() / plan.imageHeight
                    data[a + 2] = aiMask.width.toFloat()
                    data[a + 3] = aiMask.height.toFloat()
                }
                row++
            }
        }
        upload2d(shapesTex, GLES30.GL_RGBA32F, GLES30.GL_RGBA, texels, rows, data)
    }

    /**
     * Draws the coverage layers into [rect] of the region (null = all of it); with [only], only the masks it marks (the
     * other channels keep their values, the layers without a marked mask are skipped). Returns the layers drawn.
     */
    @Suppress("CyclomaticComplexMethod", "LongParameterList", "LongMethod")
    private fun drawCoverage(plan: MaskPlan, x: Int, y: Int, pre: Int, rect: IntArray?, only: BooleanArray?): Int {
        val drawsPre = plan.masks.indices.any { m -> only?.get(m) != false && MaskPlan.readsPre(plan.masks[m]) }
        if (fuseNow && drawsPre) prepareFused?.invoke()
        prepareFused = null
        bind2d(UNIT_A, if (pre != 0) pre else dummy2d)
        bind2d(UNIT_B, if (brushTex != 0) brushTex else dummy2d)
        bind2d(UNIT_C, if (aiTex != 0) aiTex else dummy2d)
        bind2d(UNIT_D, shapesTex)
        val grid = checkNotNull(coverageGrid)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coverageFbo)
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        val first = IntArray(plan.masks.size)
        for (i in 1 until plan.masks.size) first[i] = first[i - 1] + plan.components[i - 1].size
        var drawn = 0
        var current = 0
        for (layer in 0 until plan.layers) {
            val slots = IntArray(MaskPlan.SLOTS * RGBA)
            val write = BooleanArray(MaskPlan.SLOTS)
            var readsPre = false
            for (s in 0 until MaskPlan.SLOTS) {
                val m = layer * MaskPlan.SLOTS + s
                if (m < plan.masks.size && (only == null || only[m])) {
                    slots[s * RGBA] = first[m]
                    slots[s * RGBA + 1] = plan.components[m].size
                    write[s] = true
                    readsPre = readsPre || MaskPlan.readsPre(plan.masks[m])
                }
            }
            if (only != null && write.none { it }) continue
            // A layer whose masks read the pre-local image computes it itself when fused (no pre texture).
            val program = if (fuseNow && readsPre) fusedProgram else coverageProgram
            if (program != current) {
                useCoverageProgram(program, x, y)
                current = program
            }
            val (lx, ly) = grid.origin(layer)
            if (rect != null) {
                GLES30.glScissor(lx + rect[0], ly + rect[1], rect[2] - rect[0], rect[3] - rect[1])
            } else {
                GLES30.glScissor(lx, ly, regionWidth, regionHeight)
            }
            GLES30.glUniform2i(uniform(program, "uLayerOrigin"), lx, ly)
            GLES30.glUniform4iv(uniform(program, "uShapeMasks"), MaskPlan.SLOTS, slots, 0)
            GLES30.glViewport(lx, ly, regionWidth, regionHeight)
            if (only != null) GLES30.glColorMask(write[0], write[1], write[2], write[3])
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            if (only != null) GLES30.glColorMask(true, true, true, true)
            drawn++
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GlSupport.checkError("mask coverage")
        coverageTexture = coverageTex
        return drawn
    }

    private fun useCoverageProgram(program: Int, x: Int, y: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform2i(uniform(program, "uOrigin"), x, y)
        val bg = brushGrid
        GLES30.glUniform3i(uniform(program, "uBrushGrid"), bg?.cellWidth ?: 1, bg?.cellHeight ?: 1, bg?.columns ?: 1)
        val ag = aiGrid
        GLES30.glUniform3i(uniform(program, "uAiGrid"), ag?.cellWidth ?: 1, ag?.cellHeight ?: 1, ag?.columns ?: 1)
    }

    /**
     * Reads back the coverage of slot [slot] (row-major, top row first, `width x height` of the region); tests only.
     */
    fun readCoverage(slot: Int): FloatArray {
        val pixels = FloatBuffer.allocate(regionWidth * regionHeight * RGBA)
        val (ox, oy) = checkNotNull(coverageGrid).origin(slot / MaskPlan.SLOTS)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, coverageFbo)
        GLES30.glReadPixels(ox, oy, regionWidth, regionHeight, GLES30.GL_RGBA, GLES30.GL_FLOAT, pixels)
        GlSupport.checkError("coverage readback")
        return FloatArray(regionWidth * regionHeight) { pixels.get(it * RGBA + slot % MaskPlan.SLOTS) }
    }

    private fun ensureCoverageLayers(layers: Int) {
        val g = coverageGrid
        if (coverageTex != 0 && g != null && g.capacity >= layers) return
        deleteTarget(coverageTex, coverageFbo)
        val grid = LayerGrid.of(allocWidth, allocHeight, capacity(layers), maxSize)
        coverageTex = gridTexture(GLES30.GL_RGBA16F, grid, GLES30.GL_LINEAR)
        coverageFbo = targetFbo(coverageTex)
        coverageGrid = grid
        coverageKey = null
    }

    @Suppress("ComplexCondition")
    private fun ensureBrushLayers(layers: Int) {
        val g = brushGrid
        if (layers == 0 || (brushTex != 0 && g != null && g.capacity >= layers)) return
        deleteTarget(brushTex, brushFbo)
        val grid = LayerGrid.of(allocWidth, allocHeight, capacity(layers), maxSize)
        brushTex = gridTexture(GLES30.GL_R16F, grid, GLES30.GL_NEAREST)
        brushFbo = targetFbo(brushTex)
        brushGrid = grid
        brushDabs = emptyArray()
    }

    private fun capacity(layers: Int): Int {
        var c = 1
        while (c < layers) c *= 2
        return c
    }

    private fun targetFbo(texture: Int): Int {
        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status !=
            GLES30.GL_FRAMEBUFFER_COMPLETE
        ) {
            throw GlException("mask layers incomplete: 0x${status.toString(16)}")
        }
        return id[0]
    }

    private fun deleteTarget(texture: Int, fbo: Int) {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (fbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
    }

    private fun releaseRegion() {
        deleteTarget(coverageTex, coverageFbo)
        deleteTarget(brushTex, brushFbo)
        coverageTex = 0
        coverageFbo = 0
        coverageGrid = null
        brushTex = 0
        brushFbo = 0
        brushGrid = null
        coverageTexture = 0
    }

    /** Frees the region's textures (a new image); the programs stay. */
    fun releaseImage() {
        releaseRegion()
        regionWidth = 0
        regionHeight = 0
        allocWidth = 0
        allocHeight = 0
        brushDabs = emptyArray()
        brushPre = IntArray(0)
        coverageKey = null
        coveragePre = -1
        aiShown = emptyList()
    }

    fun release() {
        releaseImage()
        deleteTarget(coloursTex, coloursFbo)
        coloursTex = 0
        coloursFbo = 0
        coloursCapacity = 0
        if (aiTex != 0) GLES30.glDeleteTextures(1, intArrayOf(aiTex), 0)
        aiTex = 0
        aiGrid = null
        val textures = intArrayOf(wbTex, shapesTex, dabsTex, binsTex, indexTex, dummy2d)
        GLES30.glDeleteTextures(textures.size, textures, 0)
        GLES30.glDeleteBuffers(3, intArrayOf(ubo, developUbo, detailUbo), 0)
        blockData = null
        developData = null
        detailData = null
        GLES30.glDeleteProgram(coverageProgram)
        GLES30.glDeleteProgram(brushProgram)
        GLES30.glDeleteProgram(coloursProgram)
        locations.clear()
    }

    private fun dataTexture(): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun upload2d(texture: Int, internal: Int, format: Int, w: Int, h: Int, data: FloatArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, w, h, 0, format, GLES30.GL_FLOAT, FloatBuffer.wrap(data))
    }

    private fun gridTexture(format: Int, grid: LayerGrid, filter: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, grid.width, grid.height)
        GlSupport.checkError("mask layers ${grid.width}x${grid.height}")
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun bind2d(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
    }

    private fun union(a: IntArray, b: IntArray): IntArray =
        intArrayOf(min(a[0], b[0]), min(a[1], b[1]), max(a[2], b[2]), max(a[3], b[3]))

    private companion object {
        const val RGBA = 4

        /** `Masks` block: 2 ivec4 + 3 vec4. */
        const val BLOCK_FLOATS = 20

        // The coverage and brush passes' inputs: Detail's units (rebound before every Detail pass), never the curve
        // table's (3) or the lens table's (5), which the tiled renderer binds once per render.
        const val UNIT_A = 8
        const val UNIT_B = 9
        const val UNIT_C = 10
        const val UNIT_D = 11

        // The brush pass's reference colours: the Effects grid's first unit, rebound before every final pass.
        const val UNIT_E = 12

        /** A brush change that needs the whole coverage redone. */
        val FULL = IntArray(4)
    }
}

/**
 * Points [program]'s mask samplers and its `Masks` block (a program that declares `MaskShaders.MASKS`) at their units
 * and binding; [develop] also the local white balance's table. Every such program must be attached: an unbound block
 * would read binding 0 (the `Develop` block).
 */
internal fun attachMasks(program: Int, develop: Boolean = false) {
    val index = GLES30.glGetUniformBlockIndex(program, "Masks")
    if (index != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(program, index, MaskShaders.BINDING)
    for ((name, binding) in listOf(
        "MaskDevelop" to MaskShaders.DEVELOP_ROWS_BINDING,
        "MaskDetail" to MaskShaders.DETAIL_ROWS_BINDING,
    )) {
        val rows = GLES30.glGetUniformBlockIndex(program, name)
        if (rows != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(program, rows, binding)
    }
    GLES30.glUseProgram(program)
    GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCoverage"), MaskShaders.COVERAGE_UNIT)
    if (develop) GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLocalWb"), MaskShaders.WB_UNIT)
}

/**
 * Layers of [cellWidth] x [cellHeight] texels as cells of one 2D texture: [columns] side by side, then rows; [capacity]
 * cells in all. Layer `l` starts at texel ([origin]).
 */
internal data class LayerGrid(val cellWidth: Int, val cellHeight: Int, val columns: Int, val rows: Int) {
    val width: Int get() = cellWidth * columns
    val height: Int get() = cellHeight * rows
    val capacity: Int get() = columns * rows

    fun origin(layer: Int): Pair<Int, Int> = (layer % columns) * cellWidth to (layer / columns) * cellHeight

    companion object {
        /** The narrowest grid of [layers] cells that fits [maxSize] (the driver's `GL_MAX_TEXTURE_SIZE`). */
        fun of(cellWidth: Int, cellHeight: Int, layers: Int, maxSize: Int): LayerGrid {
            val w = max(cellWidth, 1)
            val h = max(cellHeight, 1)
            val columns = (maxSize / w).coerceIn(1, max(layers, 1))
            val rows = (max(layers, 1) + columns - 1) / columns
            if (rows * h >
                maxSize
            ) {
                throw GlException("$layers mask layers of ${w}x$h exceed the texture limit $maxSize")
            }
            return LayerGrid(w, h, columns, rows)
        }
    }
}
