@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.HighlightReconstructionOp
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.PixelBox
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

/** Receives the developed output, band by band (rows top first, `w * 4` values per row, no padding). */
interface TileSink {
    /** 8-bit RGBA (dithered) pixels of output rows [y] until `y + h`, columns [x] until `x + w`. */
    fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer)

    /** Encoded (OETF applied, clamped to [0, 1]) unquantised RGBA floats, when the render asked for high depth. */
    fun acceptHigh(x: Int, y: Int, w: Int, h: Int, rgba: FloatBuffer): Unit =
        throw UnsupportedOperationException("this sink takes 8-bit output only")
}

/** The export was cancelled between two tiles. */
class RenderCancelledException : RuntimeException("export cancelled")

/** What a tiled render cost, for logs and the ADR. */
data class TiledRenderStats(
    val tileSize: Int,
    val prepassTiles: Int,
    val outputTiles: Int,
    val reconstructMs: Long,
    val guideMs: Long,
    val mainMs: Long,
    val uploadMs: Long,
)

/**
 * The develop pipeline at full resolution, in tiles (docs/adr/0009). Its own GL thread and EGL context (never the
 * editor's), so an export in a background worker runs while the editor renders, and releases everything on [close].
 *
 * The same shaders as [GpuDevelopRenderer] run per tile, with the tile's image position as `uOrigin`:
 * 1. **Grid pre-passes** over cell-aligned tiles of the whole image: the row segments of highlight reconstruction's
 *    chroma statistics, and (after the chroma grid is finished) of the local-tone guide on the scene stage; each
 *    grid is then finished once for the whole image ([LightPasses]). Same sums in the same order as a whole-image
 *    render: the grids are bit-identical, so tiles agree with each other and with an untiled render.
 * 2. **Main pass** per output tile: upload its input region, scene stage, develop stage, the geometry operator (slot
 *    13, `GeometryOp`: crop, straighten, quarter turns, flips) when the recipe has one, then (when the size changes)
 *    the two Lanczos passes, then the output transform with the output position's dither; read back in bands.
 *
 * Texture memory is fixed by the tile size: three RGBA16F `tile x tile` textures, two RGBA32F resampling targets and
 * the output target (about 150 MB at 2048, 40 MB at 1024). [create] falls back to smaller tiles while the driver runs
 * out of memory.
 */
