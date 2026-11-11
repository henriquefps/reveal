@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred

/** Where the view looks: [scale] screen pixels per image pixel, centred on image point ([centerX], [centerY]). */
data class ViewTransform(val scale: Float = 1f, val centerX: Float = 0f, val centerY: Float = 0f)

/** Timing of one presented frame, in milliseconds, measured on the GL thread. */
data class FrameStats(
    /** Frames presented so far. */
    val frames: Long,
    /** Parameter/view updates received so far; `updates - frames` were coalesced away. */
    val updates: Long,
    /** Wall time of the last frame up to `glFinish` (only when [GpuDevelopRenderer.measureGpuTime] is on). */
    val gpuWallMs: Float,
    /** Wall time of the last frame's command submission and swap. */
    val frameMs: Float,
)

/**
 * The GPU develop pipeline: OpenGL ES 3 on a dedicated thread, float16 working textures, fused stage 1 (raw WB,
 * camera matrix, exposure, base tone) into a mip-mapped RGBA16F texture, then the view + output pass into the
 * window surface (or an RGBA8 target for readback). The UI thread never touches GL: every public method either
 * posts to the GL thread or suspends until it has run.
 *
 * **Frame coalescing:** [setRecipe], [setView] and friends only store the newest request and post at most one frame
 * task; while a frame renders, further updates overwrite the stored request and the next frame renders only the
 * latest one. Dragging a slider faster than the GPU can render therefore never builds a queue.
 *
 * Create with [create]; call [close] when done. Failures to meet the GPU requirements surface from [create] as
 * [GlesRequirementException].
 */
