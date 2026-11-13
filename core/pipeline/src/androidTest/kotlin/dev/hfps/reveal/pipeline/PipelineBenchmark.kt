package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.mask.Dabs
import dev.hfps.reveal.pipeline.mask.MaskContext
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Frame time while dragging a slider at a ~2400 px proxy: every pass of the develop pipeline + the output pass per
 * frame (stage textures mip-mapped as on screen), 120 different recipes, `glFinish` after each. Prints `REVEAL_BENCH`
 * lines (milliseconds); the orchestrator re-runs it on the phone. Not an assertion test: numbers differ per device,
 * the log is the result.
 */
@RunWith(AndroidJUnit4::class)
class PipelineBenchmark {
    @Test
    fun exposureDragFrameTime(): Unit = runBlocking {
        drag("exposure", SyntheticImages.scene(WIDTH, HEIGHT)) { Recipe(light = Light(-2f + 4f * it)) }
    }

    /** Every Light slider active, dragging highlights/shadows: guide passes + local and global tone every frame. */
    @Test
    fun lightDragFrameTime(): Unit = runBlocking {
        drag("light (all sliders, highlights/shadows drag)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            Recipe(
                light = Light(
                    -0.3f,
                    contrast = 35f,
                    highlights = -100f + 100f * it,
                    shadows = 100f - 60f * it,
                    whites = 20f,
                    blacks = -10f,
                ),
            )
        }
    }

    /** A RAW with clipped highlights while dragging temperature: the reconstruction pyramid reruns every frame. */
    @Test
    fun highlightReconstructionDragFrameTime(): Unit = runBlocking {
        val image = SyntheticImages.clippedHighlights(WIDTH, HEIGHT)
        drag("white balance on clipped highlights (reconstruction every frame)", image) {
            Recipe(
                light = Light(-1f, highlights = -60f, shadows = 40f),
                colour = Colour(WhiteBalance(temperatureK = 3000f + 6000f * it)),
            )
        }
    }

    /**
     * The costliest colour path (docs/adr/0008): Camera JPEG match (matrix, curve, hue treatment: one OkLab round
     * trip) plus vibrance and saturation (a second one), with Light's tone sliders on, dragging vibrance.
     */
    @Test
    fun colourDragFrameTime(): Unit = runBlocking {
        drag("colour (camera match + vibrance/saturation drag, light on)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            Recipe(
                profile = Profile(BaseLook.CAMERA_MATCH, 120f),
                light = Light(0.3f, contrast = 20f, highlights = -40f, shadows = 30f),
                colour = Colour(vibrance = -100f + 200f * it, saturation = 25f),
            )
        }
    }

    /**
     * Detail on the editor's proxy (docs/adr/0012): a 24 MP photo's 2400 px proxy (pixel scale 2.5: four wavelet levels
     * run), dragging luminance noise reduction with colour NR and sharpening on. The develop output is cached, so this
     * is the Detail passes, the mips and the output pass.
     */
    @Test
    fun detailDragFrameTime(): Unit = runBlocking {
        drag("detail (luminance NR drag, colour NR + sharpening on, proxy of 24 MP)", noisy(), PROXY_SCALE) {
            Recipe(
                light = Light(0.2f, contrast = 15f),
                detail = Detail(
                    nrLuminance = 100f * it,
                    nrColour = 50f,
                    sharpenAmount = 50f,
                    sharpenMasking = 30f,
                ),
            )
        }
    }

    /** Sharpening alone on the proxy, dragging the amount with masking on. */
    @Test
    fun sharpenDragFrameTime(): Unit = runBlocking {
        drag("detail (sharpening drag, masking 30, proxy of 24 MP)", noisy(), PROXY_SCALE) {
            Recipe(light = Light(0.2f), detail = Detail(sharpenAmount = 150f * it, sharpenMasking = 30f))
        }
    }

    /**
     * The tone curve (docs/adr/0013): a point of the RGB curve dragged up and down over a medium-contrast curve with
     * a red channel curve and the parametric curve on (the lookup table is rebuilt and uploaded every frame), with
     * Light's tone sliders on.
     */
    @Test
    fun curveDragFrameTime(): Unit = runBlocking {
        drag("curve (point drag, parametric + channel curve, light on)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            val y = 0.35f + 0.3f * it
            Recipe(
                light = Light(
                    0.3f,
                    contrast = 20f,
                    highlights = -40f,
                    shadows = 30f,
                    curve = Curves(
                        parametric = ParametricCurve(highlights = -20f, shadows = 15f),
                        rgb = listOf(
                            CurvePoint(0f, 0f),
                            CurvePoint(0.25f, 0.2f),
                            CurvePoint(0.5f, y),
                            CurvePoint(1f, 1f),
                        ),
                        red = Curves.MEDIUM_CONTRAST,
                    ),
                ),
            )
        }
    }

    /**
     * The colour mixer (docs/adr/0013): a hue drag of one band with saturation/luminance on other bands, colour
     * grading on and Camera JPEG match (the most expensive look), so every OkLab round trip of the develop pass runs.
     */
    @Test
    fun hslDragFrameTime(): Unit = runBlocking {
        drag("hsl (mixer hue drag, grading on, camera match)", SyntheticImages.scene(WIDTH, HEIGHT)) {
            Recipe(
                profile = Profile(BaseLook.CAMERA_MATCH, 120f),
                light = Light(0.3f, contrast = 20f),
                colour = Colour(
                    vibrance = 20f,
                    mixer = ColourMixer(
                        hue = BandValues(orange = -100f + 200f * it),
                        saturation = BandValues(blue = 30f, green = -20f),
                        luminance = BandValues(blue = -40f),
                    ),
                    grading = ColourGrading(shadows = GradeWheel(220f, 20f), highlights = GradeWheel(50f, 15f)),
                ),
            )
        }
    }

    /**
     * Every Detail and Effects slider dragged on the proxy of a 24 MP photo (pixel scale 2.5) with the rest of the
     * panel on (luminance and colour NR, sharpening with masking, texture, clarity, dehaze, vignette, grain): one
     * `REVEAL_BENCH` line per slider. The develop output, the Effects grid and the slot 11 intermediates the slider
     * does not change are cached, as on screen (docs/adr/0012, "Drag cost"; docs/adr/0014).
     */
    @Test
    fun detailAndEffectsSliderDrags(): Unit = runBlocking {
        val base = Recipe(light = Light(0.2f, contrast = 15f), detail = ALL_DETAIL, effects = ALL_EFFECTS)
        val image = noisy()
        val profile = InstrumentationRegistry.getArguments().getString("profile") == "true"
        for ((name, at) in SLIDERS) {
            if (profile) {
                profileDrag(name, image) { at(base, it) }
                continue
            }
            drag("drag $name (Detail + Effects on, proxy of 24 MP)", image, PROXY_SCALE) {
                at(base, it)
            }
        }
    }

    /**
     * Every tool on (Light, Camera JPEG match, tone curve, colour mixer, colour grading, Detail, Effects) and each
     * Detail and Effects slider dragged in turn on the proxy of a 24 MP photo, the output drawn as the editor draws it
     * (fitted to the photo frame, `-e frame WxH`, default [FRAME]): one `REVEAL_BENCH` line per slider with p50/p95 and
     * whether it meets the display-rate target (docs/adr/0012, "M2/P": p50 <= 8.3 ms, p95 <= 12 ms). `-e profile true`
     * logs the per-pass profile instead (GPU timer queries where available).
     */
    @Test
    fun everyToolOnSliderDrags(): Unit = runBlocking {
        val base = Recipe(
            profile = Profile(BaseLook.CAMERA_MATCH, 110f),
            light = Light(
                0.2f,
                contrast = 15f,
                highlights = -30f,
                shadows = 25f,
                curve = Curves(parametric = ParametricCurve(highlights = -10f), red = Curves.MEDIUM_CONTRAST),
            ),
            colour = Colour(
                vibrance = 15f,
                mixer = ColourMixer(hue = BandValues(orange = 10f), saturation = BandValues(blue = 20f)),
                grading = ColourGrading(shadows = GradeWheel(220f, 15f), highlights = GradeWheel(50f, 10f)),
            ),
            detail = ALL_DETAIL,
            effects = ALL_EFFECTS,
        )
        val image = noisy()
        val profile = InstrumentationRegistry.getArguments().getString("profile") == "true"
        val frame = frameArgument()
        for ((name, at) in SLIDERS) {
            coolDown()
            if (profile) {
                profileDrag("all tools: $name", image, frame) { at(base, it) }
            } else {
                drag("all tools on, drag $name", image, PROXY_SCALE, frame, target = true) { at(base, it) }
            }
        }
    }

    /**
     * Dragging the Transform's vertical slider (docs/adr/0015) on a photo with lens corrections and CA removal: the
     * stage is cached, every frame is the output pass through the warp (three Catmull-Rom samples per pixel). At 1:1
     * into a 2400 x 1600 target (the worst case, an export tile) and as the editor shows it (fitted to `-e frame`);
     * plus the lens distortion amount and the defringe amount. `-e profile true` logs the per-pass profile.
     */
    @Test
    fun transformDragFrameTime(): Unit = runBlocking {
        val image = SyntheticImages.scene(WIDTH, HEIGHT).withLens(GpuParityTest.PARITY_LENS)
        val transform = { t: Float ->
            Recipe(
                optics = Optics(lensCorrections = true, removeCa = true),
                geometry = Geometry(transform = Transform(vertical = -40f + 80f * t, scale = 110f)),
            )
        }
        val drags: List<Triple<String, Pair<Int, Int>?, (Float) -> Recipe>> = listOf(
            Triple("transform vertical 1:1 (lens + CA on)", null, transform),
            Triple("transform vertical, fit view (lens + CA on)", frameArgument(), transform),
            Triple("lens distortion amount, fit view", frameArgument()) { t: Float ->
                Recipe(optics = Optics(lensCorrections = true, distortionPercent = 200f * t))
            },
            Triple("defringe purple amount (proxy scale), fit view", frameArgument()) { t: Float ->
                Recipe(optics = Optics(purpleAmount = 20f * t))
            },
        )
        val profile = InstrumentationRegistry.getArguments().getString("profile") == "true"
        for ((name, frame, at) in drags) {
            if (profile) {
                profileDrag(name, image, frame, recipeAt = at)
            } else {
                drag(name, image, PROXY_SCALE, frame, target = true, recipeAt = at)
            }
        }
    }

    /**
     * Masks (docs/adr/0022): five masks, one of each type (linear, radial, a brush of 2,000+ dabs, luminance + colour
     * range, AI from a fake source), every local adjustment somewhere, on the proxy of a 24 MP photo shown fitted
     * (`-e frame`). Drags of a mask's exposure, clarity and noise (the shapes stay cached: no coverage pass), the same
     * recipe's global exposure for comparison, then a stroke in progress: 5 points appended per frame to a fifth mask's
     * brush, each frame re-rasterising only the new dabs' bins (`REVEAL_BENCH masks ...`).
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    @Test
    fun maskSliderDragAndBrushAppend(): Unit = runBlocking {
        val image = SyntheticImages.scene(WIDTH, HEIGHT)
        val stroke = longStroke()
        val brush = MaskComponent.Brush(strokes = listOf(BrushStroke.raw(stroke, size = 0.02f, feather = 50f)))
        val base = MaskFixtures.fiveMasks(brush)
        val dabs = Dabs.of(brush.strokes, WIDTH, HEIGHT).size
        Log.i(TAG, "masks: brush of ${stroke.size} points, $dabs dabs at ${WIDTH}x$HEIGHT")
        val frame = frameArgument()
        fun local(change: (LocalAdjustments) -> LocalAdjustments) = base.copy(
            masks = base.masks.mapIndexed { i, m ->
                if (i ==
                    0
                ) {
                    m.copy(adjustments = change(m.adjustments))
                } else {
                    m
                }
            },
        )
        val drags: List<Pair<String, (Float) -> Recipe>> = listOf(
            "masks: global exposure (five masks on)" to
                { t -> base.copy(light = base.light.copy(exposureEv = -1f + 2f * t)) },
            // An auto-mask brush reads the pre-local image: a global drag re-rasterises its layer whole.
            "masks: global exposure, an auto-mask brush too" to { t ->
                base.copy(
                    light = base.light.copy(exposureEv = -1f + 2f * t),
                    masks = base.masks + Mask(
                        "auto",
                        adjustments = LocalAdjustments(saturation = 30f),
                        components = listOf(
                            MaskComponent.Brush(
                                strokes = listOf(BrushStroke.raw(longStroke(0.6), size = 0.03f, autoMask = true)),
                            ),
                        ),
                    ),
                )
            },
            "masks: drag a mask's exposure" to { t -> local { it.copy(exposure = -2f + 4f * t) } },
            "masks: drag a mask's clarity" to { t -> local { it.copy(clarity = -100f + 200f * t) } },
            "masks: drag a mask's noise" to { t -> local { it.copy(noise = 100f * t) } },
            "masks: off (same global recipe)" to { t ->
                base.copy(masks = emptyList(), light = base.light.copy(exposureEv = -1f + 2f * t))
            },
            // The floor of a drag that changes the develop output: the stages the masks switch on, on globally.
            "masks: off, their Detail and Effects stages on globally (reference)" to { t ->
                base.copy(
                    masks = emptyList(),
                    light = base.light.copy(exposureEv = -1f + 2f * t),
                    detail = Detail(nrLuminance = 10f, sharpenAmount = 10f),
                    effects = Effects(texture = 10f, clarity = 10f, dehaze = 10f),
                )
            },
        )
        val profile = InstrumentationRegistry.getArguments().getString("profile") == "true"
        // `-e only <text>`: only the drags whose name contains it ("stroke": only the stroke).
        val only = InstrumentationRegistry.getArguments().getString("only")
        for ((name, at) in drags) {
            if (only != null && !name.contains(only)) continue
            coolDown()
            if (profile) {
                profileDrag(name, image, frame, MaskFixtures.context, at)
            } else {
                drag(name, image, PROXY_SCALE, frame, target = true, masks = MaskFixtures.context, recipeAt = at)
            }
        }
        // A stroke in progress on a sixth mask: points appended every frame.
        if (only != null && !"stroke".contains(only)) return@runBlocking
        val renderer = GpuDevelopRenderer.create()
        try {
            renderer.setInput(image, PROXY_SCALE)
            renderer.setMaskSource(MaskFixtures.source, "fixture")
            val drawing = longStroke(seed = 1.7)
            var auto = false
            fun withStroke(n: Int) = base.copy(
                masks = base.masks + Mask(
                    "stroke",
                    adjustments = LocalAdjustments(exposure = 0.5f),
                    components = listOf(
                        MaskComponent.Brush(
                            strokes = listOf(BrushStroke.raw(drawing.take(n), size = 0.03f, autoMask = auto)),
                        ),
                    ),
                ),
            )
            // The same stroke with the auto-mask on (docs/adr/0022 "Brush auto-mask"), pipelined.
            auto = true
            val autoGrowth = (2..drawing.size step APPEND_POINTS).map { withStroke(it) }
            warmUp(renderer, autoGrowth.take(WARMUP_STROKE), frame)
            renderer.benchmark(listOf(withStroke(2)), frame)
            val autoPiped = renderer.benchmarkPipelined(autoGrowth, frame)
            val ag = autoPiped.gpuMs.sorted()
            Log.i(
                TAG,
                "masks: auto-mask stroke append pipelined (${autoGrowth.size} frames): " +
                    "${"%.2f".format(autoPiped.msPerFrame)} ms per frame" +
                    if (ag.isEmpty()) "" else ", GPU per frame p50 ${p(ag, 50)}, p95 ${p(ag, 95)}",
            )
            auto = false
            // As the display runs it: the stroke growing frame after frame, two frames in flight (the target).
            val growth = (2..drawing.size step APPEND_POINTS).map { withStroke(it) }
            warmUp(renderer, growth.take(WARMUP_STROKE), frame)
            renderer.benchmark(listOf(withStroke(2)), frame)
            val piped = renderer.benchmarkPipelined(growth, frame)
            val pg = piped.gpuMs.sorted()
            val pipedMet = piped.msPerFrame <= TARGET_P50 && (
                pg.isEmpty() || (
                    p(pg, 50).replace(',', '.')
                        .toFloat() <= TARGET_P50 && p(pg, 95).replace(',', '.').toFloat() <= STROKE_P95
                    )
                )
            Log.i(
                TAG,
                "masks: stroke append pipelined (${growth.size} frames, six masks): ${"%.2f".format(
                    piped.msPerFrame,
                )} " +
                    "ms per frame" + (if (pg.isEmpty()) "" else ", GPU per frame p50 ${p(pg, 50)}, p95 ${p(pg, 95)}") +
                    (if (pipedMet) " [target met]" else " [target MISSED]") +
                    "; partial updates ${renderer.partialUpdates}",
            )
            renderer.benchmark(listOf(withStroke(2)), frame)
            val frames = ArrayList<Float>()
            val coverage = ArrayList<Float>()
            val bins = ArrayList<Int>()
            var n = 2
            while (n < drawing.size) {
                n = minOf(n + APPEND_POINTS, drawing.size)
                frames += renderer.benchmark(listOf(withStroke(n)), frame).single()
                val stats = renderer.maskStats
                coverage += stats.ms
                bins += stats.brushBins
            }
            val f = frames.sorted()
            val c = coverage.sorted()
            Log.i(
                TAG,
                "masks: stroke append ($APPEND_POINTS points a frame, ${frames.size} frames, six masks): frame " +
                    "p50 ${p(
                        f,
                        50,
                    )}, p95 ${p(f, 95)} ms; brush + coverage update p50 ${p(c, 50)}, p95 ${p(c, 95)} ms; " +
                    "bins per frame mean ${"%.1f".format(bins.average())}",
            )
        } finally {
            renderer.close()
        }
    }

    /** A long wandering stroke (fractions of the image) giving 2,000+ dabs at size 0.02 on the proxy. */
    private fun longStroke(seed: Double = 0.0): List<StrokePoint> = List(STROKE_POINTS) { i ->
        val t = i / STROKE_POINTS.toDouble()
        StrokePoint(
            (0.1 + 0.8 * t + 0.05 * kotlin.math.sin(t * 40 + seed)).toFloat(),
            (0.5 + 0.35 * kotlin.math.sin(t * 9 * kotlin.math.PI + seed)).toFloat(),
            (0.6 + 0.4 * kotlin.math.cos(t * 23)).toFloat(),
        )
    }

    private fun noisy() = SyntheticImages.noisyCard(WIDTH, HEIGHT, seed = 5)

    private fun p(sorted: List<Float>, percent: Int): String = "%.2f".format(sorted[(sorted.size - 1) * percent / 100])

    private fun meets(sorted: List<Float>): Boolean =
        sorted[(sorted.size - 1) / 2] <= TARGET_P50 && sorted[(sorted.size - 1) * 95 / 100] <= TARGET_P95

    /**
     * Renders the sweep back and forth for at least [WARMUP_MS] (`-e warmup <ms>`) before measuring: a phone GPU's
     * clock follows its recent load, and a drag on screen is continuous rendering, so a measurement that starts from an
     * idle GPU mixes clock states (the same frame cost 6 or 11 ms depending on what ran before).
     */
    private suspend fun warmUp(renderer: GpuDevelopRenderer, sweep: List<Recipe>, frame: Pair<Int, Int>?) {
        val ms = InstrumentationRegistry.getArguments().getString("warmup")?.toLong() ?: WARMUP_MS
        val end = System.nanoTime() + ms * NANOS_PER_MS
        var forward = true
        do {
            renderer.benchmark(if (forward) sweep else sweep.reversed(), frame)
            forward = !forward
        } while (System.nanoTime() < end)
    }

    /**
     * `-e cool <s>`: idles that many seconds before a measurement and logs the thermal status, so each slider starts
     * from the same state (a phone's GPU clock falls as it warms: the same pass costs up to 2x more after a minute of
     * continuous rendering).
     */
    private fun coolDown() {
        val seconds = InstrumentationRegistry.getArguments().getString("cool")?.toLong() ?: return
        Thread.sleep(seconds * MS_PER_S)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val power = context.getSystemService(android.os.PowerManager::class.java)
        Log.i(TAG, "thermal status ${power.currentThermalStatus}, headroom %.2f".format(power.getThermalHeadroom(0)))
    }

    /** `-e frame WxH` (the photo frame on screen), else [FRAME]; `-e frame image` draws 1:1 at the proxy's size. */
    private fun frameArgument(): Pair<Int, Int>? {
        val arg = InstrumentationRegistry.getArguments().getString("frame") ?: FRAME
        if (arg == "image") return null
        val (w, h) = arg.split('x').map { it.trim().toInt() }
        return w to h
    }

    /** [drag] with `glFinish` after every section (`-e profile true`): mean ms per pass, `REVEAL_BENCH profile`. */
    private suspend fun profileDrag(
        name: String,
        image: PipelineInput,
        frame: Pair<Int, Int>? = null,
        masks: MaskContext? = null,
        recipeAt: (Float) -> Recipe,
    ) {
        val renderer = GpuDevelopRenderer.create()
        try {
            renderer.setInput(image, PROXY_SCALE)
            masks?.let { renderer.setMaskSource(it.source, it.photoKey) }
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            warmUp(renderer, sweep, frame)
            renderer.profileWithTimers = InstrumentationRegistry.getArguments().getString("timers") != "false"
            renderer.profilePasses = true
            val times = renderer.benchmark(sweep, frame).sorted()
            val gpu = renderer.passProfile()
            val cpu = renderer.passProfileCpu()
            val mode = if (renderer.passProfileOnGpu) "gpu timer" else "glFinish"
            val sections = gpu.entries.joinToString { (k, v) -> "$k %.2f (cpu %.2f)".format(v, cpu[k] ?: 0f) }
            Log.i(
                TAG,
                "profile $name [$mode, ms per occurrence]: $sections; gpu sum %.2f; frame p50 %.2f".format(
                    gpu.values.sum(),
                    times[times.size / 2],
                ),
            )
        } finally {
            renderer.close()
        }
    }

    @Suppress("LongParameterList")
    private suspend fun drag(
        name: String,
        image: PipelineInput,
        pixelScale: Float = 1f,
        frame: Pair<Int, Int>? = null,
        target: Boolean = false,
        masks: MaskContext? = null,
        recipeAt: (Float) -> Recipe,
    ) {
        val renderer = GpuDevelopRenderer.create()
        try {
            val uploadStart = System.nanoTime()
            renderer.setInput(image, pixelScale)
            masks?.let { renderer.setMaskSource(it.source, it.photoKey) }
            renderer.benchmark(listOf(Recipe()), frame)
            val uploadMs = (System.nanoTime() - uploadStart) / 1e6
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            warmUp(renderer, sweep, frame)
            val times = renderer.benchmark(sweep, frame).sorted()
            val piped = renderer.benchmarkPipelined(sweep + sweep.reversed(), frame)
            val gpuTimes = piped.gpuMs.sorted()
            val gpu = "${renderer.info.renderer} (${renderer.info.version})"
            val shown = frame?.let { (w, h) -> " shown in ${w}x$h" } ?: ""
            // The display rate: the sustained frame time and the GPU time of each frame (p50 / p95).
            val met = piped.msPerFrame <= TARGET_P50 && (gpuTimes.isEmpty() || meets(gpuTimes))
            val verdict = if (!target) {
                ""
            } else if (met) {
                " [target met]"
            } else {
                " [target MISSED]"
            }
            val gpuPart = if (gpuTimes.isEmpty()) {
                ""
            } else {
                ", GPU per frame p50 ${p(
                    gpuTimes,
                    50,
                )}, p95 ${p(gpuTimes, 95)}"
            }
            Log.i(
                TAG,
                "$name: $gpu ${image.width}x${image.height}$shown: upload+first frame ${"%.1f".format(uploadMs)} ms; " +
                    "pipelined ${"%.2f".format(piped.msPerFrame)} ms per frame$gpuPart$verdict; " +
                    "latency (glFinish per frame) mean ${"%.2f".format(times.average())} ms, p50 ${p(times, 50)}, " +
                    "p95 ${p(times, 95)}, max ${"%.2f".format(times.last())}",
            )
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_BENCH"
        const val WIDTH = 2400
        const val HEIGHT = 1600
        const val FRAMES = 120
        const val WARMUP_MS = 2000L
        const val NANOS_PER_MS = 1_000_000L
        const val PROXY_SCALE = 2.5f

        /** The editor's photo frame on the owner's phone (S24 Ultra at 1080 x 2340, a 3:2 photo in portrait). */
        const val FRAME = "1080x720"
        const val TARGET_P50 = 8.3f
        const val TARGET_P95 = 12f
        const val MS_PER_S = 1000L
        const val STROKE_POINTS = 1200
        const val APPEND_POINTS = 5
        const val WARMUP_STROKE = 20

        /** A stroke's frame target at p95 (the brief: p50 <= 8.3, p95 <= 16 ms). */
        const val STROKE_P95 = 16f

        val ALL_DETAIL = Detail(nrLuminance = 40f, nrColour = 50f, sharpenAmount = 50f, sharpenMasking = 30f)
        val ALL_EFFECTS = Effects(texture = 30f, clarity = 25f, dehaze = 20f, vignetteAmount = -30f, grainAmount = 25f)

        /** Every Detail and Effects slider: name and the recipe at drag position t in [0, 1]. */
        val SLIDERS: List<Pair<String, (Recipe, Float) -> Recipe>> = listOf(
            "luminance" to { r, t -> r.copy(detail = r.detail.copy(nrLuminance = 100f * t)) },
            "luminance detail" to { r, t -> r.copy(detail = r.detail.copy(nrLuminanceDetail = 100f * t)) },
            "luminance contrast" to { r, t -> r.copy(detail = r.detail.copy(nrLuminanceContrast = 100f * t)) },
            "colour" to { r, t -> r.copy(detail = r.detail.copy(nrColour = 1f + 99f * t)) },
            "colour detail" to { r, t -> r.copy(detail = r.detail.copy(nrColourDetail = 100f * t)) },
            "colour smoothness" to { r, t -> r.copy(detail = r.detail.copy(nrColourSmoothness = 100f * t)) },
            "sharpening amount" to { r, t -> r.copy(detail = r.detail.copy(sharpenAmount = 1f + 149f * t)) },
            "sharpening radius" to { r, t -> r.copy(detail = r.detail.copy(sharpenRadius = 0.5f + 2.5f * t)) },
            "sharpening detail" to { r, t -> r.copy(detail = r.detail.copy(sharpenDetail = 100f * t)) },
            "sharpening masking" to { r, t -> r.copy(detail = r.detail.copy(sharpenMasking = 100f * t)) },
            "texture" to { r, t -> r.copy(effects = r.effects.copy(texture = -100f + 200f * t)) },
            "clarity" to { r, t -> r.copy(effects = r.effects.copy(clarity = -100f + 200f * t)) },
            "dehaze" to { r, t -> r.copy(effects = r.effects.copy(dehaze = -100f + 200f * t)) },
            "vignette" to { r, t -> r.copy(effects = r.effects.copy(vignetteAmount = -100f + 200f * t)) },
            "grain" to { r, t -> r.copy(effects = r.effects.copy(grainAmount = 1f + 99f * t)) },
        )
    }
}
