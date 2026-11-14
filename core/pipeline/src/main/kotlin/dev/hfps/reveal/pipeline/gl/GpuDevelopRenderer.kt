@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.EffectsParams
import dev.hfps.reveal.pipeline.cpu.GlobalToneOp
import dev.hfps.reveal.pipeline.cpu.HighlightReconstructionOp
import dev.hfps.reveal.pipeline.cpu.LowResGrid
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.cpu.PostParams
import dev.hfps.reveal.pipeline.cpu.VignetteOp
import dev.hfps.reveal.pipeline.cpu.withoutLocal
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.geometry.Warp
import dev.hfps.reveal.pipeline.heal.HealEngine
import dev.hfps.reveal.pipeline.heal.HealOp
import dev.hfps.reveal.pipeline.heal.PixelReader
import dev.hfps.reveal.pipeline.heal.RemoveContext
import dev.hfps.reveal.pipeline.heal.RemoveSource
import dev.hfps.reveal.pipeline.heal.SourcePicker
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.mask.Dabs
import dev.hfps.reveal.pipeline.mask.MaskBounds
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.mask.MaskParams
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.zoom.PixelAlignment
import dev.hfps.reveal.pipeline.zoom.ZoomGrid
import dev.hfps.reveal.pipeline.zoom.ZoomView
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
        val detailMask: Boolean = false,
        val maskOverlay: MaskOverlay? = null,
        val spots: SpotsView? = null,
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
    private val detail = DetailPasses().also { it.profile = { section -> profiler.mark(section) } }
    private val effects = EffectsPasses()
    private var labTarget: GlTarget? = null
    private var detailTargets: DetailPasses.Targets? = null
    private val chainTargets = ArrayList<GlTarget>()
    private var timerQueries = false

    /** The working texture's mips match its level 0 (they are generated only when a draw reads them). */
    private var stageMipsValid = false
    private var effectsGridValid = false
    private val defringe = DefringePasses()
    private var fringeTarget: GlTarget? = null
    private var developed: DevelopKey? = null
    private var stageMask = false
    private val zoom = ZoomTiles(handler, ::requestFrame) { egl.eglContext }
    private var sceneTex = 0
    private var sceneFbo = 0
    private var reconKey: Pair<List<Float>, Any>? = null
    private var outputProgram = 0
    private var developUbo = 0
    private var outputUbo = 0
    private var lookUbo = 0
    private lateinit var tools: ColourToolShaders.Gl
    private var vao = 0
    private var sourceTex = 0
    private var stageTex = 0
    private var stageFbo = 0
    private var stageHalfFbo = 0

    /** Only level 1 of the working texture is current (the final pass wrote it at half resolution, see runStage1). */
    private var stageHalf = false
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
    private val lensLut = WarpShaders.LutTexture()
    private val vignetteLut = WarpShaders.LutTexture()
    private val profiler = PassProfiler()
    private val masks = MaskPasses()
    private var maskContext = MaskContext.NONE
    private var maskPlan: MaskPlan? = null
    private var maskPlanKey: Any? = null

    /** Brush dabs by stroke list for the proxy (a stroke in progress recomputes only its own; docs/adr/0022 M4/P). */
    private var dabsCache: Map<List<BrushStroke>, Dabs> = emptyMap()

    /** Bumped whenever the rendered masks' coverage changes (develop and Detail caches key on it). */
    private var masksVersion = 0

    /**
     * Where the coverage changed since the working texture was last developed (docs/adr/0022 "Speed (M4/P)"):
     * [NO_CHANGE], a rectangle (image pixels x0, y0, x1, y1, exclusive: a stroke in progress), or [FULL_CHANGE].
     */
    private var maskChange: IntArray = NO_CHANGE

    /** The develop key of the working texture without the masks' shapes (a partial update needs it unchanged). */
    private var developedShapeFree: Any? = null

    /** Whether the working texture's last render wrote level 1 only (a partial update must do the same). */
    private var developedHalf = false

    /** Partial stage-1 updates so far (tests). */
    @Volatile
    var partialUpdates: Int = 0
        private set
    private var stageMasksVersion = 0
    private var preTarget: GlTarget? = null

    /** The overlay's mask when it is not one of the rendered masks (its coverage gets the slot after them). */
    private var overlayExtra: Mask? = null
    private var preKey: Any? = null

    /** The parameters the pre-local image of the masks was last made for (bumps [preVersion] when they change). */
    private var preParams: Any? = null

    /** The masks' range coverage fused with the pre-local develop (`MaskShaders.FUSED_COVERAGE_FRAG`). */
    private var fusedCoverageProgram = 0
    private var preVersion = 0

    /** Bumped when an AI mask arrives (the zoom tiles re-develop). */
    @Volatile
    private var aiEpoch = 0

    // Healing (docs/adr/0023): the input with the spots' patches, the engine that makes them, and what changed.
    private var healedTex = 0
    private var healedFbo = 0
    private var healEngine: HealEngine? = null
    private var healReader: GlSourceReader? = null
    private var healsKey: List<Heal>? = null
    private var healsVersion = 0

    /** The heal state stage 1 is asked to render: [healsVersion] (the healed input) or -1 (the photo unhealed). */
    private var wantHeals = -1

    /** The heal state the working texture holds (as [wantHeals]). */
    private var stageHeals = -1

    /** The heal state the scene texture holds, and where the healed input changed since. */
    private var sceneHeals = -1
    private var sceneHealChange: IntArray = NO_CHANGE

    /** Where the healed input changed since the last stage-1 render (as [maskChange]). */
    private var healChange: IntArray = NO_CHANGE
    private var removeSource: RemoveSource = RemoveSource.NONE
    private var fullSource: TileSource? = null
    private var healStatus = HealStatus()
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
    fun setInput(input: PipelineInput, pixelScale: Float = 1f) {
        handler.post {
            uploadInput(input, pixelScale)
            requestFrame()
        }
    }

    /**
     * [setInput] that returns once the texture is uploaded (the caller can then drop [input] and time the upload).
     * [pixelScale] is how many full-resolution pixels one pixel of [input] covers (a proxy's downscale): the Detail
     * operator scales its lengths by it (docs/adr/0012, "Scale").
     */
    suspend fun upload(input: PipelineInput, pixelScale: Float = 1f) = onGl {
        uploadInput(input, pixelScale)
        requestFrame()
    }

    /**
     * Sets the photo's full-resolution [source] (upright, the proxy's image [k] times larger along the long edge, `k`
     * as the editor's 100 % zoom uses it): views above 50 % of it then show developed full-resolution tiles
     * (docs/adr/0012). The renderer takes ownership and closes it when it is replaced or on [close]; `null` drops it.
     * Returns false (and does not take [source]) when the renderer is already closed.
     */
    fun setFullResolution(source: TileSource?, k: Float): Boolean = handler.post {
        zoom.setSource(source, k)
        // Remove's crops are read from the original (docs/adr/0023): the fills are asked for again.
        fullSource = source
        resetHealing()
        requestFrame()
    }

    /** What the full-resolution zoom has done (tiles, timings); see [ZoomStats]. */
    val zoomStats: ZoomStats get() = zoom.stats

    /**
     * Suspends until the view is zoomed in far enough for tiles (some are wanted) and every one of them is developed
     * for the current recipe and faded in. Without a window (tests) the pane [view] in a [width] x [height] surface
     * drives the scheduling; with one, the window's panes do (and the arguments are not used).
     */
    suspend fun awaitZoom(view: ViewTransform, width: Int, height: Int): ZoomStats {
        val done = CompletableDeferred<ZoomStats>()
        handler.post {
            zoom.testPane = TilePane(view, width, height, null)
            zoom.whenIdle {
                val stats = zoom.stats
                (stats.wanted > 0).also { if (it) done.complete(stats) }
            }
        }
        return done.await()
    }

    /**
     * Draws [view] (the editor's on-screen output pass, full-resolution tiles included where they are ready) into a
     * [width] x [height] offscreen RGBA8 target and reads it back, top row first. For tests and measurements. With
     * [halfStage] the Detail and Effects passes write the working texture's level 1 directly, as the editor's frames do
     * for views at 50 % or less (docs/adr/0012, "M2/P").
     */
    suspend fun renderView(
        view: ViewTransform,
        width: Int,
        height: Int,
        settings: OutputSettings,
        halfStage: Boolean = false,
    ): ByteArray = onGl {
        val image = checkNotNull(input) { "no input" }
        val req = request.get()
        val params = image.params(req.recipe)
        overlayExtra = overlayId(req)?.let { id ->
            req.recipe.masks.firstOrNull { it.id == id }?.takeIf { m -> params.masks.masks.none { it.id == m.id } }
        }
        prepareHeals(req.recipe.heals)
        prepareMasks(params)
        if (stageStale(params, false, halfStage) || stageHalf != halfStage) {
            runStage1(params, half = halfStage)
        }
        val mapping = image.mapping(req.recipe)
        ensureReadTarget(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glViewport(0, 0, width, height)
        drawOutput(
            image,
            view,
            width,
            height,
            settings,
            surround,
            mapping = mapping,
            tiles = zoom.uniforms(view, mapping),
            post = shownPost(req),
            overlay = overlayUniforms(req),
        )
        val pixels = ByteBuffer.allocateDirect(width * height * RGBA)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GlSupport.checkError("view readback")
        val flipped = ByteArray(width * height * RGBA)
        for (row in 0 until height) {
            pixels.position((height - 1 - row) * width * RGBA)
            pixels.get(flipped, row * width * RGBA, width * RGBA)
        }
        flipped
    }

    /**
     * The edit as the window shows it now (its first pane: the whole frame when not comparing), drawn offscreen at the
     * window's size with the ready zoom tiles, plus what maps its pixels to the image. Tests only; needs a window.
     */
    suspend fun renderShown(settings: OutputSettings): ShownView = onGl {
        val image = checkNotNull(input) { "no input" }
        check(egl.hasWindow) { "no window" }
        val req = request.get()
        val (width, height) = egl.windowSize()
        val frame = req.frame ?: ViewFrame.whole(width, height)
        val mapping = image.mapping(req.recipe)
        val pane = CompareLayout.panes(
            CompareMode.Off,
            frame,
            width,
            height,
            mapping.outputWidth,
            mapping.outputHeight,
            req.view,
        ).first()
        val params = image.params(req.recipe)
        prepareHeals(req.recipe.heals)
        if (params.withoutPost() != stageParams || stageMask || wantHeals != stageHeals) {
            runStage1(params)
        }
        ensureReadTarget(width, height)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GLES30.glViewport(0, 0, width, height)
        val tiles = if (tilesShown(req)) zoom.uniforms(pane.view, mapping) else null
        drawOutput(
            image,
            pane.view,
            width,
            height,
            settings,
            surround,
            mapping = mapping,
            tiles = tiles,
            post = shownPost(req),
        )
        val pixels = ByteBuffer.allocateDirect(width * height * RGBA)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        GlSupport.checkError("shown readback")
        val flipped = ByteArray(width * height * RGBA)
        for (row in 0 until height) {
            pixels.position((height - 1 - row) * width * RGBA)
            pixels.get(flipped, row * width * RGBA, width * RGBA)
        }
        // The view as drawn (pixel-aligned at 1:1), so a caller maps window pixels exactly like the pass did.
        val drawn = alignedView(pane.view, width, height, mapping, tiles)
        ShownView(flipped, width, height, frame, drawn, image.pixelScale, mapping.outputWidth, mapping.outputHeight)
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

    /**
     * Shows the sharpening mask (white = sharpened) instead of the photo, while the Masking slider is dragged
     * (docs/plan/product.md, "Detail"). On-screen only; the recipe is unchanged. Coalesced.
     */
    fun setDetailMask(show: Boolean) {
        update { it.copy(detailMask = show) }
    }

    /**
     * Shows one mask's coverage over the photo (docs/adr/0022, "Overlay"): [MaskOverlay.Mode.COLOUR] blends its colour
     * at its opacity times the coverage, [MaskOverlay.Mode.GREYSCALE] shows the coverage alone as grey. The mask is
     * found by id in the recipe; its coverage is computed even when it adjusts nothing or is hidden. `null` hides it.
     * On-screen only. Coalesced.
     */
    fun setMaskOverlay(overlay: MaskOverlay?) {
        update { it.copy(maskOverlay = overlay) }
    }

    /**
     * Where AI mask components come from (docs/adr/0022, "AI masks") and the photo's key for it; [AiMaskSource.NONE]
     * skips them. A mask that becomes ready later renders on the next frame (and the zoom tiles re-develop).
     */
    fun setMaskSource(source: AiMaskSource, photoKey: String) {
        handler.post {
            maskContext = MaskContext(source, photoKey)
            zoom.maskContext = maskContext
            zoom.maskEpoch++
            maskPlanKey = null
            masksChanged(null)
            requestFrame()
        }
    }

    /**
     * Where Remove spots get their fills (docs/adr/0023); [RemoveSource.NONE] renders them as nothing. The crops are
     * read from the full-resolution source ([setFullResolution]) when there is one, else from the proxy.
     */
    fun setHealSource(source: RemoveSource) {
        handler.post {
            removeSource = source
            zoom.healSource = source
            resetHealing()
            requestFrame()
        }
    }

    /** Called on the GL thread when the Remove spots' state changes (still computing, failed). */
    @Volatile
    var onHeals: ((HealStatus) -> Unit)? = null

    /**
     * The "Visualise spots" view (docs/adr/0023): the photo as a contrast-stretched high-pass in grey, so sensor dust
     * and small blemishes stand out; [strength] 0..100. `null` shows the photo. On-screen only. Coalesced.
     */
    fun setVisualiseSpots(strength: Float?) {
        update { it.copy(spots = strength?.let { s -> SpotsView(s) }) }
    }

    /**
     * The automatic source of [heal] (docs/adr/0023 "Source pick"): `(dx, dy)` as fractions of the image, searched on
     * the proxy with every spot of the current recipe applied; null when nothing fits.
     */
    suspend fun pickHealSource(heal: Heal): Pair<Float, Float>? = onGl {
        val image = checkNotNull(input) { "no input" }
        val engine = healEngine
        val reader = engine?.layerReader() ?: ensureHealReader()
        SourcePicker.pick(heal, reader, image.colour)
    }

    /**
     * The proxy-resolution pixels of the image with the current recipe's spots applied over x0, y0, x1, y1 (RGB, the
     * input encoding); tests.
     */
    suspend fun readHealed(x0: Int, y0: Int, x1: Int, y1: Int): FloatArray = onGl {
        val engine = healEngine
        if (engine != null) {
            engine.layer(x0, y0, x1, y1)
        } else {
            val out = FloatArray((x1 - x0) * (y1 - y0) * PipelineInput.CHANNELS)
            ensureHealReader().read(x0, y0, x1 - x0, y1 - y0, out)
            out
        }
    }

    /** What the last coverage update did (brush bins, layers, time); for the benchmark. */
    val maskStats: MaskStats
        get() = masks.lastStats.let {
            MaskStats(it.brushBins, it.brushFull, it.coverageLayers, it.nanos / NANOS_PER_MS)
        }

    /**
     * The coverage of the mask [maskId] of [recipe] at the proxy's size (row-major, top row first), as the develop pass
     * reads it; null when the recipe has no such mask. Tests.
     */
    suspend fun readCoverage(recipe: Recipe, maskId: String): FloatArray? = onGl {
        val image = checkNotNull(input) { "no input" }
        val params = image.params(recipe)
        overlayExtra = recipe.masks.firstOrNull { it.id == maskId }
        prepareMasks(params)
        val plan = maskPlan ?: return@onGl null
        val slot = plan.masks.indexOfFirst { it.id == maskId }
        if (slot < 0) null else masks.readCoverage(slot)
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
     * Per-pass profiling of frames (GPU timer queries when the driver has them, else `glFinish` after each section;
     * measurements only, see [PassProfiler]). Read the means with [passProfile]; setting it resets them.
     */
    var profilePasses: Boolean
        get() = profiler.enabled
        set(value) {
            handler.post {
                profiler.reset()
                profiler.gpuTimers = profileWithTimers && timerQueries
                profiler.enabled = value
            }
        }

    /** Mean milliseconds per section of the profiled frames (see [profilePasses]). */
    suspend fun passProfile(): Map<String, Float> = onGl { profiler.snapshot() }

    /** Mean CPU milliseconds spent issuing each section of the profiled frames. */
    suspend fun passProfileCpu(): Map<String, Float> = onGl { profiler.cpuSnapshot() }

    /** False makes [profilePasses] sync with `glFinish` even where timer queries exist (measurements). */
    @Volatile
    var profileWithTimers: Boolean = true

    /** True when [passProfile] is GPU time from timer queries (`GL_EXT_disjoint_timer_query`), else wall time. */
    val passProfileOnGpu: Boolean get() = profiler.gpuTimers

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

    /** [renderOutput] with its size: (width, height, RGBA8 top row first). The debug mask lab's input (ADR 0021). */
    suspend fun renderOutputImage(
        recipe: Recipe,
        settings: OutputSettings = OutputSettings(),
    ): Triple<Int, Int, ByteArray> = onGl {
        val mapping = checkNotNull(input) { "no input" }.mapping(recipe)
        Triple(mapping.outputWidth, mapping.outputHeight, readOutput(recipe, settings))
    }

    /** [renderOutput] with the clipping overlays drawn as on screen (tests of the output-pass flag). */
    suspend fun renderOutput(recipe: Recipe, settings: OutputSettings, clipping: ClippingWarnings): ByteArray =
        onGl { readOutput(recipe, settings, clipping) }

    /**
     * Times full offscreen renders (stage 1 + output pass into an RGBA8 target) for each recipe, in order, with
     * `glFinish` after each; returns the wall milliseconds per frame. This is the "dragging a slider" measurement
     * without the display or the compositor. With a [frame] (width, height) the output pass draws the photo fitted to
     * that frame (minified with the explicit LOD, as the editor's photo slot); without one, at 1:1 at the image size.
     */
    suspend fun benchmark(recipes: List<Recipe>, frame: Pair<Int, Int>? = null): List<Float> = onGl {
        recipes.map { recipe ->
            val start = System.nanoTime()
            benchmarkFrame(recipe, frame)
            GLES20.glFinish()
            profiler.mark("output pass")
            (System.nanoTime() - start) / NANOS_PER_MS
        }
    }

    /**
     * Frames as the display pipelines them: each frame is flushed with a fence and the CPU waits only for the frame
     * [inFlight] frames back (a swap chain's depth), so one frame's CPU work overlaps the previous frames' GPU work.
     * Returns the sustained milliseconds per frame (wall time over the frames: what a 120 Hz display needs at or below
     * 8.3 ms) and, with GPU timer queries, each frame's GPU time. [benchmark] is the serialised latency of one frame
     * (CPU, then GPU, then the sync).
     */
    suspend fun benchmarkPipelined(
        recipes: List<Recipe>,
        frame: Pair<Int, Int>? = null,
        inFlight: Int = 2,
    ): PipelinedTimes = onGl {
        val fences = ArrayDeque<Long>()
        val queries = IntArray(if (timerQueries) recipes.size else 0)
        if (queries.isNotEmpty()) GLES30.glGenQueries(queries.size, queries, 0)
        val start = System.nanoTime()
        for ((i, recipe) in recipes.withIndex()) {
            while (fences.size >= inFlight) {
                GLES30.glClientWaitSync(fences.removeFirst(), GLES30.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_TIMEOUT_NS)
            }
            if (queries.isNotEmpty()) GLES30.glBeginQuery(GL_TIME_ELAPSED_EXT, queries[i])
            benchmarkFrame(recipe, frame)
            if (queries.isNotEmpty()) GLES30.glEndQuery(GL_TIME_ELAPSED_EXT)
            fences.addLast(GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0))
            GLES30.glFlush()
        }
        GLES20.glFinish()
        val wall = (System.nanoTime() - start) / NANOS_PER_MS / recipes.size
        while (fences.isNotEmpty()) GLES30.glDeleteSync(fences.removeFirst())
        val result = IntArray(1)
        val gpu = queries.map {
            GLES30.glGetQueryObjectuiv(it, GLES30.GL_QUERY_RESULT, result, 0)
            result[0].toUInt().toFloat() / NANOS_PER_MS
        }
        if (queries.isNotEmpty()) GLES30.glDeleteQueries(queries.size, queries, 0)
        PipelinedTimes(wall, gpu)
    }

    /** One benchmark frame without the final sync: stage 1 when needed, then the output pass. */
    private fun benchmarkFrame(recipe: Recipe, frame: Pair<Int, Int>?) {
        val image = checkNotNull(input) { "no input" }
        profiler.begin()
        val params = image.params(recipe)
        profiler.mark("params (CPU)")
        // The recipe's geometry: a Transform drag resamples through the warp every frame (docs/adr/0015).
        val mapping = image.mapping(recipe)
        // As on screen: the stage runs only when what it depends on changed (not for vignette or grain), at half
        // resolution when the fitted frame reads level 1 or coarser (axis-aligned, as halfViewOnly).
        val fit = frame?.let { (w, h) -> minOf(w.toFloat() / mapping.outputWidth, h.toFloat() / mapping.outputHeight) }
        val half = fit != null && fit <= HALF_VIEW_SCALE && mapping.axisAligned
        prepareHeals(recipe.heals)
        if (stageStale(params, false, half)) runStage1(params, half = half)
        val post = PostParams.from(recipe.effects)
        if (frame == null || fit == null) {
            drawOutputOffscreen(OutputSettings(dither = true), mapping = mapping, post = post)
        } else {
            val (w, h) = frame
            ensureReadTarget(w, h)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
            GlSupport.discardColour()
            GLES30.glViewport(0, 0, w, h)
            val view = ViewTransform(fit, mapping.outputWidth / 2f, mapping.outputHeight / 2f)
            drawOutput(image, view, w, h, OutputSettings(dither = true), surround, mapping = mapping, post = post)
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

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun renderFrame() {
        framePosted.set(false)
        val image = input ?: return
        if (!egl.hasWindow) {
            zoomOffscreen(image)
            return
        }
        profiler.begin()
        val start = System.nanoTime()
        val req = request.get()
        val (width, height) = egl.windowSize()
        val frame = req.frame ?: ViewFrame.whole(width, height)
        val compare = if (req.showOriginal || req.geometryEditing) CompareMode.Off else req.compare
        // Geometry (slot 13) is part of the view: "show original" keeps the crop so the photo does not jump.
        val mapping = image.mapping(req.recipe, editing = req.geometryEditing)
        if (!req.geometryEditing && mapping != shownMapping) {
            // The histogram is of the cropped picture: a new crop/turn measures it again.
            shownMapping = mapping
            histogramDirty = true
        }
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
        val shownRecipe = if (req.showOriginal) Recipe.DEFAULT else req.recipe
        val params = image.params(shownRecipe)
        val mask = req.detailMask && !req.showOriginal
        profiler.mark("params (CPU)")
        overlayExtra = overlayId(req)?.let { id ->
            shownRecipe.masks.firstOrNull { it.id == id }?.takeIf { m -> params.masks.masks.none { it.id == m.id } }
        }
        prepareHeals(if (req.showOriginal) null else req.recipe.heals)
        prepareMasks(params)
        val tilesOn = tilesShown(req)
        val half = halfViewOnly(panes, mapping, tilesOn, req)
        if (stageStale(params, mask, half)) {
            runStage1(params, mask, half)
            histogramDirty = true
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, width, height)
        val tilePanes = if (tilesOn) tilePanes(panes, width, height, frame) else emptyList()
        val fading = zoom.beforeFrame(req.recipe, mapping, tilePanes, System.nanoTime())
        drawPanes(image, panes, width, height, req, mapping, tilesOn)
        profiler.mark("output pass")
        val submitted = System.nanoTime()
        egl.swap()
        profiler.mark("swap")
        zoom.afterFrame(System.nanoTime())
        if (fading) requestFrame()
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

    /**
     * The post-crop vignette and grain of what the window shows: none for the original, the crop mode's plane (the
     * frame is being edited) or the sharpening mask.
     */
    @Suppress("ComplexCondition")
    private fun shownPost(req: Request): PostParams = if (req.showOriginal || req.geometryEditing ||
        req.detailMask || req.maskOverlay?.mode == MaskOverlay.Mode.GREYSCALE || req.spots != null
    ) {
        PostParams.NONE
    } else {
        PostParams.from(req.recipe.effects)
    }

    /** The overlay of [req] when its mask has a coverage slot now (docs/adr/0022, "Overlay"). */
    @Suppress("ReturnCount")
    private fun overlayUniforms(req: Request): OverlayUniforms? {
        val overlay = req.maskOverlay ?: return null
        if (overlayId(req) == null || masks.coverageTexture == 0) return null
        val slot = maskPlan?.masks?.indexOfFirst { it.id == overlay.maskId }?.takeIf { it >= 0 } ?: return null
        val (cell, channel) = masks.overlayCell(slot) ?: return null
        return OverlayUniforms(cell, channel, overlay.mode, MaskOverlay.linearColour(overlay.colour), overlay.opacity)
    }

    /** Zoom tiles show unless the photo shows the original, the crop mode's plane or the sharpening mask. */
    private fun tilesShown(req: Request): Boolean =
        zoom.active && !req.showOriginal && !req.geometryEditing && !req.detailMask

    /** The edit's panes as the zoom sees them: only what is visible (the clip, else the photo's frame). */
    private fun tilePanes(panes: List<ComparePane>, width: Int, height: Int, frame: ViewFrame): List<TilePane> =
        panes.filter { !it.original }.map { TilePane(it.view, width, height, it.clip ?: frame) }

    /** Without a window (tests, benchmarks): the zoom's scheduling for its test pane, nothing is drawn. */
    private fun zoomOffscreen(image: UploadedImage) {
        val pane = zoom.testPane ?: return
        val req = request.get()
        val mapping = image.mapping(req.recipe)
        val fading = zoom.beforeFrame(req.recipe, mapping, listOf(pane), System.nanoTime())
        zoom.afterFrame(System.nanoTime())
        if (fading) handler.postDelayed({ requestFrame() }, FADE_TICK_MS)
    }

    /**
     * Scene stage, the Light passes that are needed, the develop stage, then Detail (slot 11) and the local Effects
     * (slot 12) when they run, into the working texture. [showMask] draws the sharpening mask instead (the Masking
     * overlay). The develop output is kept (in OkLab) while only Detail and Effects change, and so is the Effects grid
     * built from it; [DetailPasses] caches its own intermediates, so a Detail or Effects drag re-runs only the passes
     * that depend on the dragged slider.
     */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private fun runStage1(all: DevelopParams, showMask: Boolean = false, half: Boolean = false) {
        val image = checkNotNull(input) { "no input" }
        val params = all.withoutPost()
        val localOn = params.localActive || showMask
        val fringeOn = params.defringe.active
        // The coverage the develop pass reads (cached in MaskPasses; the overlay's mask keeps its slot after them).
        prepareMasks(params)
        val develop = DevelopKey(params.withoutDetail(), localOn, masksVersion, wantHeals)
        val region = DetailPasses.Region(0, 0, image.width, image.height, image.width, image.height)
        val shapeFree = Pair(params.withoutDetail().withoutMaskShapes(), localOn)
        val halfNow = localOn && half && !showMask && stageHalfFbo != 0
        // Only the coverage changed, inside a rectangle (a stroke in progress): the develop pass and slots 11-12 run
        // there only (docs/adr/0022 "Speed (M4/P)"); the working texture outside it is still exact.
        // Or only some masks' adjustments changed (a drag of a mask's slider): the box their coverage can reach.
        val canPartial = develop != developed && !fringeOn && !showMask && !stageMask && halfNow == developedHalf &&
            stageParams != null && developedShapeFree != null
        val partial = if (canPartial) partialRect(params, shapeFree, develop) else null
        maskChange = NO_CHANGE
        healChange = NO_CHANGE
        var gridChanged = false
        if (partial != null) {
            partialUpdates++
            runDevelop(params, localOn, partial)
            developed = develop
            if (localOn && params.effects.gridActive && effectsGridValid) {
                effects.update(checkNotNull(labTarget).texture, partial)
                gridChanged = true
                profiler.mark("effects grid")
            }
        } else if (develop != developed) {
            runDevelop(params, localOn || fringeOn)
            if (fringeOn) {
                // Defringe (slot 10b, docs/adr/0015) on the OkLab develop output: OkLab again when Detail follows.
                val out = if (localOn) ensureFringeTarget(image) else GlTarget(stageTex, stageFbo)
                defringe.run(params.defringe, checkNotNull(labTarget).texture, out, region, rgbOut = !localOn)
                profiler.mark("defringe pass")
            }
            developed = develop
            detail.invalidate()
            effectsGridValid = false
        }
        if (localOn) runLocal(params, showMask, half, region, fringeOn, partial, gridChanged)
        developedShapeFree = shapeFree
        developedHalf = halfNow
        stageParams = params
        stageMasksVersion = masksVersion
        stageHeals = wantHeals
        stageMask = showMask
        stageHalf = localOn && half && !showMask && stageHalfFbo != 0
        stageMipsValid = false
    }

    /**
     * True when the working texture does not hold [params] (without the post-crop finish) for this [mask] and
     * resolution: level 0 is needed ([half] false) and only level 1 is current.
     */
    private fun stageStale(params: DevelopParams, mask: Boolean, half: Boolean): Boolean {
        val needsLevel0 = stageHalf && !half
        return params.withoutPost() != stageParams || mask != stageMask || needsLevel0 ||
            masksVersion != stageMasksVersion || wantHeals != stageHeals
    }

    /**
     * Writes level 0 of the working texture when only level 1 is current (a draw that reads level 0 after half-
     * resolution frames): the final pass alone runs again (its inputs are cached). Keeps the caller's framebuffer,
     * viewport and scissor.
     */
    private fun ensureStageLevel0() {
        if (!stageHalf) return
        val params = stageParams ?: return
        val fbo = IntArray(1)
        val viewport = IntArray(4)
        val scissor = IntArray(4)
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, fbo, 0)
        GLES30.glGetIntegerv(GLES30.GL_VIEWPORT, viewport, 0)
        GLES30.glGetIntegerv(GLES30.GL_SCISSOR_BOX, scissor, 0)
        val scissorOn = GLES30.glIsEnabled(GLES30.GL_SCISSOR_TEST)
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        runStage1(params, stageMask)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[0])
        GLES30.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
        GLES30.glScissor(scissor[0], scissor[1], scissor[2], scissor[3])
        if (scissorOn) GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
    }

    /**
     * True when every pane of this frame reads only mip level 1 or coarser of the working texture (axis-aligned views
     * at 50 % or less, no zoom tiles, no loupe, no mask overlay): the Detail passes then write level 1 directly.
     */
    private fun halfViewOnly(
        panes: List<ComparePane>,
        mapping: GeometryMapping,
        tilesOn: Boolean,
        req: Request,
    ): Boolean = !tilesOn && req.loupe == null && !req.detailMask && !req.geometryEditing && req.spots == null &&
        mapping.axisAligned &&
        panes.all { it.original || it.view.scale <= HALF_VIEW_SCALE }

    /** Slots 11 and 12 into the working texture (level 1 only with [half]); see [runStage1]. */
    @Suppress("LongParameterList")
    private fun runLocal(
        params: DevelopParams,
        showMask: Boolean,
        half: Boolean,
        region: DetailPasses.Region,
        fringeOn: Boolean,
        dirty: IntArray? = null,
        gridChanged: Boolean = false,
    ) {
        val image = checkNotNull(input) { "no input" }
        val lab = checkNotNull(if (fringeOn) fringeTarget else labTarget).texture
        // The masks' values for slots 11-12: a drag of a mask's Detail or Effects slider keeps the develop output, so
        // the block and the adjustment table must be uploaded here too (docs/adr/0022).
        masks.bind(params)
        val e = if (showMask) EffectsParams.NONE else params.effects
        var finalFull = gridChanged
        if (e.gridActive && !effectsGridValid) {
            effects.allocate(image.width, image.height)
            effects.build(lab)
            effectsGridValid = true
            finalFull = true
            profiler.mark("effects grid")
        }
        detail.run(
            params.detail,
            e,
            grid = if (e.gridActive) effects else null,
            lab = lab,
            t = checkNotNull(detailTargets),
            output = GlTarget(stageTex, stageFbo),
            region = region,
            showMask = showMask,
            cache = true,
            half = if (half && stageHalfFbo != 0) GlTarget(stageTex, stageHalfFbo) else null,
            // The luminance levels depend on the masks' noise only (and their coverage): another local drag keeps them.
            masks = if (params.masks.active) {
                Triple(params.masks.masks.map { it.id }, params.masks.noiseColumn(), masksVersion)
            } else {
                null
            },
            dirty = dirty,
            reach = params.reach,
            finalFull = finalFull,
        )
        lastLocalPasses = detail.lastPasses
    }

    /** Passes slots 11 and 12 ran in the last stage-1 render (0 when they were cached or off); for the benchmark. */
    @Volatile
    var lastLocalPasses: Int = 0
        private set

    /** Scene stage, Light passes and the develop stage: into the stage texture, or OkLab for the Detail passes. */
    private fun runDevelop(params: DevelopParams, labOut: Boolean, rect: IntArray? = null) {
        val image = checkNotNull(input) { "no input" }
        if (labOut) ensureDetailTargets(image)
        val guided = prepareScene(params)
        masks.bind(params)
        developPass(if (labOut) checkNotNull(labTarget).fbo else stageFbo, labOut, guided, rect)
        profiler.mark("develop pass")
    }

    /**
     * The scene texture and the local-tone guide of [params] (each kept while what it depends on is unchanged) and the
     * develop stage's uniforms; returns whether the guide is in use.
     */
    @Suppress("CyclomaticComplexMethod", "ComplexCondition")
    private fun prepareScene(params: DevelopParams): Boolean {
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
        tools.upload(params)
        // The scene texture and the guide depend only on what reaches scene-linear light (white balance, matrix,
        // exposure, reconstruction): a drag of a tone slider re-runs only the develop pass. Masks never reach it.
        val scene = SceneKey(
            params.wbMultipliers.toList(),
            params.cameraToWorking.toList(),
            params.exposureGain,
            if (reconstruct) hp else null,
            params.vignette,
        )
        // Healing (docs/adr/0023): the scene reads the healed input; highlight reconstruction's chroma (above) is
        // always the photo's own. A change of the spots alone redraws the scene where the input changed.
        val heals = wantHeals
        val partialHeal = scene == sceneKey && heals >= 0 && sceneHeals >= 0 && heals != sceneHeals &&
            sceneHealChange !== FULL_CHANGE
        var guideRect: IntArray? = null
        if (scene != sceneKey || (heals != sceneHeals && !partialHeal)) {
            scenePass(params, reconstruct, if (heals >= 0) healedTex else sourceTex, null)
            sceneKey = scene
            sceneHeals = heals
            sceneHealChange = NO_CHANGE
            guideValid = false
        } else if (partialHeal) {
            val rect = sceneHealChange
            if (rect !== NO_CHANGE) {
                scenePass(params, reconstruct, healedTex, rect)
                guideRect = rect
            }
            sceneHeals = heals
            sceneHealChange = NO_CHANGE
        }
        // Local highlights/shadows need the guide too (docs/adr/0022).
        val guided = guided(params)
        if (guided && !guideValid) {
            light.guide(sceneTex)
            guideValid = true
            profiler.mark("guided filter")
        } else if (guided && guideRect != null) {
            // Only the rows of the cells the healed rectangle touches, then the low-resolution steps.
            light.guidePartial(sceneTex, guideRect)
            profiler.mark("guided filter (partial)")
        } else if (guideRect != null) {
            guideValid = false
        }
        return guided
    }

    /** The scene pass from [input] into the scene texture, whole or scissored to [rect]. */
    private fun scenePass(params: DevelopParams, reconstruct: Boolean, input: Int, rect: IntArray?) {
        val image = checkNotNull(this.input) { "no input" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        if (rect == null) {
            GlSupport.discardColour()
        } else {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        }
        GLES30.glViewport(0, 0, image.width, image.height)
        GLES30.glUseProgram(sceneProgram)
        bindVignette(sceneProgram, params, vignetteLut, image.width, image.height)
        bindTextures(input, if (reconstruct) light.chromaTexture else input)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (rect != null) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GlSupport.checkError("scene stage")
        profiler.mark(if (rect == null) "scene pass" else "scene pass (partial)")
    }

    /** The develop pass into [fbo] (the uniforms and the masks' block are uploaded). */
    private fun developPass(fbo: Int, labOut: Boolean, guided: Boolean, rect: IntArray? = null) {
        val image = checkNotNull(input) { "no input" }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        if (rect == null) {
            GlSupport.discardColour()
        } else {
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(rect[0], rect[1], rect[2] - rect[0], rect[3] - rect[1])
        }
        GLES30.glViewport(0, 0, image.width, image.height)
        GLES30.glUseProgram(developProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(developProgram, "uLabOut"), if (labOut) 1 else 0)
        bindTextures(
            sceneTex,
            if (guided) light.coefTexture else sceneTex,
            if (guided) light.momentsTexture else sceneTex,
        )
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        if (rect != null) GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GlSupport.checkError("develop stage")
    }

    /**
     * The overlay mask's id when the window shows one (not for the original, the crop plane or the sharpening mask).
     */
    private fun overlayId(req: Request): String? =
        req.maskOverlay?.maskId?.takeIf { !req.showOriginal && !req.geometryEditing && !req.detailMask }

    /**
     * The coverage of [params]' rendered masks, plus the overlay's mask ([overlayExtra]) when it is not one of them
     * (docs/adr/0022): the brush layers, the AI masks from the source, the pre-local image when a range mask reads it
     * (a develop pass without masks into its own OkLab texture, kept while the global parameters are), then the
     * coverage passes ([MaskPasses] keeps what did not change). Bumps [masksVersion] when the coverage changed.
     */
    private fun prepareMasks(params: DevelopParams) {
        val image = checkNotNull(input) { "no input" }
        val rendered = params.masks.masks
        val extra = overlayExtra?.takeIf { m -> rendered.none { it.id == m.id } }
        val list = if (extra != null) rendered + extra else rendered
        updateMaskPlan(list, image.width, image.height)
        val plan = maskPlan
        if (plan == null) {
            if (masks.coverageTexture != 0) {
                masks.coverage(MaskPlan.of(emptyList(), image.width, image.height), 0, 0, 0, 0, 0, 0, emptyList(), true)
                masksChanged(null)
            }
            return
        }
        val (pre, fused) = if (plan.needsPre) preLocal(params, plan, image) else 0 to null
        val epoch = aiEpoch
        val ai = plan.ai.map { component ->
            maskContext.ai(component, image.width, image.height) {
                handler.post {
                    if (aiEpoch == epoch) {
                        aiEpoch++
                        zoom.maskEpoch++
                    }
                    maskPlanKey = null
                    requestFrame()
                }
            }
        }
        if (masks.coverage(plan, 0, 0, image.width, image.height, pre, preVersion, ai, cache = true, fused = fused)) {
            masksChanged(masks.lastStats.dirty)
            profiler.mark("mask coverage")
        }
    }

    // ------------------------------------------------------------------ healing (docs/adr/0023)

    /**
     * Brings the healed input to [heals] (the edit's spots; null renders the photo unhealed and keeps the spots): the
     * engine recomputes the spots that changed, their rectangles are restored from the source and the patches written
     * again ([applyHealPatches]); sets [wantHeals].
     */
    @Suppress("ReturnCount")
    private fun prepareHeals(heals: List<Heal>?) {
        if (heals == null) {
            wantHeals = -1
            return
        }
        val image = input ?: return
        if (!HealOp.active(heals)) {
            val engine = healEngine
            if (engine != null && !engine.isEmpty) {
                engine.update(emptyList())?.let { applyHealPatches(it) }
            }
            healsKey = heals
            publishHealStatus()
            wantHeals = -1
            return
        }
        if (heals != healsKey) {
            val engine = healEngine ?: newHealEngine(image)
            val epoch = healEpoch
            val dirty = engine.update(heals) {
                handler.post {
                    if (healEpoch == epoch) {
                        healsKey = null
                        requestFrame()
                    }
                }
            }
            healsKey = heals
            if (dirty != null) applyHealPatches(dirty)
            publishHealStatus()
            profiler.mark("healing (CPU)")
        }
        wantHeals = healsVersion
    }

    private var healEpoch = 0

    /** A new engine on the proxy (Remove's crops from the full-resolution source when there is one). */
    private fun newHealEngine(image: UploadedImage): HealEngine {
        val reader = ensureHealReader()
        val original = fullSource?.let { PixelReader.of(it) } ?: reader
        val engine = HealEngine(
            image.width,
            image.height,
            reader,
            image.colour,
            RemoveContext(removeSource, original, image.colour),
        )
        healEngine = engine
        if (healedTex == 0) {
            healedTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
            healedFbo = newFramebuffer(healedTex)
            copySource(0, 0, image.width, image.height)
        }
        return engine
    }

    /** The proxy's source pixels read back from the GPU (block cache). */
    private fun ensureHealReader(): GlSourceReader {
        val image = checkNotNull(input) { "no input" }
        healReader?.let { return it }
        if (sourceFbo == 0) sourceFbo = newFramebuffer(sourceTex)
        return GlSourceReader(image.width, image.height) { sourceFbo }.also { healReader = it }
    }

    /**
     * The spots are computed again from scratch (a new Remove source or original): the healed input is the source again
     * and every stage that read it runs again.
     */
    private fun resetHealing() {
        healEpoch++
        healEngine = null
        healsKey = null
        val image = input ?: return
        if (healedTex != 0) copySource(0, 0, image.width, image.height)
        healsVersion++
        healChanged(null)
        publishHealStatus()
    }

    /**
     * Restores [dirty] of the healed input from the source and writes the engine's patches again: every patch that
     * meets the restored area is written whole, so the area grows to a closed set first (a later patch that overlaps
     * a rewritten one is rewritten after it).
     */
    @Suppress("NestedBlockDepth", "CyclomaticComplexMethod", "ComplexCondition")
    private fun applyHealPatches(dirty: IntArray) {
        val image = checkNotNull(input)
        val patches = healEngine?.current.orEmpty()
        val area = intArrayOf(
            dirty[0].coerceIn(0, image.width),
            dirty[1].coerceIn(0, image.height),
            dirty[2].coerceIn(0, image.width),
            dirty[3].coerceIn(0, image.height),
        )
        var grew = true
        while (grew) {
            grew = false
            for (p in patches) {
                if (p == null || !(p.x0 < area[2] && area[0] < p.x1 && p.y0 < area[3] && area[1] < p.y1)) continue
                if (p.x0 < area[0] || p.y0 < area[1] || p.x1 > area[2] || p.y1 > area[3]) {
                    area[0] = minOf(area[0], p.x0)
                    area[1] = minOf(area[1], p.y0)
                    area[2] = maxOf(area[2], p.x1)
                    area[3] = maxOf(area[3], p.y1)
                    grew = true
                }
            }
        }
        if (area[2] <= area[0] || area[3] <= area[1]) return
        copySource(area[0], area[1], area[2] - area[0], area[3] - area[1])
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, healedTex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        for (p in patches) {
            if (p == null || !(p.x0 < area[2] && area[0] < p.x1 && p.y0 < area[3] && area[1] < p.y1)) continue
            val n = p.width * p.height
            val rgba = FloatArray(n * RGBA)
            for (i in 0 until n) {
                rgba[i * RGBA] = p.rgb[i * PipelineInput.CHANNELS]
                rgba[i * RGBA + 1] = p.rgb[i * PipelineInput.CHANNELS + 1]
                rgba[i * RGBA + 2] = p.rgb[i * PipelineInput.CHANNELS + 2]
                rgba[i * RGBA + 3] = 1f
            }
            val floats = ByteBuffer.allocateDirect(n * RGBA * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                .asFloatBuffer()
            floats.put(rgba)
            floats.flip()
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, p.x0, p.y0, p.width, p.height, GLES30.GL_RGBA, GLES30.GL_FLOAT, floats,
            )
        }
        GlSupport.checkError("healed input")
        healsVersion++
        healChanged(area)
    }

    /** Copies the source into the healed input over the rectangle ([x], [y], [w] x [h]). */
    private fun copySource(x: Int, y: Int, w: Int, h: Int) {
        if (sourceFbo == 0) sourceFbo = newFramebuffer(sourceTex)
        GLES30.glBindFramebuffer(GLES30.GL_READ_FRAMEBUFFER, sourceFbo)
        GLES30.glBindFramebuffer(GLES30.GL_DRAW_FRAMEBUFFER, healedFbo)
        GLES30.glBlitFramebuffer(x, y, x + w, y + h, x, y, x + w, y + h, GLES30.GL_COLOR_BUFFER_BIT, GLES30.GL_NEAREST)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** The healed input changed over [rect] (null: anywhere): for the scene and the develop stages. */
    private fun healChanged(rect: IntArray?) {
        healChange = grow(healChange, rect)
        sceneHealChange = grow(sceneHealChange, rect)
    }

    private fun grow(current: IntArray, rect: IntArray?): IntArray = when {
        rect == null || current === FULL_CHANGE -> FULL_CHANGE

        current === NO_CHANGE -> rect.copyOf()

        else -> intArrayOf(
            minOf(current[0], rect[0]),
            minOf(current[1], rect[1]),
            maxOf(current[2], rect[2]),
            maxOf(current[3], rect[3]),
        )
    }

    private fun publishHealStatus() {
        val engine = healEngine
        val status = HealStatus(engine?.pending.orEmpty(), engine?.failed.orEmpty(), engine?.fellBack.orEmpty())
        if (status != healStatus) {
            healStatus = status
            onHeals?.invoke(status)
        }
    }

    private fun releaseHealing() {
        if (healedTex != 0) GLES30.glDeleteTextures(1, intArrayOf(healedTex), 0)
        if (healedFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(healedFbo), 0)
        healedTex = 0
        healedFbo = 0
        healEngine = null
        healReader = null
        healsKey = null
        healEpoch++
        wantHeals = -1
        stageHeals = -1
        sceneHeals = -1
        healChange = NO_CHANGE
        sceneHealChange = NO_CHANGE
    }

    /**
     * Where a partial stage-1 update redraws (see [runStage1]); null = everything. The masks' part (a stroke's bins, a
     * mask's box) and the healing's part (where the healed input changed, grown by the local-tone guide's reach when
     * the guide is in use: docs/adr/0023 "Partial updates").
     */
    @Suppress("ReturnCount", "CyclomaticComplexMethod", "ComplexCondition")
    private fun partialRect(params: DevelopParams, shapeFree: Any, develop: DevelopKey): IntArray? {
        val old = developed ?: return null
        val heal = healChange
        // Heal states -1 <-> a version re-render the scene whole: no partial update then.
        if (heal === FULL_CHANGE || (develop.heals != old.heals && (develop.heals < 0 || old.heals < 0))) return null
        val masksSame = develop.params == old.params && develop.local == old.local && develop.masks == old.masks
        val maskPart: IntArray? = if (masksSame) {
            EMPTY_RECT
        } else {
            val change = maskChange
            when {
                change === NO_CHANGE -> adjustedBox(params)
                change !== FULL_CHANGE && shapeFree == developedShapeFree -> change
                else -> null
            }
        }
        if (maskPart == null) return null
        if (develop.heals == old.heals || heal === NO_CHANGE) return maskPart
        // Range masks and auto-mask brushes read the pre-local image the heal changed: everything then.
        if (maskPlan?.needsPre == true) return null
        val image = checkNotNull(input)
        val grow = if (guided(params)) LightPasses.guideReach(image.grid) else 0
        val healRect = intArrayOf(
            maxOf(heal[0] - grow, 0),
            maxOf(heal[1] - grow, 0),
            minOf(heal[2] + grow, image.width),
            minOf(heal[3] + grow, image.height),
        )
        if (maskPart === EMPTY_RECT) return healRect
        return intArrayOf(
            minOf(maskPart[0], healRect[0]),
            minOf(maskPart[1], healRect[1]),
            maxOf(maskPart[2], healRect[2]),
            maxOf(maskPart[3], healRect[3]),
        )
    }

    /** Local highlights/shadows need the guide (docs/adr/0022). */
    private fun guided(params: DevelopParams): Boolean =
        params.tone.localActive || params.masks.needs(MaskParams.NEED_TONE_LOCAL)

    /** The plan of [list]; it depends on the shapes only: a drag of a mask's adjustments keeps it (and its dabs). */
    private fun updateMaskPlan(list: List<Mask>, width: Int, height: Int) {
        val planKey = list.map { it.id to it.components }
        if (planKey == maskPlanKey) return
        val used = HashMap<List<BrushStroke>, Dabs>()
        maskPlan = if (list.isEmpty()) {
            null
        } else {
            MaskPlan.of(list, width, height) { strokes ->
                used.getOrPut(strokes) { dabsCache[strokes] ?: Dabs.of(strokes, width, height) }
            }
        }
        dabsCache = used
        maskPlanKey = planKey
        profiler.mark("mask plan (CPU)")
    }

    /**
     * When [params] differ from the working texture's only by some masks' adjustments (same masks, shapes, global
     * values and Detail/Effects parameters), the box their coverage can reach ([MaskBounds]); else null.
     */
    @Suppress("ReturnCount")
    private fun adjustedBox(params: DevelopParams): IntArray? {
        val old = stageParams ?: return null
        val plan = maskPlan ?: return null
        val a = params.masks
        val b = old.masks
        if (!a.active || a.masks.map { it.id } != b.masks.map { it.id }) return null
        if (params.withoutMaskAdjustments() != old.withoutMaskAdjustments()) return null
        var box = IntArray(4)
        for (m in a.masks.indices) {
            val o = m * MaskParams.VECTOR
            val same = (0 until MaskParams.VECTOR).all { a.adjust[o + it] == b.adjust[o + it] }
            if (!same) box = MaskBounds.union(box, MaskBounds.of(plan, m))
        }
        return box
    }

    /** [masksVersion] moves on; [rect] is where the coverage changed (null: anywhere). */
    private fun masksChanged(rect: IntArray?) {
        masksVersion++
        maskChange = when {
            rect == null || maskChange === FULL_CHANGE -> FULL_CHANGE

            maskChange === NO_CHANGE -> rect.copyOf()

            else -> intArrayOf(
                minOf(maskChange[0], rect[0]),
                minOf(maskChange[1], rect[1]),
                maxOf(maskChange[2], rect[2]),
                maxOf(maskChange[3], rect[3]),
            )
        }
    }

    /**
     * The masks' pre-local image (docs/adr/0022): its texture and nothing to prepare when an auto-mask brush reads it
     * (a develop pass without masks into its own OkLab texture, kept while the global parameters are), else no texture
     * and the preparation of the fused range coverage. Bumps [preVersion] when the global parameters change.
     */
    private fun preLocal(params: DevelopParams, plan: MaskPlan, image: UploadedImage): Pair<Int, (() -> Unit)?> {
        val key = params.withoutDetail().withoutMasks()
        if (key != preParams) {
            preParams = key
            preVersion++
        }
        if (plan.brushes.none { it.autoMask }) {
            // The range masks compute the pre-local colour in their coverage pass (docs/adr/0022 "Speed (M4/P)").
            return 0 to {
                val guided = prepareScene(params)
                bindTextures(
                    sceneTex,
                    if (guided) light.coefTexture else sceneTex,
                    if (guided) light.momentsTexture else sceneTex,
                )
            }
        } else {
            // An auto-mask brush reads the pre-local image too: it is rendered into its own texture.
            val target = preTarget ?: run {
                val tex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
                GlTarget(tex, newFramebuffer(tex)).also { preTarget = it }
            }
            if (key != preKey) {
                val guided = prepareScene(params)
                masks.bind(params, off = true)
                developPass(target.fbo, labOut = true, guided = guided)
                profiler.mark("mask pre-local pass")
                preKey = key
            }
            return target.texture to null
        }
    }

    /** The textures of slots 11 and 12 at the proxy size, allocated the first time they run on this image. */
    private fun ensureDetailTargets(image: UploadedImage) {
        if (labTarget != null) return
        fun target(format: Int, w: Int = image.width, h: Int = image.height): GlTarget {
            val tex = newTexture(1, w, h, GLES30.GL_NEAREST, GLES30.GL_NEAREST, format)
            return GlTarget(tex, newFramebuffer(tex))
        }
        val hw = (image.width + 1) / 2
        val hh = (image.height + 1) / 2
        labTarget = target(GLES30.GL_RGBA16F)
        detailTargets = DetailPasses.Targets(
            lumA = target(GLES30.GL_RG16F),
            lumB = target(GLES30.GL_RG16F),
            lumH = target(GLES30.GL_R16F),
            halfD = target(GLES30.GL_RGBA16F, hw, hh),
            halfA = target(GLES30.GL_RGBA16F, hw, hh),
            halfB = target(GLES30.GL_RGBA16F, hw, hh),
            blurA = target(GLES30.GL_RG16F),
            blurB = target(GLES30.GL_RG16F),
            chain = { k -> chainTargets.getOrNull(k) ?: target(GLES30.GL_RGBA16F).also { chainTargets += it } },
        )
        detail.invalidate()
    }

    /** The defringe output when the Detail passes follow (OkLab, the proxy's size). */
    private fun ensureFringeTarget(image: UploadedImage): GlTarget = fringeTarget ?: run {
        val tex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        GlTarget(tex, newFramebuffer(tex)).also { fringeTarget = it }
    }

    private fun deleteDetailTargets() {
        val t = detailTargets
        val targets = listOfNotNull(labTarget, fringeTarget) + chainTargets +
            (
                t?.let { listOf(it.lumA, it.lumB, it.lumH, it.halfD, it.halfA, it.halfB, it.blurA, it.blurB) }
                    ?: emptyList()
                )
        if (targets.isNotEmpty()) {
            GLES30.glDeleteTextures(targets.size, targets.map { it.texture }.toIntArray(), 0)
            GLES30.glDeleteFramebuffers(targets.size, targets.map { it.fbo }.toIntArray(), 0)
        }
        labTarget = null
        detailTargets = null
        fringeTarget = null
        chainTargets.clear()
        developed = null
        developedShapeFree = null
        effectsGridValid = false
        detail.invalidate()
    }

    private fun bindTextures(unit0: Int, unit1: Int, unit2: Int = unit1) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit2)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, unit0)
    }

    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun drawOutput(
        image: UploadedImage,
        paneView: ViewTransform,
        width: Int,
        height: Int,
        settings: OutputSettings,
        surround: FloatArray,
        texture: Int = stageTex,
        clipping: ClippingWarnings = ClippingWarnings.NONE,
        mapping: GeometryMapping = GeometryMapping.identity(image.width, image.height),
        tiles: TileUniforms? = null,
        post: PostParams = PostParams.NONE,
        overlay: OverlayUniforms? = null,
        spots: SpotsView? = null,
    ) {
        val view = alignedView(paneView, width, height, mapping, tiles)
        val aligned = mapping.axisAligned
        val readsLevel0 = tiles != null || spots != null || OutputSampling.readsLevel0(view.scale, aligned)
        if (texture == stageTex && stageHalf && readsLevel0) ensureStageLevel0()
        // Visualise spots reads a coarse level for its local mean: the mips must be current.
        val wantsMips = OutputSampling.needsMips(view.scale, aligned) || spots != null
        if (texture == stageTex && !stageMipsValid && wantsMips) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, stageTex)
            // From level 1 when only it is current (levels above 1 only: readsLevel0 was false).
            if (stageHalf) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_BASE_LEVEL, 1)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            if (stageHalf) GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_BASE_LEVEL, 0)
            stageMipsValid = true
            profiler.mark("mipmaps")
        }
        val boxLevel = if (texture == stageTex && stageHalf) 1 else 0
        GLES30.glUseProgram(outputProgram)
        uploadUbo(
            outputUbo,
            outputBlock(
                image.width, image.height, view, width, height, settings, surround, clipping.bits, mapping, tiles,
                post, boxLevel, overlay, spots,
            ),
        )
        if (tiles != null) zoom.bind(tiles.nearest)
        if (overlay != null) masks.bindCoverage()
        lensLut.upload(mapping.warp?.lens?.table)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + LENS_UNIT)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lensLut.texture)
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

    /**
     * [view] moved by less than half a pixel so that, at exactly 1:1 on an axis-aligned mapping, screen pixel centres
     * land on pixel centres of what the pass reads with `texelFetch`: the full-resolution tiles at 100 %, else the
     * proxy at scale 1 (`PixelAlignment`, docs/adr/0012). Other views are unchanged.
     */
    private fun alignedView(
        view: ViewTransform,
        width: Int,
        height: Int,
        mapping: GeometryMapping,
        tiles: TileUniforms?,
    ): ViewTransform = when {
        tiles != null && tiles.mode == OutputSampling.MODE_TEXEL_FETCH -> PixelAlignment.tiles(
            view,
            width,
            height,
            ZoomView(tiles.k, tiles.proxyOutputWidth, tiles.proxyOutputHeight, tiles.full),
        )

        OutputSampling.stageMode(view.scale, mapping.axisAligned) == OutputSampling.MODE_TEXEL_FETCH ->
            PixelAlignment.proxy(view, width, height, mapping)

        else -> view
    }

    /** The output pass at 1:1 into the RGBA8 read target sized to [mapping]'s output. */
    private fun drawOutputOffscreen(
        settings: OutputSettings,
        clipping: ClippingWarnings = ClippingWarnings.NONE,
        mapping: GeometryMapping,
        post: PostParams = PostParams.NONE,
    ) {
        val image = checkNotNull(input) { "no input" }
        val w = mapping.outputWidth
        val h = mapping.outputHeight
        ensureReadTarget(w, h)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, readFbo)
        GlSupport.discardColour()
        GLES30.glViewport(0, 0, w, h)
        val identity = ViewTransform(1f, w / 2f, h / 2f)
        drawOutput(image, identity, w, h, settings, surround, clipping = clipping, mapping = mapping, post = post)
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
        prepareHeals(recipe.heals)
        runStage1(image.params(recipe))
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
        prepareHeals(recipe.heals)
        runStage1(image.params(recipe))
        val mapping = image.mapping(recipe)
        drawOutputOffscreen(settings, clipping, mapping, PostParams.from(recipe.effects))
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
        timerQueries = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty().split(' ')
            .contains(PassProfiler.TIMER_EXTENSION)
        profiler.gpuTimers = timerQueries
        sceneProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.SCENE_FRAG)
        developProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.DEVELOP_FRAG)
        outputProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, Shaders.OUTPUT_FRAG)
        bindBlock(sceneProgram, "Develop", DEVELOP_BINDING, "uSource")
        bindBlock(sceneProgram, "Light", LIGHT_BINDING, "uChroma", unit = 1)
        bindBlock(developProgram, "Develop", DEVELOP_BINDING, "uScene")
        bindBlock(developProgram, "Light", LIGHT_BINDING, "uCoef", unit = 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(developProgram, "uMoments"), 2)
        bindBlock(outputProgram, "Output", OUTPUT_BINDING, "uStage")
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uAtlas"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uTileIndex"), 2)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uLensLut"), LENS_UNIT)
        GLES30.glUseProgram(sceneProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(sceneProgram, "uVignetteLut"), LENS_UNIT)
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
        tools = ColourToolShaders.Gl(developProgram)
        masks.init()
        attachMasks(developProgram, develop = true)
        compileFusedCoverage()
        GLES30.glUseProgram(outputProgram)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(outputProgram, "uOverlay"), MaskShaders.COVERAGE_UNIT)
        light.init()
        detail.init()
        effects.init()
        defringe.init()
        val vaos = IntArray(1)
        GLES30.glGenVertexArrays(1, vaos, 0)
        vao = vaos[0]
        GLES30.glBindVertexArray(vao)
        GlSupport.checkError("renderer setup")
        probeFloatTarget()
    }

    /** The masks' fused range coverage: the develop stage's blocks and inputs, then [MaskPasses.attachFused]. */
    private fun compileFusedCoverage() {
        fusedCoverageProgram = GlSupport.compile(Shaders.FULLSCREEN_VERT, MaskShaders.FUSED_COVERAGE_FRAG)
        bindBlock(fusedCoverageProgram, "Develop", DEVELOP_BINDING, "uScene")
        bindBlock(fusedCoverageProgram, "Light", LIGHT_BINDING, "uCoef", unit = 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(fusedCoverageProgram, "uMoments"), 2)
        GLES30.glUniformBlockBinding(
            fusedCoverageProgram,
            GLES30.glGetUniformBlockIndex(fusedCoverageProgram, "Look"),
            ColourShaders.BINDING,
        )
        GLES30.glUniformBlockBinding(
            fusedCoverageProgram,
            GLES30.glGetUniformBlockIndex(fusedCoverageProgram, "Tools"),
            ColourToolShaders.BINDING,
        )
        GLES30.glUniform1i(
            GLES30.glGetUniformLocation(fusedCoverageProgram, "uCurve"),
            ColourToolShaders.CURVE_UNIT,
        )
        masks.attachFused(fusedCoverageProgram)
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

    private fun uploadInput(image: PipelineInput, pixelScale: Float) {
        deleteImageTextures()
        val grid = light.allocate(image.width, image.height)
        val reconstruct = image.saturation?.let { HighlightReconstructionOp.needed(image.linearRgb, it) } ?: false
        input = UploadedImage(image.width, image.height, image.colourOnly(), grid, reconstruct, pixelScale)
        stageParams = null
        developed = null
        developedShapeFree = null
        reconKey = null
        sceneKey = null
        guideValid = false
        val levels = 32 - Integer.numberOfLeadingZeros(maxOf(image.width, image.height))
        sourceTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        stageTex = newTexture(levels, image.width, image.height, GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR)
        sceneTex = newTexture(1, image.width, image.height, GLES30.GL_NEAREST, GLES30.GL_NEAREST)
        sceneFbo = newFramebuffer(sceneTex)
        stageFbo = newFramebuffer(stageTex)
        stageHalfFbo = if (levels > 1) newFramebuffer(stageTex, 1) else 0
        stageHalf = false
        stageMipsValid = false
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

    private fun newFramebuffer(texture: Int, level: Int = 0): Int {
        val id = IntArray(1)
        GLES30.glGenFramebuffers(1, id, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, id[0])
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            level,
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
        releaseHealing()
        releaseCompareAndReadbacks()
        deleteDetailTargets()
        masks.releaseImage()
        preTarget?.let {
            GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0)
            GLES30.glDeleteFramebuffers(1, intArrayOf(it.fbo), 0)
        }
        preTarget = null
        preKey = null
        preParams = null
        maskPlan = null
        maskPlanKey = null
        dabsCache = emptyMap()
        masksChanged(null)
        GLES30.glDeleteTextures(4, intArrayOf(sourceTex, stageTex, readTex, sceneTex), 0)
        GLES30.glDeleteFramebuffers(4, intArrayOf(stageFbo, readFbo, sceneFbo, stageHalfFbo), 0)
        sceneTex = 0
        sceneFbo = 0
        sourceTex = 0
        stageTex = 0
        readTex = 0
        stageFbo = 0
        stageHalfFbo = 0
        stageHalf = false
        readFbo = 0
        readWidth = 0
        readHeight = 0
    }

    private fun releaseGl() {
        if (!::info.isInitialized) return
        zoom.release()
        profiler.release()
        deleteImageTextures()
        masks.release()
        light.release()
        detail.release()
        effects.release()
        defringe.release()
        lensLut.release()
        vignetteLut.release()
        GLES30.glDeleteBuffers(4, intArrayOf(developUbo, outputUbo, lightUbo, lookUbo), 0)
        if (::tools.isInitialized) tools.release()
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteProgram(sceneProgram)
        GLES30.glDeleteProgram(developProgram)
        GLES30.glDeleteProgram(fusedCoverageProgram)
        GLES30.glDeleteProgram(outputProgram)
    }

    private fun uploadUbo(buffer: Int, data: ByteBuffer) {
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, buffer)
        GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, data.remaining(), data, GLES30.GL_DYNAMIC_DRAW)
    }

    // ------------------------------------------------------------------ compare, histogram, sampling (GL thread)

    /** Draws the output pass once per compare pane, scissored to the pane where it has a clip. */
    @Suppress("LongParameterList", "CyclomaticComplexMethod")
    private fun drawPanes(
        image: UploadedImage,
        panes: List<ComparePane>,
        width: Int,
        height: Int,
        req: Request,
        mapping: GeometryMapping,
        tilesOn: Boolean = false,
    ) {
        val settings = OutputSettings(req.space)
        // The surround everywhere, then each pane's output only where the output can be (docs/adr/0012, "M2/P"): the
        // window is the whole screen behind the chrome, and the output pass costs per pixel.
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        GLES30.glClearColor(surround[0], surround[1], surround[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        for (pane in panes) {
            val rect = CompareLayout.outputRect(
                pane.view,
                width,
                height,
                mapping.outputWidth,
                mapping.outputHeight,
                pane.clip,
            ) ?: continue
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(rect[0], height - (rect[1] + rect[3]), rect[2], rect[3])
            drawOutput(
                image = image,
                paneView = pane.view,
                width = width,
                height = height,
                settings = settings,
                surround = surround,
                texture = if (pane.original) originalTex else stageTex,
                clipping = if (pane.original || req.geometryEditing) ClippingWarnings.NONE else req.clipping,
                mapping = mapping,
                tiles = if (tilesOn && !pane.original) zoom.uniforms(pane.view, mapping) else null,
                post = if (pane.original) PostParams.NONE else shownPost(req),
                overlay = if (pane.original) null else overlayUniforms(req),
                spots = if (pane.original || req.showOriginal || req.geometryEditing) null else req.spots,
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
        val heals = wantHeals
        wantHeals = -1
        runStage1(image.params(Recipe.DEFAULT))
        wantHeals = heals
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
                drawOutput(
                    image,
                    view,
                    w,
                    h,
                    OutputSettings(dither = false),
                    surround,
                    mapping = mapping,
                    post = shownPost(request.get()),
                )
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
        /** [maskChange] values: no change, and a change anywhere. */
        private val NO_CHANGE = IntArray(0)
        private val FULL_CHANGE = IntArray(0)

        /** [partialRect]'s "the masks need nothing redrawn". */
        private val EMPTY_RECT = IntArray(4)

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
            tiles: TileUniforms? = null,
            post: PostParams = PostParams.NONE,
            boxLevel: Int = 0,
            overlay: OverlayUniforms? = null,
            spots: SpotsView? = null,
        ): ByteBuffer {
            val b = ByteBuffer.allocateDirect(OUTPUT_BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            putMat4(b, OutputTransformOp.workingToOutput(settings.space).toFloatArray())
            b.putFloat(
                imageWidth.toFloat(),
            ).putFloat(imageHeight.toFloat()).putFloat(view.scale).putFloat(
                if (OutputSampling.stageMode(view.scale, mapping.axisAligned) == OutputSampling.MODE_BOX) {
                    boxLevel.toFloat()
                } else {
                    OutputSampling.lod(view.scale)
                },
            )
            b.putFloat(view.centerX).putFloat(view.centerY).putFloat(width.toFloat()).putFloat(height.toFloat())
            b.putFloat(surround[0]).putFloat(surround[1]).putFloat(surround[2]).putFloat(1f)
            val aligned = mapping.axisAligned
            b.putInt(settings.ditherSeed).putInt(if (settings.dither) 1 else 0)
                .putInt(OutputSampling.stageMode(view.scale, aligned)).putInt(clippingBits)
            val m = mapping.toImageFloats()
            b.putFloat(m[0]).putFloat(m[1]).putFloat(m[2]).putFloat(mapping.outputWidth.toFloat())
            b.putFloat(m[3]).putFloat(m[4]).putFloat(m[5]).putFloat(mapping.outputHeight.toFloat())
            b.putInt(if (OutputSampling.snapToOutputPixels(view.scale, aligned)) 1 else 0)
                .putInt(if (mapping.emptyIsSurround) 1 else 0).putInt(0).putInt(0)
            putTiles(b, tiles)
            putPost(b, post, mapping, tiles)
            putWarp(b, mapping.warp, tiles?.full?.warp)
            putOverlay(b, overlay)
            // Visualise spots (docs/adr/0023): on, gain, the level of the local mean (about 8 screen pixels).
            if (spots == null) {
                repeat(SPOTS_BLOCK_FLOATS) { b.putFloat(0f) }
            } else {
                val level = (maxOf(OutputSampling.lod(view.scale), 0f) + SPOTS_LEVELS)
                    .coerceAtMost((32 - Integer.numberOfLeadingZeros(maxOf(imageWidth, imageHeight)) - 1).toFloat())
                b.putFloat(1f).putFloat(SpotsView.gain(spots.strength)).putFloat(level).putFloat(0f)
            }
            b.flip()
            return b
        }

        /**
         * The mask overlay (docs/adr/0022): colour (linear working space, opacity), info (on, -, channel, mode), the
         * coverage layer's cell (origin, texture size).
         */
        private fun putOverlay(b: ByteBuffer, o: OverlayUniforms?) {
            if (o == null) {
                repeat(OVERLAY_BLOCK_FLOATS) { b.putInt(0) }
                return
            }
            b.putFloat(o.colour[0]).putFloat(o.colour[1]).putFloat(o.colour[2]).putFloat(o.opacity)
            b.putInt(1).putInt(0).putInt(o.channel).putInt(if (o.mode == MaskOverlay.Mode.GREYSCALE) 1 else 0)
            o.cell.forEach { b.putFloat(it) }
        }

        /** The warp part of the output block (docs/adr/0015): norms, homography rows, flags, full-res norm. */
        private fun putWarp(b: ByteBuffer, warp: Warp?, fullWarp: Warp?) {
            WarpShaders.norm(warp).forEach { b.putFloat(it) }
            WarpShaders.rows(warp).forEach { b.putFloat(it) }
            b.putInt(if (warp != null) 1 else 0).putInt(if (warp?.lens != null) 1 else 0)
                .putInt(if (warp?.chromatic == true) 1 else 0).putInt(0)
            WarpShaders.norm(fullWarp).forEach { b.putFloat(it) }
        }

        /** The tile part of the output block (zeros when the pane shows no tiles). */
        private fun putTiles(b: ByteBuffer, t: TileUniforms?) {
            if (t == null) {
                repeat(TILE_BLOCK_FLOATS) { b.putFloat(0f) }
                return
            }
            val f = t.full.toImageFloats()
            b.putFloat(t.k).putFloat(1f).putFloat(t.fullOutputWidth.toFloat()).putFloat(t.fullOutputHeight.toFloat())
            b.putFloat(f[0]).putFloat(f[1]).putFloat(f[2]).putFloat(t.proxyOutputWidth.toFloat())
            b.putFloat(f[3]).putFloat(f[4]).putFloat(f[5]).putFloat(t.proxyOutputHeight.toFloat())
            b.putFloat(ZoomGrid.TILE.toFloat()).putFloat(ZoomGrid.BORDER.toFloat()).putFloat(ZoomGrid.SLOT.toFloat())
                .putFloat(t.lod)
            b.putFloat(t.imageWidth.toFloat()).putFloat(t.imageHeight.toFloat()).putFloat(t.atlasWidth.toFloat())
                .putFloat(t.atlasHeight.toFloat())
            b.putInt(t.mode).putInt(if (t.snap) 1 else 0).putInt(t.indexWidth).putInt(t.indexHeight)
        }

        /**
         * The post-crop finish (docs/adr/0014): post0 = (vignette, midpoint, feather, highlights), post1 = (round,
         * exponent, norm, flags), post2 = (grain, roughness), post3 = lattice cells per output pixel of the proxy's
         * output and of the full-resolution tiles' output.
         */
        private fun putPost(b: ByteBuffer, p: PostParams, mapping: GeometryMapping, tiles: TileUniforms?) {
            val flags = (if (p.vignetteActive) 1 else 0) or (if (p.grainActive) 2 else 0)
            b.putFloat(p.vignette).putFloat(p.midpoint).putFloat(p.feather).putFloat(p.highlights)
            b.putFloat(p.round).putFloat(p.exponent).putFloat(p.norm).putFloat(flags.toFloat())
            b.putFloat(p.grain).putFloat(p.roughness).putFloat(0f).putFloat(0f)
            val full = tiles?.let { p.grainScale(maxOf(it.fullOutputWidth, it.fullOutputHeight)) } ?: 0f
            b.putFloat(p.grainScale(maxOf(mapping.outputWidth, mapping.outputHeight))).putFloat(full)
                .putFloat(0f).putFloat(0f)
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
        private const val TILE_BLOCK_FLOATS = 24
        private const val POST_BLOCK_FLOATS = 16
        private const val WARP_BLOCK_FLOATS = 24
        private const val OVERLAY_BLOCK_FLOATS = 12
        private const val SPOTS_BLOCK_FLOATS = 4

        /** Levels above the displayed one for Visualise spots' local mean. */
        private const val SPOTS_LEVELS = 3f
        private const val OUTPUT_BLOCK_FLOATS =
            44 + TILE_BLOCK_FLOATS + POST_BLOCK_FLOATS + WARP_BLOCK_FLOATS + OVERLAY_BLOCK_FLOATS + SPOTS_BLOCK_FLOATS
        private const val FADE_TICK_MS = 16L
        private const val FENCE_TIMEOUT_NS = 2_000_000_000L
        private const val GL_TIME_ELAPSED_EXT = 0x88BF

        /** Views at this scale or below read the working texture's level 1 or coarser (the half-resolution write). */
        private const val HALF_VIEW_SCALE = 0.5f
    }
}

/**
 * [GpuDevelopRenderer.benchmarkPipelined]'s result: the sustained wall milliseconds per frame and, where the driver has
 * timer queries, each frame's GPU milliseconds (empty otherwise).
 */
data class PipelinedTimes(val msPerFrame: Float, val gpuMs: List<Float>)

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
    val pixelScale: Float,
) {
    private var baseKey: Recipe? = null
    private var base: DevelopParams? = null

    /**
     * The operator parameters of [recipe] for this image at its pixel scale. Everything but Detail and Effects is
     * kept from the previous call while the rest of the recipe (geometry aside, which no parameter depends on) is
     * unchanged: a Detail or Effects drag recomputes only
     * its own group (the curve table, the mixer and grading parameters cost a millisecond or more per frame). GL
     * thread only.
     */
    fun params(recipe: Recipe): DevelopParams {
        // The geometry is the output pass' alone: a Transform or crop drag keeps the parameters too.
        val key = recipe.withoutLocal().copy(geometry = Geometry())
        val kept = base?.takeIf { baseKey == key } ?: DevelopParams.from(colour, key, pixelScale).also {
            baseKey = key
            base = it
        }
        return kept.withLocal(colour, recipe, pixelScale)
    }

    /** The geometry operator's mapping of [recipe] (lens corrections included), or the crop mode's plane. */
    fun mapping(recipe: Recipe, editing: Boolean = false): GeometryMapping = if (editing) {
        GeometryMapping.editing(recipe.geometry, width, height, colour.lens, recipe.optics)
    } else {
        GeometryMapping.of(recipe, colour.lens, width, height)
    }
}

/**
 * What [GpuDevelopRenderer.renderShown] read back: RGBA8 pixels of the [width] x [height] window (top row first), the
 * photo's [frame], the pane's [view] (window terms, as the output pass uses it), the proxy's [pixelScale] and its
 * geometry output size; `ZoomView(pixelScale, proxyOutputWidth, proxyOutputHeight, fullMapping).imagePoint(view, ...)`
 * maps a window pixel to the full-resolution image.
 */
class ShownView(
    val rgba: ByteArray,
    val width: Int,
    val height: Int,
    val frame: ViewFrame,
    val view: ViewTransform,
    val pixelScale: Float,
    val proxyOutputWidth: Int,
    val proxyOutputHeight: Int,
)

/** What the scene pass (and so the guide) depends on: when it is unchanged, both are reused. */
private data class SceneKey(
    val wb: List<Float>,
    val matrix: List<Float>,
    val exposureGain: Float,
    val highlights: Any?,
    val vignette: Any?,
)

/**
 * Sets the scene pass's vignetting uniforms (`VignetteOp`) for an image of [imageWidth] x [imageHeight] (the whole
 * image, also for a tile: the pass adds `uOrigin`) and binds [lut] on [LENS_UNIT] (GL thread, program in use).
 */
internal fun bindVignette(
    program: Int,
    params: DevelopParams,
    lut: WarpShaders.LutTexture,
    imageWidth: Int,
    imageHeight: Int,
) {
    val table = params.vignette
    lut.upload(table?.let { WarpShaders.vignetteTexels(it.gains) })
    val n = VignetteOp.norm(imageWidth, imageHeight)
    GLES30.glUniform4f(
        GLES30.glGetUniformLocation(program, "uVignette"),
        if (table != null) 1f else 0f,
        n[0],
        n[1],
        n[2],
    )
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + LENS_UNIT)
    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lut.texture)
    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
}

/** Texture unit of the lens tables (vignetting in the scene pass, the lens warp in the output passes). */
internal const val LENS_UNIT: Int = 5

/**
 * A mask's coverage shown over the photo (docs/adr/0022, "Overlay"): the mask [maskId] of the recipe, in [mode]; COLOUR
 * blends [colour] (opaque sRGB `0xRRGGBB`) at [opacity] (0..1) times the coverage, GREYSCALE shows the coverage alone.
 */
data class MaskOverlay(
    val maskId: String,
    val mode: Mode = Mode.COLOUR,
    val colour: Int = DEFAULT_COLOUR,
    val opacity: Float = DEFAULT_OPACITY,
) {
    enum class Mode { COLOUR, GREYSCALE }

    companion object {
        /** A saturated red, like other editors' mask overlays. */
        const val DEFAULT_COLOUR: Int = 0xFF2A2A
        const val DEFAULT_OPACITY: Float = 0.5f

        /** [rgb] (sRGB `0xRRGGBB`) in the working space (linear Rec.2020). */
        internal fun linearColour(rgb: Int): FloatArray {
            fun lin(v: Int): Double {
                val e = v / 255.0
                return if (e <= 0.04045) e / 12.92 else StrictMath.pow((e + 0.055) / 1.055, 2.4)
            }
            val m = dev.hfps.reveal.pipeline.cpu.ColorSpaces.REC2020.fromXyz *
                dev.hfps.reveal.pipeline.cpu.ColorSpaces.SRGB.toXyz
            val v = m.times(lin(rgb shr 16 and 0xff), lin(rgb shr 8 and 0xff), lin(rgb and 0xff))
            return FloatArray(3) { v[it].toFloat() }
        }
    }
}

/** What the output pass needs of a [MaskOverlay]: the coverage cell and channel, mode, linear colour and opacity. */
internal class OverlayUniforms(
    val cell: FloatArray,
    val channel: Int,
    val mode: MaskOverlay.Mode,
    val colour: FloatArray,
    val opacity: Float,
)

/** What the last mask coverage update did (`GpuDevelopRenderer.maskStats`). */
data class MaskStats(val brushBins: Int, val brushFull: Int, val coverageLayers: Int, val ms: Float)

/** What stage 1's develop output depends on, besides Detail and Effects (see `runStage1`). */
private data class DevelopKey(val params: DevelopParams, val local: Boolean, val masks: Int, val heals: Int)

/**
 * The Remove spots of the shown recipe whose fill is still being computed ([pending]), failed ([failed]), or was made
 * by the patch method because the spot's model could not run ([fellBack], docs/adr/0024), by id.
 */
data class HealStatus(
    val pending: Set<String> = emptySet(),
    val failed: Set<String> = emptySet(),
    val fellBack: Set<String> = emptySet(),
)

/** The "Visualise spots" view (docs/adr/0023): high-pass [strength] 0..100. */
data class SpotsView(val strength: Float) {
    companion object {
        /** The high-pass gain of [strength]: 4 at 0 up to 40 at 100 (geometric). */
        fun gain(strength: Float): Float =
            (4.0 * StrictMath.pow(10.0, (strength.coerceIn(0f, 100f) / 100f).toDouble())).toFloat()
    }
}

/**
 * The proxy's source texture read back to the CPU for the healing engine (docs/adr/0023): blocks of [BLOCK] x [BLOCK]
 * pixels read once (`glReadPixels`, float) and kept. GL thread only.
 */
private class GlSourceReader(override val width: Int, override val height: Int, private val fbo: () -> Int) :
    PixelReader {
    private val blocks = HashMap<Int, FloatArray>()
    override val threadSafe: Boolean get() = false

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val c = PipelineInput.CHANNELS
        for (by in y / BLOCK..(y + h - 1) / BLOCK) {
            for (bx in x / BLOCK..(x + w - 1) / BLOCK) {
                val block = block(bx, by)
                val bw = minOf(BLOCK, width - bx * BLOCK)
                val x0 = maxOf(x, bx * BLOCK)
                val x1 = minOf(x + w, bx * BLOCK + bw)
                val y0 = maxOf(y, by * BLOCK)
                val y1 = minOf(y + h, by * BLOCK + minOf(BLOCK, height - by * BLOCK))
                for (row in y0 until y1) {
                    System.arraycopy(
                        block,
                        ((row - by * BLOCK) * bw + (x0 - bx * BLOCK)) * c,
                        out,
                        ((row - y) * w + (x0 - x)) * c,
                        (x1 - x0) * c,
                    )
                }
            }
        }
    }

    private fun block(bx: Int, by: Int): FloatArray = blocks.getOrPut(by * BLOCKS_PER_ROW + bx) {
        val bw = minOf(BLOCK, width - bx * BLOCK)
        val bh = minOf(BLOCK, height - by * BLOCK)
        val pixels = FloatBuffer.allocate(bw * bh * 4)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo())
        // Texture row 0 is the image's top row (uploadInput): framebuffer y addresses texture rows directly.
        GLES30.glReadPixels(bx * BLOCK, by * BLOCK, bw, bh, GLES30.GL_RGBA, GLES30.GL_FLOAT, pixels)
        GlSupport.checkError("healing readback")
        FloatArray(bw * bh * PipelineInput.CHANNELS) { pixels.get((it / 3) * 4 + it % 3) }
    }

    private companion object {
        const val BLOCK = 128
        const val BLOCKS_PER_ROW = 1 shl 12
    }
}