class TiledRenderer private constructor(requestedTile: Int) : AutoCloseable {
    private val thread = HandlerThread("reveal-export-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()

    /** Tile edge in pixels actually allocated; build the [ExportPlan] with it. */
    @Volatile
    var tileSize: Int = requestedTile
        private set

    /** Driver information. */
    @Volatile
    lateinit var info: GlInfo
        private set

    private var sceneProgram = 0
    private var developProgram = 0
    private var resampleProgram = 0
    private var geometryProgram = 0
    private var outputProgram = 0
    private var developUbo = 0
    private var lightUbo = 0
    private var lookUbo = 0
    private var vao = 0
    private val light = LightPasses()

    private var sourceTex = 0
    private var sceneTex = 0
    private var workTex = 0
    private var rowsTex = 0
    private var colsTex = 0
    private var outTex = 0
    private var sceneFbo = 0
    private var workFbo = 0
    private var rowsFbo = 0
    private var colsFbo = 0
    private var outFbo = 0
    private var outHigh = false

    /**
     * Develops [source] with [recipe] as [plan] says and streams the output to [sink]. Blocking (runs on the renderer's
     * GL thread); [cancel] is polled between tiles ([RenderCancelledException]); [progress] gets 0..1.
     *
     * @param high true writes unquantised encoded floats to [TileSink.acceptHigh] (10/16-bit encoders) instead of
     *   dithered 8-bit.
     */
    @Suppress("LongParameterList")
    fun render(
        source: TileSource,
        recipe: Recipe,
        plan: ExportPlan,
        output: OutputSettings,
        sink: TileSink,
        high: Boolean = false,
        cancel: () -> Boolean = { false },
        progress: (Float) -> Unit = {},
    ): TiledRenderStats = onGl {
        require(plan.maxTile <= tileSize) { "plan tiles ${plan.maxTile} exceed the allocated $tileSize" }
        require(plan.imageWidth == source.width && plan.imageHeight == source.height) { "plan is for another image" }
        renderOnGl(source, recipe, plan, output, sink, high, cancel, progress)
    }

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    private fun renderOnGl(
        source: TileSource,
        recipe: Recipe,
        plan: ExportPlan,
        output: OutputSettings,
        sink: TileSink,
        high: Boolean,
        cancel: () -> Boolean,
        progress: (Float) -> Unit,
    ): TiledRenderStats {
        ensureOutputTarget(high)
        val params = DevelopParams.from(source.colour, recipe)
        val grid = light.allocate(source.width, source.height)
        val prepass = plan.prepassTiles(grid)
        val tiles = plan.outputTiles()
        val hp = params.highlights
        val prepassRuns = (if (hp != null) 1 else 0) + (if (params.tone.localActive) 1 else 0)
        val steps = tiles.size + prepassRuns * prepass.size
        var done = 0
        fun step() {
            if (cancel()) throw RenderCancelledException()
            progress(done++.toFloat() / steps)
        }
        val uploader = Uploader(source)
        // 1a. Highlight reconstruction's chroma grid (only when some pixel reaches the clip ramp, as in the preview).
        var reconstruct = false
        val t0 = System.nanoTime()
        if (hp != null) {
            val threshold = HighlightReconstructionOp.T0 * checkNotNull(source.colour.saturation)
            uploader.watchClip = threshold
            for (tile in prepass) {
                step()
                uploader.upload(tile)
                light.reconstructRows(sourceTex, params.wbMultipliers, hp, tile.x, tile.y, tile.width, tile.height)
            }
            uploader.watchClip = Float.MAX_VALUE
            if (uploader.reachedClip) {
                light.reconstructFinish()
                reconstruct = true
            }
        }
        val t1 = System.nanoTime()
        uploadUbo(developUbo, GpuDevelopRenderer.developBlock(params))
        uploadUbo(lightUbo, GpuDevelopRenderer.lightBlock(params, grid, reconstruct))
        uploadUbo(lookUbo, ColourShaders.lookBlock(params))
        // 1b. The local-tone guide from the scene stage of every pre-pass tile.
        if (params.tone.localActive) {
            for (tile in prepass) {
                step()
                uploader.upload(tile)
                scenePass(tile, reconstruct)
                light.guideRows(sceneTex, tile.x, tile.y, tile.width, tile.height)
            }
            light.guideFinish()
        }
        val t2 = System.nanoTime()
        // 2. The output tiles.
        val matrix = OutputTransformOp.workingToOutput(output.space).toFloatArray()
        val band = readBand(high)
        for (tile in tiles) {
            step()
            val input = tile.input
            val box = tile.geometry
            var stage = workTex
            if (input != null) {
                uploader.upload(input)
                scenePass(input, reconstruct)
                developPass(input, params.tone.localActive)
            }
            // Slot 13: the geometry box from the developed region (black where it reads nothing).
            if (plan.transforms) {
                geometryPass(plan, box, input)
                stage = colsTex
            }
            if (plan.resamples) {
                val out = tile.output
                resample(rowsFbo, stage, horizontal = true, plan, out, box, out.width, box.height)
                resample(colsFbo, rowsTex, horizontal = false, plan, out, box, out.width, out.height)
                stage = colsTex
            }
            outputPass(stage, tile.output, matrix, output, high)
            readBack(tile.output, band, high, sink)
        }
        GLES30.glFinish()
        progress(1f)
        val t3 = System.nanoTime()
        return TiledRenderStats(
            tileSize = tileSize,
            prepassTiles = prepass.size,
            outputTiles = tiles.size,
            reconstructMs = (t1 - t0) / NANOS_PER_MS,
            guideMs = (t2 - t1) / NANOS_PER_MS,
            mainMs = (t3 - t2) / NANOS_PER_MS,
            uploadMs = uploader.nanos / NANOS_PER_MS,
        )
    }

    // ------------------------------------------------------------------ passes

    private fun scenePass(region: PixelBox, reconstruct: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, region.width, region.height)
        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(sceneProgram, "uOrigin"), region.x, region.y)
        bind(sourceTex, if (reconstruct) light.chromaTexture else sourceTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("export scene stage")
    }

