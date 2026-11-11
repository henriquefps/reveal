package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BaseLook
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
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
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
        val detail = Detail(
            nrLuminance = 40f,
            nrColour = 50f,
            sharpenAmount = 50f,
            sharpenMasking = 30f,
        )
        val effects = Effects(
            texture = 30f,
            clarity = 25f,
            dehaze = 20f,
            vignetteAmount = -30f,
            grainAmount = 25f,
        )
        val base = Recipe(light = Light(0.2f, contrast = 15f), detail = detail, effects = effects)
        val sliders: List<Pair<String, (Recipe, Float) -> Recipe>> = listOf(
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
        val image = noisy()
        val profile = InstrumentationRegistry.getArguments().getString("profile") == "true"
        for ((name, at) in sliders) {
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
     * Dragging the Transform's vertical slider (docs/adr/0015) on a photo with lens corrections and CA removal: the
     * stage is cached, every frame is the output pass through the warp (three Catmull-Rom samples per pixel).
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
        // 1:1 into a 2400 x 1600 target (every pixel three Catmull-Rom samples: the worst case, an export tile).
        drag("transform vertical 1:1 (lens + CA on)", image, PROXY_SCALE, transform)
        // As the editor shows it: the whole photo fitted to a phone's photo area (minified, explicit LOD).
        dragView("transform vertical, fit view (lens + CA on)", image, transform)
        drag("lens distortion amount", image, PROXY_SCALE) {
            Recipe(optics = Optics(lensCorrections = true, distortionPercent = 200f * it))
        }
        drag("defringe purple amount (proxy scale)", image, PROXY_SCALE) {
            Recipe(optics = Optics(purpleAmount = 20f * it))
        }
    }

    /**
     * [drag] as the editor draws it: the output pass into a [VIEW_W] x [VIEW_H] target with the photo fitted (a phone's
     * photo area), stage 1 re-run only when needed, `glFinish` after each frame (no readback).
     */
    private suspend fun dragView(name: String, image: PipelineInput, recipeAt: (Float) -> Recipe) {
        val renderer = GpuDevelopRenderer.create()
        try {
            renderer.setInput(image, PROXY_SCALE)
            val scale = minOf(VIEW_W.toFloat() / image.width, VIEW_H.toFloat() / image.height)
            val view = ViewTransform(scale, image.width / 2f, image.height / 2f)
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            renderer.benchmarkView(sweep.take(WARMUP), view, VIEW_W, VIEW_H)
            val times = renderer.benchmarkView(sweep, view, VIEW_W, VIEW_H).sorted()
            Log.i(
                TAG,
                "$name: ${renderer.info.renderer} ${VIEW_W}x$VIEW_H view of ${image.width}x${image.height}: " +
                    "frame mean ${"%.2f".format(times.average())} ms, p50 ${"%.2f".format(times[times.size / 2])}, " +
                    "p95 ${"%.2f".format(times[(times.size * 95) / 100])}, max ${"%.2f".format(times.last())}",
            )
        } finally {
            renderer.close()
        }
    }

    private fun noisy() = SyntheticImages.noisyCard(WIDTH, HEIGHT, seed = 5)

    /** [drag] with `glFinish` after every section (`-e profile true`): mean ms per pass, `REVEAL_BENCH profile`. */
    private suspend fun profileDrag(name: String, image: PipelineInput, recipeAt: (Float) -> Recipe) {
        val renderer = GpuDevelopRenderer.create()
        try {
            renderer.setInput(image, PROXY_SCALE)
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            renderer.benchmark(sweep.take(WARMUP))
            renderer.profilePasses = true
            renderer.benchmark(sweep)
            val sections = renderer.passProfile().entries.joinToString { (k, v) -> "$k %.2f".format(v) }
            Log.i(TAG, "profile $name: $sections")
        } finally {
            renderer.close()
        }
    }

    private suspend fun drag(name: String, image: PipelineInput, pixelScale: Float = 1f, recipeAt: (Float) -> Recipe) {
        val renderer = GpuDevelopRenderer.create()
        try {
            val uploadStart = System.nanoTime()
            renderer.setInput(image, pixelScale)
            renderer.benchmark(listOf(Recipe()))
            val uploadMs = (System.nanoTime() - uploadStart) / 1e6
            val sweep = List(FRAMES) { recipeAt(it.toFloat() / (FRAMES - 1)) }
            renderer.benchmark(sweep.take(WARMUP))
            val times = renderer.benchmark(sweep).sorted()
            val gpu = "${renderer.info.renderer} (${renderer.info.version})"
            val mean = "%.2f".format(times.average())
            val p50 = "%.2f".format(times[times.size / 2])
            val p95 = "%.2f".format(times[(times.size * 95) / 100])
            val max = "%.2f".format(times.last())
            val first = "%.1f".format(uploadMs)
            Log.i(
                TAG,
                "$name: $gpu ${image.width}x${image.height}: upload+first frame $first ms, " +
                    "frame mean $mean ms, p50 $p50, p95 $p95, max $max",
            )
        } finally {
            renderer.close()
        }
    }

    private companion object {
        const val TAG = "REVEAL_BENCH"
        const val VIEW_W = 1080
        const val VIEW_H = 1500
        const val WIDTH = 2400
        const val HEIGHT = 1600
        const val FRAMES = 120
        const val WARMUP = 10
        const val PROXY_SCALE = 2.5f
    }
}
