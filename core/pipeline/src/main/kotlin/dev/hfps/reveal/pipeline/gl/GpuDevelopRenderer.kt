@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.GlobalToneOp
import dev.hfps.reveal.pipeline.cpu.HighlightReconstructionOp
import dev.hfps.reveal.pipeline.cpu.LowResGrid
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
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
 * The GPU develop pipeline: OpenGL ES 3 on a dedicated thread, float16 working textures. The scene stage (raw WB,
 * highlight reconstruction, camera matrix, exposure) writes a scene-linear RGBA16F texture; the Light group's
 * low-res passes ([LightPasses], skipped when not needed) read it; the develop stage (highlights/shadows, base tone,
 * contrast/whites/blacks) writes the mip-mapped RGBA16F working texture; then the view + output pass draws into the
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
@Suppress("TooManyFunctions", "LargeClass")
class GpuDevelopRenderer private constructor() : AutoCloseable {
    private val thread = HandlerThread("reveal-gl").apply { start() }
    private val handler = Handler(thread.looper)
    private val egl = EglCore()

    private data class Request(
        val recipe: Recipe,
        val showOriginal: Boolean,
        val view: ViewTransform,
        val space: OutputSpace,
        val frame: ViewFrame? = null,
        val compare: CompareMode = CompareMode.Off,
        val clipping: ClippingWarnings = ClippingWarnings.NONE,
        val loupe: Loupe? = null,
        val geometryEditing: Boolean = false,
    )

    @Volatile
    private var surround: FloatArray = DEFAULT_SURROUND

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
    private var sceneProgram = 0
    private var developProgram = 0
    private var lightUbo = 0
    private val light = LightPasses()
    private var sceneTex = 0
    private var sceneFbo = 0
    private var reconKey: Pair<List<Float>, Any>? = null
    private var outputProgram = 0
    private var developUbo = 0
    private var outputUbo = 0
    private var lookUbo = 0
    private var vao = 0
    private var sourceTex = 0
    private var stageTex = 0
    private var stageFbo = 0
    private var readTex = 0
    private var readFbo = 0
    private var readWidth = 0
    private var readHeight = 0
    private var shownMapping = GeometryMapping.identity(1, 1)
    private var input: UploadedImage? = null
    private var stageParams: DevelopParams? = null
    private var sceneKey: SceneKey? = null
    private var guideValid = false
    private var originalTex = 0
    private var originalFbo = 0
    private var originalValid = false
    private var sourceFbo = 0
    private val histogram = HistogramReadback()
    private val profiler = PassProfiler()
    private var histogramDirty = false
    private var histogramPosted = false
    private var histogramPollPosted = false
    private var frames = 0L
    private var lastStats = FrameStats(0, 0, 0f, 0f)

    // ------------------------------------------------------------------ public API (any thread)

    /**
     * Uploads [input] (linear proxy) as a float16 texture; replaces the previous image. The renderer keeps only the
     * texture and the colour metadata, never the pixel array, so the caller's copy is the last one on the heap.
     */
    fun setInput(input: PipelineInput) {
        handler.post {
            uploadInput(input)
            requestFrame()
        }
    }