    private fun developPass(region: PixelBox, local: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, workFbo)
        GLES30.glViewport(0, 0, region.width, region.height)
        GLES30.glUseProgram(developProgram)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(developProgram, "uOrigin"), region.x, region.y)
        bind(sceneTex, if (local) light.coefTexture else sceneTex, if (local) light.momentsTexture else sceneTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("export develop stage")
    }

    /** Geometry operator for [box] from the developed [input] region into the columns target. */
    private fun geometryPass(plan: ExportPlan, box: PixelBox, input: PixelBox?) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, colsFbo)
        GLES30.glViewport(0, 0, box.width, box.height)
        if (input == null) {
            GLES30.glClearColor(0f, 0f, 0f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val p = geometryProgram
        GLES30.glUseProgram(p)
        fun loc(name: String) = GLES30.glGetUniformLocation(p, name)
        val m = plan.mapping.toImageFloats()
        GLES30.glUniform2i(loc("uSrc"), input.x, input.y)
        GLES30.glUniform2i(loc("uImage"), plan.imageWidth, plan.imageHeight)
        GLES30.glUniform2i(loc("uDst"), box.x, box.y)
        GLES30.glUniform3f(loc("uGeomX"), m[0], m[1], m[2])
        GLES30.glUniform3f(loc("uGeomY"), m[3], m[4], m[5])
        GLES30.glUniform1i(loc("uAxis"), if (plan.mapping.axisAligned) 1 else 0)
        bind(workTex, workTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("export geometry")
    }

    @Suppress("LongParameterList")
    private fun resample(
        fbo: Int,
        src: Int,
        horizontal: Boolean,
        plan: ExportPlan,
        out: PixelBox,
        input: PixelBox,
        width: Int,
        height: Int,
    ) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glViewport(0, 0, width, height)
        val p = resampleProgram
        GLES30.glUseProgram(p)
        fun loc(name: String) = GLES30.glGetUniformLocation(p, name)
        GLES30.glUniform2i(loc("uDir"), if (horizontal) 1 else 0, if (horizontal) 0 else 1)
        GLES30.glUniform1i(loc("uDstOrigin"), if (horizontal) out.x else out.y)
        GLES30.glUniform1i(loc("uSrcOrigin"), if (horizontal) input.x else input.y)
        GLES30.glUniform1i(loc("uOffset"), 0)
        GLES30.glUniform1i(loc("uSize"), if (horizontal) plan.geometrySize.width else plan.geometrySize.height)
        GLES30.glUniform1f(loc("uScale"), if (horizontal) plan.scaleX else plan.scaleY)
        bind(src, src)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("export resampling")
    }

    private fun outputPass(stage: Int, out: PixelBox, matrix: FloatArray, settings: OutputSettings, high: Boolean) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(0, 0, out.width, out.height)
        val p = outputProgram
        GLES30.glUseProgram(p)
        // Row-major Kotlin matrix -> GLSL column-major mat3 (transpose = false needs columns).
        val columns = FloatArray(9) { i -> matrix[(i % 3) * 3 + i / 3] }
        GLES30.glUniformMatrix3fv(GLES30.glGetUniformLocation(p, "uToOutput"), 1, false, columns, 0)
        GLES30.glUniform2i(GLES30.glGetUniformLocation(p, "uOrigin"), out.x, out.y)
        GLES30.glUniform2ui(
            GLES30.glGetUniformLocation(p, "uDither"),
            settings.ditherSeed,
            if (settings.dither) 1 else 0,
        )
        GLES30.glUniform1i(GLES30.glGetUniformLocation(p, "uHigh"), if (high) 1 else 0)
        bind(stage, stage)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("export output pass")
    }

    private fun readBand(high: Boolean): ByteBuffer =
        ByteBuffer.allocateDirect(tileSize * BAND_ROWS * (if (high) RGBA * Float.SIZE_BYTES else RGBA))
            .order(ByteOrder.nativeOrder())

    private fun readBack(out: PixelBox, band: ByteBuffer, high: Boolean, sink: TileSink) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glPixelStorei(GLES30.GL_PACK_ALIGNMENT, 1)
        var row = 0
        while (row < out.height) {
            val rows = minOf(BAND_ROWS, out.height - row)
            band.clear()
            if (high) {
                GLES30.glReadPixels(0, row, out.width, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, band)
                band.limit(out.width * rows * RGBA * Float.SIZE_BYTES)
                sink.acceptHigh(out.x, out.y + row, out.width, rows, band.asFloatBuffer())
            } else {
                GLES30.glReadPixels(0, row, out.width, rows, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, band)
                band.limit(out.width * rows * RGBA)
                sink.accept(out.x, out.y + row, out.width, rows, band)
            }
            GlSupport.checkError("export readback")
            row += rows
        }
    }

    private fun bind(unit0: Int, unit1: Int, unit2: Int = unit1) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit0)
    }

    /** Uploads image regions into the source texture in bands (small staging buffers whatever the tile size). */
    private inner class Uploader(private val source: TileSource) {
        private val staging = ByteBuffer.allocateDirect(tileSize * UPLOAD_ROWS * RGBA * Float.SIZE_BYTES)
            .order(ByteOrder.nativeOrder())
        private val floats = staging.asFloatBuffer()
        private val band = FloatArray(tileSize * UPLOAD_ROWS * RGBA)
        var watchClip = Float.MAX_VALUE
        var reachedClip = false
        var nanos = 0L

        fun upload(region: PixelBox) {
            val start = System.nanoTime()
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            var y = 0
            while (y < region.height) {
                val rows = minOf(UPLOAD_ROWS, region.height - y)
                val n = region.width * rows * RGBA
                source.read(region.x, region.y + y, region.width, rows, band)
                if (!reachedClip && watchClip != Float.MAX_VALUE) reachedClip = reaches(n)
                floats.clear()
                floats.put(band, 0, n)
                floats.flip()
                GLES30.glTexSubImage2D(
                    GLES30.GL_TEXTURE_2D, 0, 0, y, region.width, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, floats,
                )
                y += rows
            }
            GlSupport.checkError("export upload")
            nanos += System.nanoTime() - start
        }

        /** `HighlightReconstructionOp.needed` over the colour channels of the band. */
        private fun reaches(n: Int): Boolean {
            var i = 0
            while (i < n) {
                if (band[i] >= watchClip || band[i + 1] >= watchClip || band[i + 2] >= watchClip) return true
                i += RGBA
            }
            return false
        }
    }

    // ------------------------------------------------------------------ setup

    private fun initGl() {
        egl.create()
        info = GlSupport.query()
        GlSupport.require(info)
        sceneProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.SCENE_FRAG)
        developProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.DEVELOP_FRAG)
        resampleProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, ExportShaders.RESAMPLE_FRAG)
        geometryProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, ExportShaders.GEOMETRY_FRAG)
        outputProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, ExportShaders.OUTPUT_FRAG)
        block(sceneProgram, "Develop", DEVELOP_BINDING)
        block(sceneProgram, "Light", LIGHT_BINDING)
        block(developProgram, "Develop", DEVELOP_BINDING)
        block(developProgram, "Light", LIGHT_BINDING)
        block(developProgram, "Look", ColourShaders.BINDING)
        sampler(sceneProgram, "uSource", 0)
        sampler(sceneProgram, "uChroma", 1)
        sampler(developProgram, "uScene", 0)
        sampler(developProgram, "uCoef", 1)
        sampler(developProgram, "uMoments", 2)
        sampler(resampleProgram, "uSrc", 0)
        sampler(geometryProgram, "uStage", 0)
        sampler(outputProgram, "uStage", 0)
        val ids = IntArray(3)
        GLES30.glGenBuffers(3, ids, 0)
        developUbo = ids[0]
        lightUbo = ids[1]
        lookUbo = ids[2]
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, DEVELOP_BINDING, developUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, LIGHT_BINDING, lightUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, ColourShaders.BINDING, lookUbo)
        light.init()
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)
        GlSupport.checkError("export renderer setup")
        allocateWithFallback()
    }

    /** Allocates the tile textures, halving the tile while the driver reports `GL_OUT_OF_MEMORY`. */
    private fun allocateWithFallback() {
        while (true) {
            try {
                allocateTiles(tileSize)
                return
            } catch (e: GlException) {
                deleteTiles()
                if (tileSize / 2 < ExportPlan.MIN_TILE) throw e
                tileSize /= 2
            }
        }
    }

    private fun allocateTiles(size: Int) {
        sourceTex = texture(size, size, GLES30.GL_RGBA16F)
        sceneTex = texture(size, size, GLES30.GL_RGBA16F)
        workTex = texture(size, size, GLES30.GL_RGBA16F)
        rowsTex = texture(size, size, GLES30.GL_RGBA32F)
        colsTex = texture(size, size, GLES30.GL_RGBA32F)
        sceneFbo = framebuffer(sceneTex)
        workFbo = framebuffer(workTex)
        rowsFbo = framebuffer(rowsTex)
        colsFbo = framebuffer(colsTex)
        outHigh = false
        outTex = texture(size, size, GLES30.GL_RGBA8)
        outFbo = framebuffer(outTex)
    }

    private fun ensureOutputTarget(high: Boolean) {
        if (high == outHigh) return
        GLES30.glDeleteFramebuffers(1, intArrayOf(outFbo), 0)
        GLES30.glDeleteTextures(1, intArrayOf(outTex), 0)
        outTex = texture(tileSize, tileSize, if (high) GLES30.GL_RGBA32F else GLES30.GL_RGBA8)
        outFbo = framebuffer(outTex)
        outHigh = high
    }

    private fun deleteTiles() {
        val textures = intArrayOf(sourceTex, sceneTex, workTex, rowsTex, colsTex, outTex).filter { it != 0 }
        val fbos = intArrayOf(sceneFbo, workFbo, rowsFbo, colsFbo, outFbo).filter { it != 0 }
        if (textures.isNotEmpty()) GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        if (fbos.isNotEmpty()) GLES30.glDeleteFramebuffers(fbos.size, fbos.toIntArray(), 0)
        sourceTex = 0
        sceneTex = 0
        workTex = 0
        rowsTex = 0
        colsTex = 0
        outTex = 0
        sceneFbo = 0
        workFbo = 0
        rowsFbo = 0
        colsFbo = 0
        outFbo = 0
        GLES30.glGetError()
    }

    private fun texture(w: Int, h: Int, format: Int): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, format, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) throw GlException("texture ${w}x$h: GL error 0x${error.toString(HEX)}")
        return id[0]
    }

    private fun framebuffer(texture: Int): Int {
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
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) throw GlException("export framebuffer 0x${status.toString(HEX)}")
        return id[0]
    }

    private fun block(program: Int, name: String, binding: Int) {
        val index = GLES30.glGetUniformBlockIndex(program, name)
        check(index != GLES30.GL_INVALID_INDEX) { "uniform block $name missing" }
        GLES30.glUniformBlockBinding(program, index, binding)
    }

    private fun sampler(program: Int, name: String, unit: Int) {
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, name), unit)
    }

    private fun uploadUbo(buffer: Int, data: ByteBuffer) {
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, data.remaining(), data, GLES30.GL_DYNAMIC_DRAW)
    }

    override fun close() {
        runCatching {
            onGl {
                if (::info.isInitialized) {
                    deleteTiles()
                    light.release()
                    GLES30.glDeleteBuffers(3, intArrayOf(developUbo, lightUbo, lookUbo), 0)
                    GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
                    for (p in intArrayOf(
                        sceneProgram,
                        developProgram,
                        resampleProgram,
                        geometryProgram,
                        outputProgram,
                    )) {
                        GLES30.glDeleteProgram(p)
                    }
                }
                egl.release()
            }
        }
        thread.quitSafely()
    }

    /**
     * Runs [block] on the GL thread and waits for it **uninterruptibly**: the caller owns what the block reads (the
     * [TileSource], the sink) and frees it as soon as this returns, so returning early on an interrupt (a cancelled
     * export's worker) would let the GL thread read a recycled bitmap or freed LibRaw buffer. Cancellation stops the
     * block at the next tile through its `cancel` check instead; the interrupt is restored afterwards.
     */
    private fun <T> onGl(block: () -> T): T {
        val result = AtomicReference<Result<T>>()
        val latch = CountDownLatch(1)
        handler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        var interrupted = false
        while (true) {
            try {
                latch.await()
                break
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
        return result.get().getOrThrow()
    }

    companion object {
        private const val DEVELOP_BINDING = 0
        private const val LIGHT_BINDING = 2
        private const val RGBA = 4
        private const val UPLOAD_ROWS = 64
        private const val BAND_ROWS = 128
        private const val HEX = 16
        private const val NANOS_PER_MS = 1_000_000L

        /**
         * Creates a renderer with tiles of at most [tile] px (smaller when the driver cannot allocate them). Blocking;
         * throws [GlesRequirementException] when the device cannot run the pipeline.
         */
        fun create(tile: Int = ExportPlan.DEFAULT_MAX_TILE): TiledRenderer {
            val renderer = TiledRenderer(tile)
            try {
                renderer.onGl { renderer.initGl() }
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                renderer.close()
                throw e
            }
            return renderer
        }
    }
}
