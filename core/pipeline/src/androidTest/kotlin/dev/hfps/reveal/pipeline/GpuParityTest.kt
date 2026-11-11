package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.CurveLut
import dev.hfps.reveal.pipeline.cpu.EffectsParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CropRect
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
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * GPU == CPU: the same synthetic inputs and recipes through the CPU reference and the float16 GPU pipeline.
 * Tolerances are the ones of docs/adr/0002 ("Parity tolerance"); the measured errors are logged under
 * `REVEAL_PARITY` for the PR and the ADR.
 */
@RunWith(AndroidJUnit4::class)
class GpuParityTest {
    private lateinit var renderer: GpuDevelopRenderer

    @Before
    fun setUp() {
        renderer = runBlocking { GpuDevelopRenderer.create() }
        Log.i(TAG, "GL ${renderer.info}")
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    private val images: Map<String, PipelineInput> = linkedMapOf(
        "ramp" to SyntheticImages.grayRamp(256, 4, max = 4f),
        "rawRamp" to SyntheticImages.rawRamp(256, 4, max = 16f),
        "srgbChart" to SyntheticImages.srgb8Chart().first,
        "checker" to SyntheticImages.colorChecker(cell = 6),
        "outOfRange" to SyntheticImages.outOfRange(),
        "scene" to SyntheticImages.scene(160, 108),
        "rawNoise" to SyntheticImages.rawNoise(97, 61, seed = 11),
        // A RAW with a known saturation point: exercises the highlight clip of the raw white balance.
        "rawClipped" to SyntheticImages.rawRamp(256, 4, max = 1f).let {
            PipelineInput(it.kind, it.width, it.height, it.linearRgb, it.cameraToXyz, it.asShotMultipliers, 0.8f)
        },
        // Light group (docs/adr/0007): clipped coloured highlights (reconstruction), a textured step edge (local tone).
        "highlights" to SyntheticImages.clippedHighlights(192, 128),
        "stepEdge" to SyntheticImages.stepEdge(256, 48, dark = 0.02f, bright = 2f, texture = 0.25f),
        // Colour tools (docs/adr/0013): every hue at several lightnesses plus a neutral band.
        "hueSweep" to SyntheticImages.hueSweep(180, 24),
        // Effects (docs/adr/0014): a hazy landscape (dehaze), display-referred.
        "hazy" to SyntheticImages.hazy(160, 96),
    )

    private fun recipes(count: Int): List<Recipe> {
        val random = Random(1234)
        fun slider() = if (random.nextBoolean()) random.nextFloat() * 200f - 100f else 0f
        return listOf(Recipe()) + LIGHT_EXTREMES + COLOUR_EXTREMES + GEOMETRY + DETAIL + EFFECTS + TOOL_EXTREMES +
            toolRecipes(count) +
            List(count) {
                Recipe(
                    light = Light(
                        random.nextFloat() * 6f - 3f,
                        contrast = slider(),
                        highlights = slider(),
                        shadows = slider(),
                        whites = slider(),
                        blacks = slider(),
                    ),
                    colour = Colour(
                        WhiteBalance(
                            temperatureK = if (random.nextBoolean()) 2500f + random.nextFloat() * 9500f else null,
                            tint = if (random.nextBoolean()) random.nextFloat() * 160f - 80f else null,
                            relTemperature = slider(),
                            relTint = slider(),
                        ),
                        vibrance = slider(),
                        saturation = slider(),
                    ),
                    profile = Profile(
                        BaseLook.entries[random.nextInt(BaseLook.entries.size)],
                        random.nextFloat() * 200f,
                    ),
                )
            }
    }

    /** Random colour-tool recipes (own seed, so the other random recipes stay what they were). */
    private fun toolRecipes(count: Int): List<Recipe> {
        val random = Random(4321)
        fun slider() = if (random.nextInt(3) == 0) random.nextFloat() * 200f - 100f else 0f
        fun bands() = BandValues.of(List(8) { slider() })
        fun wheel() =
            GradeWheel(random.nextFloat() * 360f, if (random.nextBoolean()) random.nextFloat() * 100f else 0f, slider())

        // Curves as people draw them: 1-3 interior points, monotone, slopes within about 0.3..3 (the steep and
        // folded cases are pinned in TOOL_EXTREMES; their error grows with the slope, see ADR 0013).
        fun points(): List<CurvePoint> {
            if (random.nextInt(3) != 0) return emptyList()
            val n = 1 + random.nextInt(3)
            val xs = (1..n).map { (it + random.nextFloat() * 0.6f - 0.3f) / (n + 1) }
            val ys = xs.map { (it + random.nextFloat() * 0.2f - 0.1f).coerceIn(0.02f, 0.98f) }.sorted()
            return listOf(CurvePoint(0f, random.nextFloat() * 0.05f)) + xs.zip(ys).map { (x, y) -> CurvePoint(x, y) } +
                CurvePoint(1f, 0.95f + random.nextFloat() * 0.05f)
        }
        return List(count) {
            Recipe(
                profile = Profile(
                    BaseLook.entries[random.nextInt(BaseLook.entries.size)],
                    treatment = if (random.nextInt(4) == 0) Treatment.BLACK_WHITE else Treatment.COLOUR,
                ),
                light = Light(
                    random.nextFloat() * 2f - 1f,
                    curve = Curves(
                        ParametricCurve(
                            slider(),
                            slider(),
                            slider(),
                            slider(),
                            15f + random.nextFloat() * 20f,
                            50f,
                            70f + random.nextFloat() * 20f,
                        ),
                        rgb = points(),
                        red = points(),
                        green = points(),
                        blue = points(),
                    ),
                ),
                colour = Colour(
                    vibrance = slider(),
                    mixer = ColourMixer(bands(), bands(), bands()),
                    grading = ColourGrading(wheel(), wheel(), wheel(), wheel(), random.nextFloat() * 100f, slider()),
                    greyMixer = bands(),
                ),
            )
        }
    }

    @Test
    fun colourToolsAtNeutralAreIdentity() = runBlocking {
        // Moved splits, blending, balance and grading hues alone change nothing: the passes are skipped exactly.
        val neutral = Recipe(
            light = Light(curve = Curves(parametric = ParametricCurve(splitShadows = 35f), rgb = Curves.LINEAR)),
            colour = Colour(grading = ColourGrading(shadows = GradeWheel(hue = 120f), blending = 90f, balance = 30f)),
        )
        for (name in listOf("scene", "hueSweep")) {
            renderer.setInput(images.getValue(name))
            assertThat(renderer.renderLinear(neutral)).isEqualTo(renderer.renderLinear(Recipe()))
        }
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    @Test
    fun linearStageMatchesCpuReference() = runBlocking {
        var maxAbs = 0f
        var sumAbs = 0.0
        var count = 0L
        var dehazeSum = 0.0
        var dehazeCount = 0L
        var worstRatio = 0f
        val failures = mutableListOf<String>()
        for ((name, image) in images) {
            renderer.setInput(image)
            var imageWorst = 0f
            var where = ""
            for ((r, recipe) in recipes(RANDOM_RECIPES).withIndex()) {
                // The hazy landscape is there for Dehaze: only the Detail and Effects recipes run on it.
                if (name == "hazy" && !recipe.hasDetail()) continue
                val cpu = CpuDevelop.renderLinear(image, recipe)
                val gpu = renderer.renderLinear(recipe)
                val light = recipe.light.hasTone() || name in RECONSTRUCTING
                val colour = recipe.hasColour()
                val detail = recipe.hasDetail()
                // Saturated noise with near-zero luminance amplifies the extra OkLab cube roots of the colour tools.
                val slope = recipe.curveSlope() * if (name == "rawNoise" && recipe.hasTools()) NOISE_TOOLS else 1f
                for (p in 0 until image.width * image.height) {
                    val scale = maxOf(abs(cpu[p * 3]), abs(cpu[p * 3 + 1]), abs(cpu[p * 3 + 2]))
                    for (c in 0 until 3) {
                        val error = abs(gpu[p * 3 + c] - cpu[p * 3 + c])
                        val allowed = slope * when {
                            detail -> (DETAIL_ABS + DETAIL_REL * scale) * recipe.dehazeGain()
                            light && colour -> LIGHT_ABS + COLOUR_LIGHT_REL * scale
                            light -> LIGHT_ABS + LIGHT_REL * scale
                            colour -> COLOUR_ABS + COLOUR_REL * scale
                            else -> LINEAR_ABS + LINEAR_REL * scale
                        }
                        val ratio = error / allowed
                        maxAbs = maxOf(maxAbs, error)
                        // Detail and Effects (float16 cascades; positive dehaze x4) have their own mean bound.
                        if (!detail) {
                            sumAbs += error
                            count++
                        } else {
                            dehazeSum += error
                            dehazeCount++
                        }
                        if (ratio > imageWorst) {
                            imageWorst = ratio
                            where =
                                "recipe #$r ${recipe.light} ${recipe.profile.base} ${recipe.effects}, " +
                                "pixel ($p % ${image.width}, ${p / image.width}) channel $c: " +
                                "cpu ${cpu[p * 3 + c]} gpu ${gpu[p * 3 + c]}"
                        }
                    }
                }
            }
            worstRatio = maxOf(worstRatio, imageWorst)
            Log.i(TAG, "linear $name: worst error / tolerance $imageWorst at $where")
            if (imageWorst > 1f) failures += name
        }
        Log.i(
            TAG,
            "linear: max abs error $maxAbs, mean abs error ${sumAbs / count} (Detail and Effects recipes " +
                "${dehazeSum / dehazeCount}), worst error / tolerance $worstRatio",
        )
        assertThat(failures).isEmpty()
        assertThat(sumAbs / count).isAtMost(LINEAR_MEAN)
        assertThat(dehazeSum / dehazeCount).isAtMost(DETAIL_MEAN)
    }

    @Suppress("CyclomaticComplexMethod")
    @Test
    fun outputMatchesCpuReferenceIn8Bit() = runBlocking {
        val failures = mutableListOf<String>()
        for (space in OutputSpace.entries) {
            var maxDiff = 0
            var sum = 0L
            var count = 0L
            var overOne = 0L
            for ((name, image) in images) {
                var imageMax = 0
                var where = ""
                val imageAllowed = when (name) {
                    "rawNoise" -> OUTPUT_MAX_LEVELS_NOISE
                    in RECONSTRUCTING -> OUTPUT_MAX_LEVELS_RECONSTRUCTION
                    else -> OUTPUT_MAX_LEVELS
                }
                renderer.setInput(image)
                for ((r, recipe) in recipes(RANDOM_RECIPES).withIndex()) {
                    // The hazy landscape is there for Dehaze: only the Detail and Effects recipes run on it.
                    if (name == "hazy" && !recipe.hasDetail()) continue
                    val allowed = outputAllowed(name, recipe, imageAllowed)
                    var recipeMax = 0
                    val settings = OutputSettings(space = space)
                    val cpu = CpuDevelop.render(image, recipe, settings)
                    val gpu = renderer.renderOutput(recipe, settings)
                    // Geometry: both sides render the operator's output size.
                    assertThat(gpu.size).isEqualTo(cpu.size)
                    val outWidth = CpuDevelop.mapping(image, recipe).outputWidth
                    for (i in cpu.indices) {
                        if (i % 4 == 3) {
                            assertThat(gpu[i]).isEqualTo(cpu[i])
                            continue
                        }
                        val diff = abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff))
                        maxDiff = maxOf(maxDiff, diff)
                        sum += diff
                        count++
                        if (diff > 1) overOne++
                        recipeMax = maxOf(recipeMax, diff)
                        if (diff > imageMax) {
                            imageMax = diff
                            where = "recipe #$r ${recipe.light} ${recipe.profile} ${recipe.colour} " +
                                "${recipe.geometry}, pixel ${i / 4 % outWidth}, ${i / 4 / outWidth}"
                        }
                    }
                    if (recipeMax > allowed) failures += "$space $name recipe #$r"
                }
                Log.i(TAG, "output $space $name: max diff $imageMax levels at $where")
            }
            Log.i(
                TAG,
                "output $space: max diff $maxDiff levels, mean diff ${sum.toDouble() / count}, " +
                    "off by >1: $overOne of $count",
            )
            assertThat(sum.toDouble() / count).isAtMost(OUTPUT_MEAN_LEVELS)
        }
        assertThat(failures).isEmpty()
    }