@Suppress("TooManyFunctions")
class GpuDevelopRenderer private constructor() : AutoCloseable {
    private val thread = HandlerThread("reveal-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()

    private data class Request(
        val recipe: Recipe,
        val showOriginal: Boolean,
        val view: ViewTransform,
        val space: OutputSpace,
    )

    private val request = AtomicReference(Request(Recipe.DEFAULT, false, ViewTransform(), OutputSpace.SRGB))
    private val framePosted = AtomicBoolean(false)
    private val updates = AtomicLong()

    /** Called on the GL thread after every presented frame. */
    @Volatile
    var onFrame: ((FrameStats) -> Unit)? = null

    /** When true each frame ends with `glFinish` so [FrameStats.gpuWallMs] is meaningful (costs some overlap). */
    @Volatile
    var measureGpuTime: Boolean = false

    /** Driver information, valid after [create] returned. */
    @Volatile
    lateinit var info: GlInfo
        private set

    // ---- GL-thread state ----
    private var developProgram = 0
    private var outputProgram = 0
    private var developUbo = 0
    private var outputUbo = 0
    private var vao = 0
    private var sourceTex = 0
    private var stageTex = 0
    private var stageFbo = 0
    private var readTex = 0
    private var readFbo = 0
    private var input: PipelineInput? = null
    private var stageParams: DevelopParams? = null
    private var frames = 0L
    private var lastStats = FrameStats(0, 0, 0f, 0f)

    // ------------------------------------------------------------------ public API (any thread)

    /** Uploads [input] (linear proxy) as a float16 texture; replaces the previous image. */
    fun setInput(input: PipelineInput) {
        handler.post {
            uploadInput(input)
            requestFrame()
        }
    }

    /** Sets the recipe to render. Coalesced. */
    fun setRecipe(recipe: Recipe) {
        update { it.copy(recipe = recipe) }
    }

    /** True renders the recipe defaults (the original) instead of the recipe, for "hold to compare". */
    fun setShowOriginal(show: Boolean) {
        update { it.copy(showOriginal = show) }
    }

    /** Sets zoom and pan. Coalesced. */
    fun setView(view: ViewTransform) {
        update { it.copy(view = view) }
    }

    /** Sets the display output space of the on-screen pass. */
    fun setOutputSpace(space: OutputSpace) {
        update { it.copy(space = space) }
    }

    /** Attaches the window [surface]; frames are presented into it. */
    fun attachSurface(surface: Surface) {
        handler.post {
            egl.attachWindow(surface)
            requestFrame()
        }
    }

    /** Detaches the window surface, blocking until the GL thread no longer uses it (call from `surfaceDestroyed`). */
    fun detachSurface() {
        val latch = CountDownLatch(1)
        handler.post {
            egl.detachWindow()
            latch.countDown()
        }
        latch.await()
    }

    /** Asks for a new frame, for example after the surface changed size. */
    fun invalidate() = requestFrame()

    /**
     * Renders [recipe] offscreen and returns the stage-1 result: linear Rec.2020 RGB, `width*height*3` floats,
     * the float16 texture read back (parity tests, export seams).
     */
    suspend fun renderLinear(recipe: Recipe): FloatArray = onGl { readLinear(recipe) }

    /** Renders [recipe] offscreen through the output transform; RGBA8, top row first (`width*height*4`). */
    suspend fun renderOutput(recipe: Recipe, settings: OutputSettings = OutputSettings()): ByteArray =
        onGl { readOutput(recipe, settings) }

    /**
     * Times full offscreen renders (stage 1 + output pass into an RGBA8 target of the image size) for each recipe,
     * in order, with `glFinish` after each; returns the wall milliseconds per frame. This is the "dragging a
     * slider" measurement without the display or the compositor.
     */
    suspend fun benchmark(recipes: List<Recipe>): List<Float> = onGl {
        val image = checkNotNull(input) { "no input" }
        recipes.map { recipe ->
            val start = System.nanoTime()
            runStage1(DevelopParams.from(image, recipe))
            drawOutputOffscreen(OutputSettings(dither = true))
            GLES20.glFinish()
            (System.nanoTime() - start) / NANOS_PER_MS
        }
    }

    override fun close() {
        val latch = CountDownLatch(1)
        handler.post {
            releaseGl()
            egl.release()
            latch.countDown()
        }
        latch.await()
        thread.quitSafely()
    }

    // ------------------------------------------------------------------ frame loop

    private fun update(change: (Request) -> Request) {
        request.updateAndGet { change(it) }
        updates.incrementAndGet()
        requestFrame()
    }

    private fun requestFrame() {
        if (framePosted.compareAndSet(false, true)) handler.post(::renderFrame)
    }

    private fun renderFrame() {
        framePosted.set(false)
        val image = input
        if (image == null || !egl.hasWindow) return
        val start = System.nanoTime()
        val req = request.get()
        val params = DevelopParams.from(image, if (req.showOriginal) Recipe.DEFAULT else req.recipe)
        if (params != stageParams) {
            runStage1(params)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        }
        val (width, height) = egl.windowSize()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        drawOutput(image, req.view, width, height, OutputSettings(req.space), SURROUND_RGB)
        val submitted = System.nanoTime()
        egl.swap()
        var gpuWall = lastStats.gpuWallMs
        if (measureGpuTime) {
            GLES20.glFinish()
            gpuWall = (System.nanoTime() - start) / NANOS_PER_MS
        }
        frames++
        lastStats = FrameStats(frames, updates.get(), gpuWall, (submitted - start) / NANOS_PER_MS)
        onFrame?.invoke(lastStats)
    }

    // ------------------------------------------------------------------ GL work (GL thread only)

    private fun runStage1(params: DevelopParams) {
        val image = checkNotNull(input) { "no input" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, stageFbo)
        GLES30.glViewport(0, 0, image.width, image.height)
        GLES30.glUseProgram(developProgram)
        uploadUbo(developUbo, developBlock(params))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("develop stage")
        stageParams = params
    }

    private fun drawOutput(
        image: PipelineInput,
        view: ViewTransform,
        width: Int,
        height: Int,
        settings: OutputSettings,
        surround: FloatArray,
    ) {
        GLES30.glUseProgram(outputProgram)
        uploadUbo(outputUbo, outputBlock(image, view, width, height, settings, surround))
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
        val magnified = view.scale >= NEAREST_ABOVE_SCALE
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            if (magnified) GLES30.GL_NEAREST else GLES30.GL_LINEAR,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("output pass")
    }

    private fun drawOutputOffscreen(settings: OutputSettings) {
        val image = checkNotNull(input) { "no input" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glViewport(0, 0, image.width, image.height)
        val identity = ViewTransform(1f, image.width / 2f, image.height / 2f)
        drawOutput(image, identity, image.width, image.height, settings, SURROUND_RGB)
    }

    private fun readLinear(recipe: Recipe): FloatArray {
        val image = checkNotNull(input) { "no input" }
        runStage1(DevelopParams.from(image, recipe))
        val pixels = FloatBuffer.allocate(image.width * image.height * RGBA)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, stageFbo)
        GLES30.glReadPixels(0, 0, image.width, image.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, pixels)
        GlSupport.checkError("readback of the working-space texture")
        val out = FloatArray(image.width * image.height * PipelineInput.CHANNELS)
        for (i in 0 until image.width * image.height) {
            out[i * PipelineInput.CHANNELS] = pixels.get(i * RGBA)
            out[i * PipelineInput.CHANNELS + 1] = pixels.get(i * RGBA + 1)
            out[i * PipelineInput.CHANNELS + 2] = pixels.get(i * RGBA + 2)
        }
        return out
    }

    private fun readOutput(recipe: Recipe, settings: OutputSettings): ByteArray {
        val image = checkNotNull(input) { "no input" }
        runStage1(DevelopParams.from(image, recipe))
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        drawOutputOffscreen(settings)
        val pixels = ByteBuffer.allocateDirect(image.width * image.height * RGBA)
        GLES30.glReadPixels(0, 0, image.width, image.height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GlSupport.checkError("readback of the output")
        val flipped = ByteArray(image.width * image.height * RGBA)
        val stride = image.width * RGBA
        for (row in 0 until image.height) {
            pixels.position((image.height - 1 - row) * stride)
            pixels.get(flipped, row * stride, stride)
        }
        return flipped
    }

    private fun initGl() {
        egl.create()
        info = GlSupport.query()
        GlSupport.require(info)
        developProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.DEVELOP_FRAG)
        outputProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.OUTPUT_FRAG)
        bindBlock(developProgram, "Develop", DEVELOP_BINDING, "uSource")
        bindBlock(outputProgram, "Output", OUTPUT_BINDING, "uStage")
        val ids = IntArray(2)
        GLES30.glGenBuffers(2, ids, 0)
        developUbo = ids[0]
        outputUbo = ids[1]
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, DEVELOP_BINDING, developUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, OUTPUT_BINDING, outputUbo)
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)
        GlSupport.checkError("renderer setup")
        probeFloatTarget()
    }

    /** Proves a RGBA16F texture is renderable on this driver, with a clear message when it is not. */
    private fun probeFloatTarget() {
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA16F, 2, 2)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            tex[0],
            0,
        )
        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glDeleteFramebuffers(1, fbo, 0)
        GLES30.glDeleteTextures(1, tex, 0)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            throw GlesRequirementException(
                "RGBA16F is not renderable on '${info.renderer}' (framebuffer status 0x${status.toString(16)})",
            )
        }
    }

    private fun bindBlock(program: Int, block: String, binding: Int, sampler: String) {
        val index = GLES30.glGetUniformBlockIndex(program, block)
        check(index != GLES30.GL_INVALID_INDEX) { "uniform block $block missing" }
        GLES30.glUniformBlockBinding(program, index, binding)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, sampler), 0)
    }

    private fun uploadInput(image: PipelineInput) {
        deleteImageTextures()
        input = image
        stageParams = null
        val levels = 32 - Integer.numberOfLeadingZeros(maxOf(image.width, image.height))
        sourceTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        stageTex = newTexture(levels, image.width, image.height, GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
        readTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST, GLES30.GL_RGBA8)
        stageFbo = newFramebuffer(stageTex)
        readFbo = newFramebuffer(readTex)
        // Upload in bands so the RGB -> RGBA float staging buffer stays small even for a 2400 px proxy.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val band = UPLOAD_ROWS.coerceAtMost(image.height)
        val staging = ByteBuffer.allocateDirect(
            image.width * band * RGBA * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        val floats = staging.asFloatBuffer()
        var y = 0
        while (y < image.height) {
            val rows = minOf(band, image.height - y)
            floats.clear()
            for (p in 0 until image.width * rows) {
                val s = (y * image.width + p) * PipelineInput.CHANNELS
                floats.put(image.linearRgb[s]).put(image.linearRgb[s + 1]).put(image.linearRgb[s + 2]).put(1f)
            }
            floats.flip()
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, y, image.width, rows, GLES30.GL_RGBA, GLES30.GL_FLOAT, floats,
            )
            y += rows
        }
        GlSupport.checkError("input upload")
    }

    private fun newTexture(levels: Int, w: Int, h: Int, min: Int, mag: Int, format: Int = GLES30.GL_RGBA16F): Int {
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, levels, format, w, h)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, min)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, mag)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return id[0]
    }

    private fun newFramebuffer(texture: Int): Int {
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
            throw GlException("Framebuffer incomplete: 0x${status.toString(16)}")
        }
        return id[0]
    }

    private fun deleteImageTextures() {
        GLES30.glDeleteTextures(3, intArrayOf(sourceTex, stageTex, readTex), 0)
        GLES30.glDeleteFramebuffers(2, intArrayOf(stageFbo, readFbo), 0)
        sourceTex = 0
        stageTex = 0
        readTex = 0
        stageFbo = 0
        readFbo = 0
    }

    private fun releaseGl() {
        if (!::info.isInitialized) return
        deleteImageTextures()
        GLES30.glDeleteBuffers(2, intArrayOf(developUbo, outputUbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(developProgram)
        GLES30.glDeleteProgram(outputProgram)
    }

    private fun uploadUbo(buffer: Int, data: ByteBuffer) {
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, data.remaining(), data, GLES30.GL_DYNAMIC_DRAW)
    }

    // ------------------------------------------------------------------ threading helpers

    private suspend fun <T> onGl(block: () -> T): T {
        val result = CompletableDeferred<T>()
        handler.post {
            try {
                result.complete(block())
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                result.completeExceptionally(e)
            }
        }
        return result.await()
    }

    companion object {
        private const val DEVELOP_BINDING = 0
        private const val OUTPUT_BINDING = 1
        private const val RGBA = 4
        private const val UPLOAD_ROWS = 128
        private const val NANOS_PER_MS = 1_000_000f
        private const val NEAREST_ABOVE_SCALE = 2f

        /** The neutral surround outside the image, display-encoded `#1c1c1e`. */
        // TODO(theme worker): take this from EditorTheme once the theme is merged.
        private val SURROUND_RGB = floatArrayOf(0x1c / 255f, 0x1c / 255f, 0x1e / 255f)

        /**
         * Creates a renderer, building its GL thread, context and programs, and fails with
         * [GlesRequirementException] when the device cannot run it.
         */
        suspend fun create(): GpuDevelopRenderer {
            val renderer = GpuDevelopRenderer()
            try {
                renderer.onGl { renderer.initGl() }
            } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
                renderer.close()
                throw e
            }
            return renderer
        }

        /** std140 block of the develop stage: wb (vec4), cameraToWorking (mat4), exposure (vec4), baseTone (vec4). */
        internal fun developBlock(p: DevelopParams): ByteBuffer {
            val b = ByteBuffer.allocateDirect(DEVELOP_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            b.putFloat(p.wbMultipliers[0]).putFloat(p.wbMultipliers[1]).putFloat(p.wbMultipliers[2]).putFloat(0f)
            putMat4(b, p.cameraToWorking)
            b.putFloat(p.exposureGain).putFloat(0f).putFloat(0f).putFloat(0f)
            b.putFloat(p.toneKnee).putFloat(0f).putFloat(0f).putFloat(0f)
            b.flip()
            return b
        }

        /** std140 block of the output pass: mat4, image (vec4), viewport (vec4), surround (vec4), dither (uvec4). */
        internal fun outputBlock(
            image: PipelineInput,
            view: ViewTransform,
            width: Int,
            height: Int,
            settings: OutputSettings,
            surround: FloatArray,
        ): ByteBuffer {
            val b = ByteBuffer.allocateDirect(OUTPUT_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            putMat4(b, OutputTransformOp.workingToOutput(settings.space).toFloatArray())
            b.putFloat(image.width.toFloat()).putFloat(image.height.toFloat()).putFloat(view.scale).putFloat(0f)
            b.putFloat(view.centerX).putFloat(view.centerY).putFloat(width.toFloat()).putFloat(height.toFloat())
            b.putFloat(surround[0]).putFloat(surround[1]).putFloat(surround[2]).putFloat(1f)
            b.putInt(settings.ditherSeed).putInt(if (settings.dither) 1 else 0).putInt(0).putInt(0)
            b.flip()
            return b
        }

        private fun putMat4(b: ByteBuffer, rowMajor3: FloatArray) {
            for (col in 0 until 3) {
                for (row in 0 until 3) b.putFloat(rowMajor3[row * 3 + col])
                b.putFloat(0f)
            }
            b.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
        }

        private const val DEVELOP_BLOCK_FLOATS = 28
        private const val OUTPUT_BLOCK_FLOATS = 32
    }
}