    /** [setInput] that returns once the texture is uploaded (the caller can then drop [input] and time the upload). */
    suspend fun upload(input: PipelineInput) = onGl {
        uploadInput(input)
        requestFrame()
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

    /**
     * Sets the visible part of the window where the photo is framed (window pixels); `null` = the whole window. The
     * [ViewTransform] of [setView] is in frame terms (see [ViewFrame]). Coalesced.
     */
    fun setFrame(frame: ViewFrame?) {
        update { it.copy(frame = frame) }
    }

    /** Sets the before/after comparison drawn on screen. Coalesced. */
    fun setCompare(mode: CompareMode) {
        update { it.copy(compare = mode) }
    }

    /** Shows or hides (`null`) the magnifier of the eyedropper. Coalesced. */
    fun setLoupe(loupe: Loupe?) {
        update { it.copy(loupe = loupe) }
    }

    /**
     * The crop mode: true shows the **editing plane** ([GeometryMapping.editing]: the whole image straightened and
     * oriented, empty areas in the surround) instead of the geometry operator's output; the view ([setView]) is then
     * in plane coordinates. Compare panes are off while editing. Coalesced.
     */
    fun setGeometryEditing(editing: Boolean) {
        update { it.copy(geometryEditing = editing) }
    }

    /** Turns the on-screen clipping overlays on or off (see [ClippingWarnings]). Coalesced. */
    fun setClippingWarnings(warnings: ClippingWarnings) {
        update { it.copy(clipping = warnings) }
    }

    /** Called on the GL thread with each new histogram of the displayed photo while [histogramEnabled]. */
    @Volatile
    var onHistogram: ((Histogram) -> Unit)? = null

    /** Computes a histogram of the displayed photo after it changes, rate-limited (see [HistogramReadback]). */
    @Volatile
    var histogramEnabled: Boolean = false
        set(value) {
            field = value
            if (value) {
                handler.post {
                    histogramDirty = true
                    requestFrame()
                }
            }
        }

    /**
     * Per-pass profiling of on-screen frames (each section ends with `glFinish`; measurements only). Read the means
     * with [passProfile]; setting it resets them.
     */
    var profilePasses: Boolean
        get() = profiler.enabled
        set(value) {
            handler.post {
                profiler.reset()
                profiler.enabled = value
            }
        }

    /** Mean milliseconds per section of the profiled frames (see [profilePasses]). */
    suspend fun passProfile(): Map<String, Float> = onGl { profiler.snapshot() }

    /** GL-thread milliseconds of the last histogram (request draw + map + binning), for measurements. */
    val histogramMs: Float get() = histogram.lastMs

    /** GL-thread milliseconds of the last histogram request alone (draw + readback issue). */
    val histogramRequestMs: Float get() = histogram.lastRequestMs

    /** Milliseconds from the last histogram request to its result (GPU latency included). */
    val histogramLatencyMs: Float get() = histogram.lastLatencyMs

    /** Histogram readbacks so far. */
    val histogramCount: Long get() = histogram.count

    /**
     * Mean of the **source** pixels (the uploaded input before any develop step: un-balanced camera RGB for a RAW)
     * in the square of half-size [radius] around image pixel ([x], [y]), clipped to the image; for the eyedropper.
     */
    suspend fun sampleSource(x: Int, y: Int, radius: Int): FloatArray = onGl { readSourceMean(x, y, radius) }

    /** Sets the neutral colour around the photo from an opaque `0xRRGGBB` value (display-encoded). */
    fun setSurround(rgb: Int) {
        surround = floatArrayOf((rgb shr 16 and 0xff) / 255f, (rgb shr 8 and 0xff) / 255f, (rgb and 0xff) / 255f)
        requestFrame()
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

    /** [renderOutput] with the clipping overlays drawn as on screen (tests of the output-pass flag). */
    suspend fun renderOutput(recipe: Recipe, settings: OutputSettings, clipping: ClippingWarnings): ByteArray =
        onGl { readOutput(recipe, settings, clipping) }

    /**
     * Times full offscreen renders (stage 1 + output pass into an RGBA8 target of the image size) for each recipe,
     * in order, with `glFinish` after each; returns the wall milliseconds per frame. This is the "dragging a
     * slider" measurement without the display or the compositor.
     */
    suspend fun benchmark(recipes: List<Recipe>): List<Float> = onGl {
        val image = checkNotNull(input) { "no input" }
        recipes.map { recipe ->
            val start = System.nanoTime()
            runStage1(DevelopParams.from(image.colour, recipe))
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            drawOutputOffscreen(
                OutputSettings(dither = true),
                mapping = GeometryMapping.identity(image.width, image.height),
            )
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
        profiler.begin()
        val start = System.nanoTime()
        val req = request.get()
        val (width, height) = egl.windowSize()
        val frame = req.frame ?: ViewFrame.whole(width, height)
        val compare = if (req.showOriginal || req.geometryEditing) CompareMode.Off else req.compare
        // Geometry (slot 13) is part of the view: "show original" keeps the crop so the photo does not jump.
        val geometry = req.recipe.geometry
        val mapping = if (req.geometryEditing) {
            GeometryMapping.editing(geometry, image.width, image.height)
        } else {
            GeometryMapping.of(geometry, image.width, image.height)
        }
        if (!req.geometryEditing) shownMapping = mapping
        val panes = CompareLayout.panes(
            compare,
            frame,
            width,
            height,
            mapping.outputWidth,
            mapping.outputHeight,
            req.view,
        ) + listOfNotNull(req.loupe?.let { CompareLayout.loupePane(it, width, height) })
        if (panes.any { it.original }) ensureOriginal(image)
        val params = DevelopParams.from(image.colour, if (req.showOriginal) Recipe.DEFAULT else req.recipe)
        profiler.mark("params (CPU)")
        if (params != stageParams) {
            runStage1(params)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            profiler.mark("mipmaps")
            histogramDirty = true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        drawPanes(image, panes, width, height, req, mapping)
        profiler.mark("output pass")
        val submitted = System.nanoTime()
        egl.swap()
        profiler.mark("swap")
        var gpuWall = lastStats.gpuWallMs
        if (measureGpuTime) {
            GLES20.glFinish()
            gpuWall = (System.nanoTime() - start) / NANOS_PER_MS
        }
        frames++
        lastStats = FrameStats(frames, updates.get(), gpuWall, (submitted - start) / NANOS_PER_MS)
        onFrame?.invoke(lastStats)
        maybeMeasureHistogram()
    }

    // ------------------------------------------------------------------ GL work (GL thread only)

    /** Scene stage, the Light passes that are needed, then the develop stage into the working texture. */
    private fun runStage1(params: DevelopParams) {
        val image = checkNotNull(input) { "no input" }
        val hp = params.highlights
        val reconstruct = hp != null && image.needsReconstruction
        if (reconstruct) {
            val key = params.wbMultipliers.toList() to checkNotNull(hp)
            if (key != reconKey) {
                light.reconstruct(sourceTex, params.wbMultipliers, hp)
                reconKey = key
                profiler.mark("highlight reconstruction")
            }
        }
        uploadUbo(developUbo, developBlock(params))
        uploadUbo(lightUbo, lightBlock(params, image.grid, reconstruct))
        uploadUbo(lookUbo, ColourShaders.lookBlock(params))
        // The scene texture and the guide depend only on what reaches scene-linear light (white balance, matrix,
        // exposure, reconstruction): a drag of a tone slider re-runs only the develop pass.
        val scene = SceneKey(
            params.wbMultipliers.toList(),
            params.cameraToWorking.toList(),
            params.exposureGain,
            if (reconstruct) hp else null,
        )
        if (scene != sceneKey) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
            GLES30.glViewport(0, 0, image.width, image.height)
            GLES30.glUseProgram(sceneProgram)
            bindTextures(sourceTex, if (reconstruct) light.chromaTexture else sourceTex)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
            GlSupport.checkError("scene stage")
            profiler.mark("scene pass")
            sceneKey = scene
            guideValid = false
        }
        if (params.tone.localActive && !guideValid) {
            light.guide(sceneTex)
            guideValid = true
            profiler.mark("guided filter")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, stageFbo)
        GLES30.glViewport(0, 0, image.width, image.height)
        GLES30.glUseProgram(developProgram)
        val local = params.tone.localActive
        bindTextures(
            sceneTex,
            if (local) light.coefTexture else sceneTex,
            if (local) light.momentsTexture else sceneTex,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("develop stage")
        profiler.mark("develop pass")
        stageParams = params
    }

    private fun bindTextures(unit0: Int, unit1: Int, unit2: Int = unit1) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit0)
    }

    @Suppress("LongParameterList")
    private fun drawOutput(
        image: UploadedImage,
        view: ViewTransform,
        width: Int,
        height: Int,
        settings: OutputSettings,
        surround: FloatArray,
        texture: Int = stageTex,
        clipping: ClippingWarnings = ClippingWarnings.NONE,
        mapping: GeometryMapping = GeometryMapping.identity(image.width, image.height),
    ) {
        GLES30.glUseProgram(outputProgram)
        uploadUbo(
            outputUbo,
            outputBlock(image.width, image.height, view, width, height, settings, surround, clipping.bits, mapping),
        )
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        val magnified = OutputSampling.nearestMagnification(view.scale)
        GLES30.glTexParameteri(
            GLES30.GL_TEXTURE_2D,
            GLES30.GL_TEXTURE_MAG_FILTER,
            if (magnified) GLES30.GL_NEAREST else GLES30.GL_LINEAR,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("output pass")
    }

    /** The output pass at 1:1 into the RGBA8 read target sized to [mapping]'s output. */
    private fun drawOutputOffscreen(
        settings: OutputSettings,
        clipping: ClippingWarnings = ClippingWarnings.NONE,
        mapping: GeometryMapping,
    ) {
        val image = checkNotNull(input) { "no input" }
        val w = mapping.outputWidth
        val h = mapping.outputHeight
        ensureReadTarget(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glViewport(0, 0, w, h)
        val identity = ViewTransform(1f, w / 2f, h / 2f)
        drawOutput(image, identity, w, h, settings, surround, clipping = clipping, mapping = mapping)
    }

    /** (Re)allocates the RGBA8 readback target when the output size changes (crop, quarter turns). */
    private fun ensureReadTarget(width: Int, height: Int) {
        if (readTex != 0 && readWidth == width && readHeight == height) return
        if (readTex != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(readTex), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(readFbo), 0)
        }
        readTex = newTexture(1, width, height, GLES30.GL_NEAREST, GLES30.GL_NEAREST, GLES30.GL_RGBA8)
        readFbo = newFramebuffer(readTex)
        readWidth = width
        readHeight = height
    }

    private fun readLinear(recipe: Recipe): FloatArray {
        val image = checkNotNull(input) { "no input" }
        runStage1(DevelopParams.from(image.colour, recipe))
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

    private fun readOutput(
        recipe: Recipe,
        settings: OutputSettings,
        clipping: ClippingWarnings = ClippingWarnings.NONE,
    ): ByteArray {
        val image = checkNotNull(input) { "no input" }
        runStage1(DevelopParams.from(image.colour, recipe))
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        val mapping = GeometryMapping.of(recipe.geometry, image.width, image.height)
        drawOutputOffscreen(settings, clipping, mapping)
        val w = mapping.outputWidth
        val h = mapping.outputHeight
        val pixels = ByteBuffer.allocateDirect(w * h * RGBA)
        GLES30.glReadPixels(0, 0, w, h, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GlSupport.checkError("readback of the output")
        val flipped = ByteArray(w * h * RGBA)
        val stride = w * RGBA
        for (row in 0 until h) {
            pixels.position((h - 1 - row) * stride)
            pixels.get(flipped, row * stride, stride)
        }
        return flipped
    }

    private fun initGl() {
        egl.create()
        info = GlSupport.query()
        GlSupport.require(info)
        sceneProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.SCENE_FRAG)
        developProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.DEVELOP_FRAG)
        outputProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.OUTPUT_FRAG)
        bindBlock(sceneProgram, "Develop", DEVELOP_BINDING, "uSource")
        bindBlock(sceneProgram, "Light", LIGHT_BINDING, "uChroma", unit = 1)
        bindBlock(developProgram, "Develop", DEVELOP_BINDING, "uScene")
        bindBlock(developProgram, "Light", LIGHT_BINDING, "uCoef", unit = 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(developProgram, "uMoments"), 2)
        bindBlock(outputProgram, "Output", OUTPUT_BINDING, "uStage")
        val ids = IntArray(4)
        val lookIndex = GLES30.glGetUniformBlockIndex(developProgram, "Look")
        check(lookIndex != GLES30.GL_INVALID_INDEX) { "uniform block Look missing" }
        GLES30.glUniformBlockBinding(developProgram, lookIndex, ColourShaders.BINDING)
        GLES30.glGenBuffers(4, ids, 0)
        developUbo = ids[0]
        outputUbo = ids[1]
        lightUbo = ids[2]
        lookUbo = ids[3]
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, DEVELOP_BINDING, developUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, OUTPUT_BINDING, outputUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, LIGHT_BINDING, lightUbo)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, ColourShaders.BINDING, lookUbo)
        light.init()
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

    private fun bindBlock(program: Int, block: String, binding: Int, sampler: String, unit: Int = 0) {
        val index = GLES30.glGetUniformBlockIndex(program, block)
        check(index != GLES30.GL_INVALID_INDEX) { "uniform block $block missing" }
        GLES30.glUniformBlockBinding(program, index, binding)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, sampler), unit)
    }

    private fun uploadInput(image: PipelineInput) {
        deleteImageTextures()
        val grid = light.allocate(image.width, image.height)
        val reconstruct = image.saturation?.let { HighlightReconstructionOp.needed(image.linearRgb, it) } ?: false
        input = UploadedImage(image.width, image.height, image.colourOnly(), grid, reconstruct)
        stageParams = null
        reconKey = null
        sceneKey = null
        guideValid = false
        val levels = 32 - Integer.numberOfLeadingZeros(maxOf(image.width, image.height))
        sourceTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        stageTex = newTexture(levels, image.width, image.height, GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
        sceneTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        sceneFbo = newFramebuffer(sceneTex)
        stageFbo = newFramebuffer(stageTex)
        shownMapping = GeometryMapping.identity(image.width, image.height)
        // Upload in bands so the RGB -> RGBA float staging buffer stays small even for a 2400 px proxy.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sourceTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val band = UPLOAD_ROWS.coerceAtMost(image.height)
        val staging = ByteBuffer.allocateDirect(
            image.width * band * RGBA * Float.SIZE_BYTES,
        ).order(ByteOrder.nativeOrder())
        val floats = staging.asFloatBuffer()
        // RGB -> RGBA in a heap array, then one bulk put: element-wise puts into a direct buffer cost ~4x more.
        val band4 = FloatArray(image.width * band * RGBA)
        val rgb = image.linearRgb
        var y = 0
        while (y < image.height) {
            val rows = minOf(band, image.height - y)
            val pixels = image.width * rows
            var s = y * image.width * PipelineInput.CHANNELS
            var d = 0
            repeat(pixels) {
                band4[d] = rgb[s]
                band4[d + 1] = rgb[s + 1]
                band4[d + 2] = rgb[s + 2]
                band4[d + 3] = 1f
                s += PipelineInput.CHANNELS
                d += RGBA
            }
            floats.clear()
            floats.put(band4, 0, pixels * RGBA)
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
        releaseCompareAndReadbacks()
        GLES30.glDeleteTextures(4, intArrayOf(sourceTex, stageTex, readTex, sceneTex), 0)
        GLES30.glDeleteFramebuffers(3, intArrayOf(stageFbo, readFbo, sceneFbo), 0)
        sceneTex = 0
        sceneFbo = 0
        sourceTex = 0
        stageTex = 0
        readTex = 0
        stageFbo = 0
        readFbo = 0
        readWidth = 0
        readHeight = 0
    }

    private fun releaseGl() {
        if (!::info.isInitialized) return
        deleteImageTextures()
        light.release()
        GLES30.glDeleteBuffers(4, intArrayOf(developUbo, outputUbo, lightUbo, lookUbo), 0)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(sceneProgram)
        GLES30.glDeleteProgram(developProgram)
        GLES30.glDeleteProgram(outputProgram)
    }

    private fun uploadUbo(buffer: Int, data: ByteBuffer) {
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, data.remaining(), data, GLES30.GL_DYNAMIC_DRAW)
    }

    // ------------------------------------------------------------------ compare, histogram, sampling (GL thread)

    /** Draws the output pass once per compare pane, scissored to the pane where it has a clip. */
    @Suppress("LongParameterList")
    private fun drawPanes(
        image: UploadedImage,
        panes: List<ComparePane>,
        width: Int,
        height: Int,
        req: Request,
        mapping: GeometryMapping,
    ) {
        val settings = OutputSettings(req.space)
        if (panes.first().clip != null) {
            // Side by side: the gap between the panes is the surround.
            GLES30.glClearColor(surround[0], surround[1], surround[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        }
        for (pane in panes) {
            val clip = pane.clip
            if (clip == null) {
                GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
            } else {
                val x = clip.left.roundToInt()
                val top = clip.top.roundToInt()
                val w = (clip.left + clip.width).roundToInt() - x
                val h = (clip.top + clip.height).roundToInt() - top
                GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
                GLES30.glScissor(x, height - (top + h), w.coerceAtLeast(0), h.coerceAtLeast(0))
            }
            drawOutput(
                image = image,
                view = pane.view,
                width = width,
                height = height,
                settings = settings,
                surround = surround,
                texture = if (pane.original) originalTex else stageTex,
                clipping = if (pane.original || req.geometryEditing) ClippingWarnings.NONE else req.clipping,
                mapping = mapping,
            )
        }
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        if (panes.none { it.original }) releaseOriginal()
    }

    /**
     * Keeps a developed copy of the original (default recipe) for split and side-by-side views: stage 1 runs with
     * the defaults, the stage texture is blitted into its own mip-mapped texture, and the stage is marked stale so
     * the edit is developed again. Freed as soon as no pane shows the original.
     */
    private fun ensureOriginal(image: UploadedImage) {
        if (originalValid) return
        if (originalTex == 0) {
            val levels = 32 - Integer.numberOfLeadingZeros(maxOf(image.width, image.height))
            originalTex = newTexture(
                levels,
                image.width,
                image.height,
                GLES30.GL_LINEAR_MIPMAP_LINEAR,
                GLES30.GL_LINEAR,
            )
            originalFbo = newFramebuffer(originalTex)
        }
        runStage1(DevelopParams.from(image.colour, Recipe.DEFAULT))
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, stageFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, originalFbo)
        GLES30.glBlitFramebuffer(
            0, 0, image.width, image.height, 0, 0, image.width, image.height,
            GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST,
        )
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, originalTex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GlSupport.checkError("original for compare")
        stageParams = null
        originalValid = true
    }

    private fun releaseOriginal() {
        if (originalTex == 0) return
        GLES30.glDeleteTextures(1, intArrayOf(originalTex), 0)
        GLES30.glDeleteFramebuffers(1, intArrayOf(originalFbo), 0)
        originalTex = 0
        originalFbo = 0
        originalValid = false
    }

    /**
     * After a presented frame: starts a histogram readback of the displayed stage now, or once the rate limit allows
     * (trailing update); a readback in flight is polled until the GPU finished it.
     */
    @Suppress("ReturnCount")
    private fun maybeMeasureHistogram() {
        if (!histogramEnabled || onHistogram == null) return
        if (histogram.pending) {
            scheduleHistogramPoll()
            return
        }
        if (!histogramDirty) return
        val image = input ?: return
        val wait = histogram.waitMs()
        if (wait == 0L) {
            profiler.begin()
            // The histogram is of the picture as it will be exported: the geometry operator's output.
            val mapping = shownMapping
            histogram.request(mapping.outputWidth, mapping.outputHeight) { scale, w, h ->
                val view = ViewTransform(scale, mapping.outputWidth / 2f, mapping.outputHeight / 2f)
                drawOutput(image, view, w, h, OutputSettings(dither = false), surround, mapping = mapping)
            }
            profiler.mark("histogram request")
            histogramDirty = false
            scheduleHistogramPoll()
        } else if (!histogramPosted) {
            histogramPosted = true
            handler.postDelayed({
                histogramPosted = false
                requestFrame()
            }, wait)
        }
    }

    private fun scheduleHistogramPoll() {
        if (histogramPollPosted) return
        histogramPollPosted = true
        handler.postDelayed({
            histogramPollPosted = false
            val result = histogram.poll()
            when {
                result != null -> {
                    onHistogram?.invoke(result)
                    // The photo changed meanwhile: the next frame (re-rendered if needed) measures it.
                    if (histogramDirty) requestFrame()
                }

                histogram.pending -> scheduleHistogramPoll()
            }
        }, HistogramReadback.POLL_MS)
    }

    private fun readSourceMean(x: Int, y: Int, radius: Int): FloatArray {
        val image = checkNotNull(input) { "no input" }
        if (sourceFbo == 0) sourceFbo = newFramebuffer(sourceTex)
        val x0 = (x - radius).coerceIn(0, image.width - 1)
        val x1 = (x + radius).coerceIn(0, image.width - 1)
        val y0 = (y - radius).coerceIn(0, image.height - 1)
        val y1 = (y + radius).coerceIn(0, image.height - 1)
        val w = x1 - x0 + 1
        val h = y1 - y0 + 1
        val pixels = FloatBuffer.allocate(w * h * RGBA)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sourceFbo)
        // Texture row 0 is the image's top row (uploadInput), and framebuffer y addresses texture rows directly.
        GLES30.glReadPixels(x0, y0, w, h, GLES30.GL_RGBA, GLES30.GL_FLOAT, pixels)
        GlSupport.checkError("source sample")
        val sum = DoubleArray(PipelineInput.CHANNELS)
        for (i in 0 until w * h) for (c in 0 until PipelineInput.CHANNELS) sum[c] += pixels.get(i * RGBA + c).toDouble()
        return FloatArray(PipelineInput.CHANNELS) { (sum[it] / (w * h)).toFloat() }
    }

    private fun releaseCompareAndReadbacks() {
        releaseOriginal()
        if (sourceFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(sourceFbo), 0)
        sourceFbo = 0
        histogram.release()
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
        private const val LIGHT_BINDING = 2
        private const val RGBA = 4
        private const val UPLOAD_ROWS = 128
        private const val NANOS_PER_MS = 1_000_000f

        /** Default surround outside the image, display-encoded `#1C1C1C` (the editor's `EditorNeutrals.Surround`). */
        private val DEFAULT_SURROUND = floatArrayOf(0x1c / 255f, 0x1c / 255f, 0x1c / 255f)

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

        /** std140 block of the develop stage: wb (vec4), cameraToWorking (mat4), exposure (vec4). */
        internal fun developBlock(p: DevelopParams): ByteBuffer {
            val b = ByteBuffer.allocateDirect(DEVELOP_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            b.putFloat(p.wbMultipliers[0]).putFloat(p.wbMultipliers[1]).putFloat(p.wbMultipliers[2]).putFloat(0f)
            putMat4(b, p.cameraToWorking)
            b.putFloat(p.exposureGain).putFloat(0f).putFloat(0f).putFloat(0f)
            b.flip()
            return b
        }

        /**
         * std140 block of the Light group: grid (cell, size, reconstruction on), clip levels (rgb, max),
         * tone (highlight stops, shadow stops, local on, global on), tone2 (contrast gamma, whites, blacks, pivot).
         */
        internal fun lightBlock(p: DevelopParams, grid: LowResGrid, reconstruct: Boolean): ByteBuffer {
            val b = ByteBuffer.allocateDirect(LIGHT_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            b.putFloat(grid.cell.toFloat()).putFloat(grid.gridWidth.toFloat()).putFloat(grid.gridHeight.toFloat())
                .putFloat(if (reconstruct) 1f else 0f)
            val hp = p.highlights
            if (hp != null) {
                b.putFloat(hp.clipR).putFloat(hp.clipG).putFloat(hp.clipB).putFloat(hp.maxClip)
            } else {
                b.putFloat(1f).putFloat(1f).putFloat(1f).putFloat(1f)
            }
            val t = p.tone
            b.putFloat(t.highlightStops).putFloat(t.shadowStops).putFloat(if (t.localActive) 1f else 0f)
                .putFloat(if (t.globalActive) 1f else 0f)
            b.putFloat(t.contrastGamma).putFloat(t.whitesShift).putFloat(t.blacksShift).putFloat(GlobalToneOp.PIVOT)
            b.flip()
            return b
        }

        /**
         * std140 block of the output pass: mat4, image (vec4), viewport (vec4: output point at the view centre, view
         * size), surround (vec4), dither (uvec4: seed, on, sampling mode, clipping-warning bits of
         * [ClippingWarnings]), the geometry mapping's rows (2 x vec4: output -> image, output size) and its flags
         * (uvec4: snap to output pixels, empty = surround).
         */
        @Suppress("LongParameterList")
        internal fun outputBlock(
            imageWidth: Int,
            imageHeight: Int,
            view: ViewTransform,
            width: Int,
            height: Int,
            settings: OutputSettings,
            surround: FloatArray,
            clippingBits: Int = 0,
            mapping: GeometryMapping = GeometryMapping.identity(imageWidth, imageHeight),
        ): ByteBuffer {
            val b = ByteBuffer.allocateDirect(OUTPUT_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            putMat4(b, OutputTransformOp.workingToOutput(settings.space).toFloatArray())
            b.putFloat(
                imageWidth.toFloat(),
            ).putFloat(imageHeight.toFloat()).putFloat(view.scale).putFloat(OutputSampling.lod(view.scale))
            b.putFloat(view.centerX).putFloat(view.centerY).putFloat(width.toFloat()).putFloat(height.toFloat())
            b.putFloat(surround[0]).putFloat(surround[1]).putFloat(surround[2]).putFloat(1f)
            val aligned = mapping.axisAligned
            b.putInt(settings.ditherSeed).putInt(if (settings.dither) 1 else 0)
                .putInt(OutputSampling.mode(view.scale, aligned)).putInt(clippingBits)
            val m = mapping.toImageFloats()
            b.putFloat(m[0]).putFloat(m[1]).putFloat(m[2]).putFloat(mapping.outputWidth.toFloat())
            b.putFloat(m[3]).putFloat(m[4]).putFloat(m[5]).putFloat(mapping.outputHeight.toFloat())
            b.putInt(if (OutputSampling.snapToOutputPixels(view.scale, aligned)) 1 else 0)
                .putInt(if (mapping.emptyIsSurround) 1 else 0).putInt(0).putInt(0)
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

        private const val DEVELOP_BLOCK_FLOATS = 24
        private const val LIGHT_BLOCK_FLOATS = 16
        private const val OUTPUT_BLOCK_FLOATS = 44
    }
}

/**
 * What the GL thread keeps of an uploaded input: its size, a 1x1 stand-in carrying the colour metadata, the Light
 * grid, and whether any pixel reaches the clip ramp (otherwise highlight reconstruction is skipped: it would be the
 * identity).
 */
private class UploadedImage(
    val width: Int,
    val height: Int,
    val colour: PipelineInput,
    val grid: LowResGrid,
    val needsReconstruction: Boolean,
)

/** The same input without its pixels (one black pixel): enough for [DevelopParams.from]. */
private fun PipelineInput.colourOnly(): PipelineInput =
    PipelineInput(kind, 1, 1, FloatArray(PipelineInput.CHANNELS), cameraToXyz, asShotMultipliers?.copyOf(), saturation)

/** What the scene pass (and so the guide) depends on: when it is unchanged, both are reused. */
private data class SceneKey(
    val wb: List<Float>,
    val matrix: List<Float>,
    val exposureGain: Float,
    val highlights: Any?,
)