    /** The 8-bit limit for [recipe] on image [name]: the image's own, raised for colour and noise+tone cases. */
    private fun outputAllowed(name: String, recipe: Recipe, imageAllowed: Int): Int {
        val base = when {
            name == "rawNoise" && recipe.hasDetail() -> OUTPUT_MAX_LEVELS_NOISE_DETAIL
            recipe.hasDetail() -> maxOf(imageAllowed, OUTPUT_MAX_LEVELS_DETAIL)
            recipe.hasColour() -> maxOf(imageAllowed, OUTPUT_MAX_LEVELS_COLOUR)
            name in SATURATED && recipe.light.hasTone() -> OUTPUT_MAX_LEVELS_NOISE_TONE
            else -> imageAllowed
        }
        // A steep tone curve multiplies the fp16 rounding before it (docs/adr/0013, "Parity").
        return kotlin.math.ceil(base * recipe.curveSlope() * recipe.dehazeGain()).toInt()
    }

    @Test
    fun geometryMatchesCpuReferenceIn8Bit() = runBlocking {
        // Slot 13 alone: same size, exact copies without straightening, Catmull-Rom within the 8-bit tolerance.
        var worst = 0
        var sum = 0L
        var count = 0L
        for (name in listOf("checker", "scene", "srgbChart", "stepEdge")) {
            val image = images.getValue(name)
            renderer.setInput(image)
            for (recipe in GEOMETRY) {
                val settings = OutputSettings(dither = false)
                val cpu = CpuDevelop.render(image, recipe, settings)
                val gpu = renderer.renderOutput(recipe, settings)
                assertThat(gpu.size).isEqualTo(cpu.size)
                var max = 0
                for (i in cpu.indices) {
                    val diff = abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff))
                    max = maxOf(max, diff)
                    sum += diff
                    count++
                }
                Log.i(TAG, "geometry $name ${recipe.geometry}: max diff $max levels")
                worst = maxOf(worst, max)
                if (recipe.geometry.angle == 0f) assertThat(max).isAtMost(1)
            }
        }
        Log.i(TAG, "geometry: max diff $worst levels, mean diff ${sum.toDouble() / count}")
        assertThat(worst).isAtMost(OUTPUT_MAX_LEVELS)
        assertThat(sum.toDouble() / count).isAtMost(OUTPUT_MEAN_LEVELS)
    }

    /**
     * Optics and Transform (docs/adr/0015): vignetting (slot 3), defringe (slot 10b), lens distortion with CA removal
     * and the Transform folded into slot 13, at 1:1 and at a proxy scale (defringe's radius scales). A warp resamples
     * every pixel with Catmull-Rom, so the geometry tolerance applies; defringe's float16 OkLab keeps it within the
     * Detail tolerance.
     */
    @Test
    fun opticsMatchCpuReference() = runBlocking {
        val failures = mutableListOf<String>()
        var worst = 0
        var sum = 0L
        var count = 0L
        for (name in listOf("scene", "checker", "stepEdge", "hueSweep")) {
            val image = images.getValue(name).withLens(PARITY_LENS)
            for (scale in listOf(1f, 2.5f)) {
                renderer.upload(image, scale)
                for (recipe in OPTICS) {
                    val settings = OutputSettings(dither = false)
                    val cpu = CpuDevelop.render(image, recipe, settings, scale)
                    val gpu = renderer.renderOutput(recipe, settings)
                    assertThat(gpu.size).isEqualTo(cpu.size)
                    var max = 0
                    for (i in cpu.indices) {
                        val diff = abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff))
                        max = maxOf(max, diff)
                        sum += diff
                        count++
                    }
                    Log.i(
                        TAG,
                        "optics $name x$scale ${recipe.optics} ${recipe.geometry.transform}: max diff $max levels",
                    )
                    worst = maxOf(worst, max)
                    val fringe = recipe.optics.purpleAmount > 0f || recipe.optics.greenAmount > 0f
                    if (max >
                        (if (fringe) OUTPUT_MAX_LEVELS_DETAIL else OUTPUT_MAX_LEVELS)
                    ) {
                        failures +=
                            "$name x$scale #${OPTICS.indexOf(recipe)}: $max"
                    }
                }
            }
        }
        Log.i(TAG, "optics: max diff $worst levels, mean diff ${sum.toDouble() / count}")
        assertThat(failures).isEmpty()
        assertThat(sum.toDouble() / count).isAtMost(OUTPUT_MEAN_LEVELS)
    }

    /**
     * Detail (slot 11, docs/adr/0012) on a proxy: the operator's lengths scaled by the proxy's pixel scale (wavelet
     * levels skipped or re-stepped, the blur radius divided) give the same result on both sides.
     */
    @Test
    fun detailAtAProxyScaleMatchesCpuReference() = runBlocking {
        var worst = 0
        var sum = 0L
        var count = 0L
        for (name in listOf("scene", "checker", "stepEdge", "rawNoise")) {
            val image = images.getValue(name)
            for (scale in listOf(2.5f, 4f)) {
                renderer.upload(image, scale)
                for (recipe in DETAIL + EFFECTS) {
                    val settings = OutputSettings(dither = false)
                    val cpu = CpuDevelop.render(image, recipe, settings, scale)
                    val gpu = renderer.renderOutput(recipe, settings)
                    var max = 0
                    for (i in cpu.indices) {
                        val diff = abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff))
                        max = maxOf(max, diff)
                        sum += diff
                        count++
                    }
                    worst = maxOf(worst, max)
                    val allowed = (
                        if (name == "rawNoise") OUTPUT_MAX_LEVELS_NOISE_DETAIL else OUTPUT_MAX_LEVELS_DETAIL
                        ) * recipe.dehazeGain().toInt()
                    assertThat(max).isAtMost(allowed)
                }
            }
        }
        Log.i(TAG, "detail and effects at proxy scales: max diff $worst levels, mean diff ${sum.toDouble() / count}")
        assertThat(sum.toDouble() / count).isAtMost(OUTPUT_MEAN_LEVELS)
    }

    @Test
    fun effectsNeutralIsIdentity() = runBlocking {
        val image = images.getValue("scene")
        renderer.setInput(image)
        val base = Recipe(light = Light(0.3f))
        // The shaping sliders change nothing while every amount is 0 (slot 12 and the post-crop finish are skipped).
        val shaped = base.copy(
            effects = Effects(vignetteMidpoint = 10f, vignetteRoundness = -60f, vignetteFeather = 90f, grainSize = 80f),
        )
        assertThat(renderer.renderLinear(shaped)).isEqualTo(renderer.renderLinear(base))
        assertThat(renderer.renderOutput(shaped)).isEqualTo(renderer.renderOutput(base))
        val edit = base.copy(effects = Effects(clarity = 50f, dehaze = 30f, grainAmount = 40f, vignetteAmount = -30f))
        assertThat(renderer.renderOutput(edit)).isEqualTo(renderer.renderOutput(edit))
    }

    @Test
    fun detailNeutralIsIdentityAndMaskIsDeterministic() = runBlocking {
        val image = images.getValue("scene")
        renderer.setInput(image)
        val base = Recipe(light = Light(0.3f))
        // Detail sliders other than the amounts change nothing while every amount is 0 (the passes are skipped).
        val shaped = base.copy(detail = Detail(sharpenRadius = 2.5f, sharpenDetail = 90f, nrLuminanceDetail = 10f))
        assertThat(renderer.renderLinear(shaped)).isEqualTo(renderer.renderLinear(base))
        val edit = base.copy(detail = Detail(sharpenAmount = 80f, nrLuminance = 40f, nrColour = 50f))
        assertThat(renderer.renderOutput(edit)).isEqualTo(renderer.renderOutput(edit))
    }

    @Test
    fun displayReferredImageRoundTripsOnTheGpu(): Unit = runBlocking {
        val (input, source) = SyntheticImages.srgb8Chart()
        renderer.setInput(input)

        val out = renderer.renderOutput(Recipe())

        var maxDiff = 0
        for (i in source.indices) {
            maxDiff =
                maxOf(maxDiff, abs((out[i].toInt() and 0xff) - (source[i].toInt() and 0xff)))
        }
        Log.i(TAG, "sRGB 8-bit round trip on the GPU: max diff $maxDiff levels")
        assertThat(maxDiff).isAtMost(1)
    }

    @Test
    fun hardEdgedPatchesMatchCpuWithinOneLevelAtOneToOne(): Unit = runBlocking {
        // Regression for derivative-selected mips on Adreno: a patch edge must never pick up its neighbour.
        for (name in listOf("srgbChart", "checker")) {
            val input = images.getValue(name)
            renderer.setInput(input)
            val settings = OutputSettings(dither = false)
            val gpu = renderer.renderOutput(Recipe(), settings)
            val cpu = CpuDevelop.render(input, Recipe(), settings)
            var maxDiff = 0
            for (i in cpu.indices) {
                maxDiff = maxOf(maxDiff, abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff)))
            }
            Log.i(TAG, "hard edges $name: max diff $maxDiff levels")
            assertThat(maxDiff).isAtMost(1)
        }
    }

    @Test
    fun gpuIsDeterministic() = runBlocking {
        val image = images.getValue("scene")
        renderer.setInput(image)
        val recipe = Recipe(light = Light(0.7f), colour = Colour(WhiteBalance(5200f, 8f)))

        val a = renderer.renderOutput(recipe)
        val b = renderer.renderOutput(recipe)

        assertThat(a).isEqualTo(b)
    }

    @Test
    fun lightOperatorsAreDeterministicAndNeutralIsIdentity() = runBlocking {
        val image = images.getValue("highlights")
        renderer.setInput(image)
        val recipe = Recipe(light = Light(-1f, contrast = 40f, highlights = -100f, shadows = 100f, whites = 30f))

        assertThat(renderer.renderOutput(recipe)).isEqualTo(renderer.renderOutput(recipe))
        // Zero tone sliders render exactly what exposure alone renders (the tone passes are skipped).
        val exposureOnly = Recipe(light = Light(-1f))
        val zeros = Recipe(light = Light(-1f, contrast = 0f, highlights = 0f, shadows = 0f, whites = 0f, blacks = 0f))
        assertThat(renderer.renderLinear(zeros)).isEqualTo(renderer.renderLinear(exposureOnly))
    }

    internal companion object {
        const val TAG = "REVEAL_PARITY"

        /** The Light sliders at their ends, alone and together (always in the parity set). */

        // Geometry (slot 13): pixel-exact crops, quarter turns and flips, Catmull-Rom straightening (constrained and
        // with black empty corners), a tiny angle; with an exposure so the stage is not the identity.

        /** The public ZV-E10 sample's embedded lens data (E PZ 16-50mm at 29 mm), with stronger CA for the test. */
        val PARITY_LENS = checkNotNull(
            SonyLensProfile.of(
                "E PZ 16-50mm",
                listOf(11, 21, 2, -22, -49, -84, -124, -168, -208, -247, -279, -305),
                listOf(11, 0, 0, 128, 352, 736, 1184, 1920, 3200, 4768, 6528, 8384),
                listOf(
                    22, -2048, -2048, -1536, -1536, -1024, -1024, -1024, -512, 0, 512, 1024,
                    1024, 3072, 4096, 5120, 5632, 5632, 5632, 5120, 4096, 3072, 2048,
                ),
            ),
        )

        val OPTICS: List<Recipe> = listOf(
            Recipe(optics = Optics(lensCorrections = true)),
            Recipe(
                optics = Optics(
                    lensCorrections = true,
                    removeCa = true,
                    distortionPercent = 200f,
                    vignettingPercent = 150f,
                ),
            ),
            Recipe(optics = Optics(purpleAmount = 15f, greenAmount = 10f), light = Light(exposureEv = 0.3f)),
            Recipe(
                optics = Optics(lensCorrections = true, removeCa = true, purpleAmount = 8f),
                detail = Detail(sharpenAmount = 40f),
            ),
            Recipe(
                optics = Optics(lensCorrections = true),
                geometry = Geometry(
                    angle = 3.5f,
                    rotate = 1,
                    transform = Transform(vertical = 30f, horizontal = -15f, rotate = 2f, aspect = 12f, scale = 115f),
                ),
            ),
            Recipe(
                geometry = Geometry(
                    crop = CropRect(0.1f, 0.1f, 0.85f, 0.9f),
                    constrain = false,
                    transform = Transform(vertical = -40f, scale = 70f, offsetX = 20f),
                ),
            ),
        )

        val GEOMETRY: List<Recipe> = listOf(
            Geometry(crop = CropRect(0.1f, 0.2f, 0.8f, 0.9f)),
            Geometry(crop = CropRect(0.05f, 0f, 0.6f, 0.75f), rotate = 1, flipH = true),
            Geometry(rotate = 2, flipV = true),
            Geometry(rotate = 3),
            Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f), angle = 7.3f),
            Geometry(angle = -31.7f, rotate = 1, constrain = false),
            Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f), angle = 0.1f, flipH = true),
        ).map { Recipe(light = Light(0.4f), geometry = it) }

        // Detail (slot 11, docs/adr/0012): each part at its end, the shaping sliders, everything together.
        val DETAIL: List<Recipe> = listOf(
            Detail(nrLuminance = 100f),
            Detail(nrLuminance = 60f, nrLuminanceDetail = 100f, nrLuminanceContrast = 100f),
            Detail(nrColour = 100f),
            Detail(nrColour = 70f, nrColourDetail = 0f, nrColourSmoothness = 100f),
            Detail(sharpenAmount = 150f),
            Detail(sharpenAmount = 120f, sharpenRadius = 3f, sharpenDetail = 0f, sharpenMasking = 60f),
            Detail(sharpenAmount = 60f, sharpenRadius = 0.5f, sharpenDetail = 100f),
            Detail(
                sharpenAmount = 70f,
                sharpenMasking = 30f,
                nrLuminance = 45f,
                nrLuminanceContrast = 20f,
                nrColour = 50f,
            ),
        ).map { Recipe(light = Light(0.2f, contrast = 10f), detail = it) }

        // Effects (docs/adr/0014): each local effect at both ends, with Detail, and the post-crop finish on a crop.
        val EFFECTS: List<Recipe> = listOf(
            Recipe(effects = Effects(texture = 100f)),
            Recipe(effects = Effects(texture = -100f), detail = Detail(nrLuminance = 30f)),
            Recipe(effects = Effects(clarity = 100f)),
            Recipe(effects = Effects(clarity = -100f, texture = 40f)),
            Recipe(effects = Effects(dehaze = 100f)),
            Recipe(effects = Effects(dehaze = -100f, clarity = 30f)),
            Recipe(
                effects = Effects(texture = 50f, clarity = 40f, dehaze = 40f),
                detail = Detail(sharpenAmount = 60f, sharpenMasking = 20f, nrColour = 40f),
            ),
            Recipe(
                geometry = Geometry(crop = CropRect(0.1f, 0.05f, 0.9f, 0.95f)),
                effects = Effects(vignetteAmount = -80f, vignetteRoundness = 40f, vignetteHighlights = 50f),
            ),
            Recipe(effects = Effects(vignetteAmount = 70f, vignetteRoundness = -90f, vignetteFeather = 15f)),
            Recipe(effects = Effects(grainAmount = 100f, grainSize = 10f, grainRoughness = 100f)),
            Recipe(
                geometry = Geometry(crop = CropRect(0.2f, 0.2f, 0.8f, 0.8f), angle = 7.3f),
                effects = Effects(grainAmount = 60f, grainSize = 70f, vignetteAmount = -50f, clarity = 30f),
            ),
        ).map { it.copy(light = Light(0.2f, contrast = 10f)) }

        val LIGHT_EXTREMES: List<Recipe> = listOf(
            Light(highlights = -100f),
            Light(highlights = 100f),
            Light(shadows = 100f),
            Light(shadows = -100f),
            Light(contrast = 100f),
            Light(contrast = -100f),
            Light(whites = 100f, blacks = -100f),
            Light(whites = -100f, blacks = 100f),
            Light(-1.5f, contrast = 50f, highlights = -100f, shadows = 100f, whites = 40f, blacks = -30f),
        ).map { Recipe(light = it) } +
            // Regression: main's random recipe #13 before ADR 0008 (failed at 1.06x on rawNoise; see ADR 0007).
            Recipe(
                light = Light(2.9933739f, contrast = -21.92221f, whites = 74.15491f, blacks = 8.119026f),
                colour = Colour(WhiteBalance(5320.001f, 74.344864f)),
            )

        // Colour group (docs/adr/0008): every look at 100 % and 200 %, vibrance/saturation extremes, relative WB.
        val COLOUR_EXTREMES: List<Recipe> = BaseLook.entries.flatMap { look ->
            listOf(100f, 200f).map { Recipe(profile = Profile(look, it)) }
        } + listOf(
            Colour(saturation = 100f),
            Colour(saturation = -100f),
            Colour(vibrance = 100f),
            Colour(vibrance = -100f),
            Colour(vibrance = 100f, saturation = 100f),
            Colour(WhiteBalance(relTemperature = 100f, relTint = -100f), saturation = 40f),
        ).map { Recipe(colour = it) } +
            Recipe(profile = Profile(BaseLook.CAMERA_MATCH), colour = Colour(vibrance = 60f, saturation = -30f))
        const val RANDOM_RECIPES = 12

        // Linear stage: |gpu - cpu| <= LINEAR_ABS + LINEAR_REL * (largest |channel| of that pixel); docs/adr/0002.
        const val LINEAR_ABS = 1e-6f
        const val LINEAR_REL = 3e-3f

        // Mean absolute error over every image and recipe. 3e-4 in M0; 4e-4 since the Standard look of ADR 0008
        // renders scene-referred input about 1.3 EV brighter (absolute errors grow with the values; measured 3.3e-4).
        const val LINEAR_MEAN = 4e-4

        /** Mean abs error of the linear stage over the Detail and Effects recipes (docs/adr/0014). */
        const val DETAIL_MEAN = 1e-3

        // 8-bit output: dithering turns sub-level float differences into at most one level of change.
        // The random-noise image is a stress test: saturated pixels with tiny channels in the darks, where one
        // float16 input rounding is worth up to ~3 levels of the steep sRGB toe.
        const val OUTPUT_MAX_LEVELS = 2
        const val OUTPUT_MAX_LEVELS_NOISE = 4

        // Light group (docs/adr/0007): recipes with a tone slider, and images where highlights are reconstructed.
        // Reconstruction reads the fp16 source near the clip, where the 90-99 % clip ramp multiplies a source
        // rounding by 11; the tone curves multiply relative errors by their slope and, for gains computed from a
        // tiny luminance, by much more (saturated noise with near-zero luminance). Measured on the emulator: see
        // the ADR table; the 8-bit output stays within 3 levels.
        const val LIGHT_ABS = 2e-3f
        const val LIGHT_REL = 2e-2f
        const val OUTPUT_MAX_LEVELS_RECONSTRUCTION = 3

        // Tone sliders on the saturated random-noise stress image (docs/adr/0007, "Parity on SwiftShader"): the
        // whites/blacks gain is computed from a near-zero luminance, so a driver with a less exact pow (SwiftShader)
        // reaches 6 levels on a few pixels; the host GPU stays within 4.
        const val OUTPUT_MAX_LEVELS_NOISE_TONE = 6
        val RECONSTRUCTING = setOf("rawClipped", "highlights")

        // Images of saturated colours with little luminance (the noise stress image, the hue sweep's out-of-sRGB
        // colours): tone recipes pushing them far over white (Light regression recipe #10: +3 EV, whites +74) reach
        // 5 levels on Adreno 750 / NVIDIA translator for a few pixels, as ADR 0007 measured for the noise image.
        val SATURATED = setOf("rawNoise", "hueSweep")

        // Colour tools on the noise stress image (docs/adr/0013): measured 1.13x the colour tolerance on Adreno 750.
        const val NOISE_TOOLS = 1.5f

        // Colour group (docs/adr/0008): recipes with a look other than Standard, vibrance/saturation or relative
        // white balance. The scene texture between the two develop stages is fp16 (ADR 0007); a look's matrix
        // (Camera JPEG match: coefficients up to about ±6 at 200 %) and the OkLab cube roots amplify that one
        // rounding. Measured on the emulator: worst 2.1x the base linear tolerance (saturated noise), 1.7x Light's
        // where reconstruction runs too; 8-bit output at most 6 levels, 381 of 8.3 M values off by more than 1, mean
        // unchanged (0.05).
        const val COLOUR_ABS = 1e-5f
        const val COLOUR_REL = 8e-3f
        const val COLOUR_LIGHT_REL = 4e-2f
        const val OUTPUT_MAX_LEVELS_COLOUR = 6

        fun Recipe.hasColour() = profile.base != BaseLook.STANDARD || colour.vibrance != 0f ||
            colour.saturation != 0f || colour.whiteBalance.relTemperature != 0f || colour.whiteBalance.relTint != 0f ||
            hasTools()

        // Colour tools (docs/adr/0013) take the colour tolerance: they add OkLab round trips (cube roots, atan) and the
        // curve's sRGB transfer to the same fp16 scene texture rounding.
        fun Recipe.hasTools() = !light.curve.isNeutral || !colour.mixer.isNeutral || !colour.grading.isNeutral ||
            profile.treatment == Treatment.BLACK_WHITE

        // The tools at their ends: point-curve presets, a steep channel curve, the parametric extremes, every mixer
        // property at ±100 on alternating bands, B&W with the grey mixer at its ends, every grading wheel.
        val TOOL_EXTREMES: List<Recipe> = listOf(
            Recipe(light = Light(curve = Curves(rgb = Curves.STRONG_CONTRAST))),
            Recipe(
                light = Light(curve = Curves(parametric = ParametricCurve(100f, -100f, 100f, -100f, 10f, 30f, 90f))),
            ),
            Recipe(
                light = Light(
                    curve = Curves(
                        red = listOf(
                            CurvePoint(0f, 0.1f),
                            CurvePoint(0.45f, 0.2f),
                            CurvePoint(0.55f, 0.9f),
                            CurvePoint(1f, 1f),
                        ),
                        blue = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.3f), CurvePoint(1f, 0.85f)),
                    ),
                ),
            ),
            Recipe(
                colour = Colour(
                    mixer = ColourMixer(
                        hue = BandValues.of(
                            List(8) {
                                if (it % 2 ==
                                    0
                                ) {
                                    100f
                                } else {
                                    -100f
                                }
                            },
                        ),
                    ),
                ),
            ),
            Recipe(
                colour = Colour(
                    mixer = ColourMixer(
                        saturation = BandValues.of(
                            List(8) {
                                if (it % 2 ==
                                    0
                                ) {
                                    -100f
                                } else {
                                    100f
                                }
                            },
                        ),
                    ),
                ),
            ),
            Recipe(
                colour = Colour(
                    mixer = ColourMixer(
                        luminance = BandValues.of(
                            List(8) {
                                if (it % 2 ==
                                    0
                                ) {
                                    100f
                                } else {
                                    -100f
                                }
                            },
                        ),
                    ),
                ),
            ),
            Recipe(profile = Profile(treatment = Treatment.BLACK_WHITE)),
            Recipe(
                profile = Profile(treatment = Treatment.BLACK_WHITE),
                colour = Colour(greyMixer = BandValues.of(List(8) { if (it % 2 == 0) 100f else -100f })),
            ),
            Recipe(
                colour = Colour(
                    grading = ColourGrading(
                        GradeWheel(220f, 100f, -100f),
                        GradeWheel(40f, 100f, 100f),
                        GradeWheel(60f, 100f, -100f),
                        GradeWheel(300f, 50f, 100f),
                        blending = 0f,
                        balance = 100f,
                    ),
                ),
            ),
            Recipe(
                profile = Profile(BaseLook.CAMERA_MATCH, treatment = Treatment.BLACK_WHITE),
                light = Light(curve = Curves(rgb = Curves.MEDIUM_CONTRAST)),
                colour = Colour(
                    greyMixer = BandValues(red = 40f, blue = -60f),
                    grading = ColourGrading(shadows = GradeWheel(230f, 30f), highlights = GradeWheel(50f, 25f)),
                ),
            ),
        )

        /**
         * The tone curve's steepest slope (at least 1): an fp16 rounding of the scene texture before the curve comes
         * out multiplied by it, so a steep user curve (a point pulled hard against its neighbour) widens the linear
         * tolerance by the same factor (docs/adr/0013, "Parity").
         */
        fun Recipe.curveSlope(): Float {
            val table = CurveLut.of(light.curve)?.table ?: return 1f
            var steepest = 1f
            for (i in 0 until CurveLut.SIZE - 1) {
                for (c in 0 until 3) {
                    steepest = maxOf(steepest, (table[(i + 1) * 3 + c] - table[i * 3 + c]) * (CurveLut.SIZE - 1))
                }
            }
            return steepest
        }

        fun Recipe.hasDetail() = detail != Detail() || effects != Effects()

        /**
         * Positive dehaze divides by the transmission (at least `DEHAZE_T0`): it multiplies the fp16 storage
         * differences of its input by up to `1 / t0` = 4, so its tolerance is Detail's times that (docs/adr/0014).
         */
        fun Recipe.dehazeGain(): Float = if (effects.dehaze > 0f) 1f / EffectsParams.DEHAZE_T0 else 1f

        // Detail (docs/adr/0012): both sides store the same float16 values (Half / q16), so what remains is fp32
        // arithmetic (exp, cube roots) amplified by sharpening (gain up to 1.8); the random-noise stress image gets
        // the noise + tone allowance. Measured on the emulator, see the ADR.
        const val DETAIL_ABS = 4e-3f
        const val DETAIL_REL = 3e-2f
        const val OUTPUT_MAX_LEVELS_DETAIL = 4

        // Detail on the random-noise stress image: luminance NR 100 on uniform per-channel noise reaches 7 levels on
        // the Adreno 750 (a few pixels whose edge-stop weights sit at the exp's steepest point); 5 on the host GPU.
        const val OUTPUT_MAX_LEVELS_NOISE_DETAIL = 8

        fun Light.hasTone() = contrast != 0f || highlights != 0f || shadows != 0f || whites != 0f || blacks != 0f
        const val OUTPUT_MEAN_LEVELS = 0.1
    }
}
