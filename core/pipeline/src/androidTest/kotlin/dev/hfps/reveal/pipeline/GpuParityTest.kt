package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
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
    )

    private fun recipes(count: Int): List<Recipe> {
        val random = Random(1234)
        fun slider() = if (random.nextBoolean()) random.nextFloat() * 200f - 100f else 0f
        return listOf(Recipe()) + LIGHT_EXTREMES + COLOUR_EXTREMES + List(count) {
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
                profile = Profile(BaseLook.entries[random.nextInt(BaseLook.entries.size)], random.nextFloat() * 200f),
            )
        }
    }

    @Test
    fun linearStageMatchesCpuReference() = runBlocking {
        var maxAbs = 0f
        var sumAbs = 0.0
        var count = 0L
        var worstRatio = 0f
        val failures = mutableListOf<String>()
        for ((name, image) in images) {
            renderer.setInput(image)
            var imageWorst = 0f
            var where = ""
            for ((r, recipe) in recipes(RANDOM_RECIPES).withIndex()) {
                val cpu = CpuDevelop.renderLinear(image, recipe)
                val gpu = renderer.renderLinear(recipe)
                val light = recipe.light.hasTone() || name in RECONSTRUCTING
                val colour = recipe.hasColour()
                for (p in 0 until image.width * image.height) {
                    val scale = maxOf(abs(cpu[p * 3]), abs(cpu[p * 3 + 1]), abs(cpu[p * 3 + 2]))
                    for (c in 0 until 3) {
                        val error = abs(gpu[p * 3 + c] - cpu[p * 3 + c])
                        val allowed = when {
                            light && colour -> LIGHT_ABS + COLOUR_LIGHT_REL * scale
                            light -> LIGHT_ABS + LIGHT_REL * scale
                            colour -> COLOUR_ABS + COLOUR_REL * scale
                            else -> LINEAR_ABS + LINEAR_REL * scale
                        }
                        val ratio = error / allowed
                        maxAbs = maxOf(maxAbs, error)
                        sumAbs += error
                        count++
                        if (ratio > imageWorst) {
                            imageWorst = ratio
                            where =
                                "recipe #$r ${recipe.light} ${recipe.profile.base}, " +
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
            "linear: max abs error $maxAbs, mean abs error ${sumAbs / count}, worst error / tolerance $worstRatio",
        )
        assertThat(failures).isEmpty()
        assertThat(sumAbs / count).isAtMost(LINEAR_MEAN)
    }

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
                    val allowed = outputAllowed(name, recipe, imageAllowed)
                    var recipeMax = 0
                    val settings = OutputSettings(space = space)
                    val cpu = CpuDevelop.render(image, recipe, settings)
                    val gpu = renderer.renderOutput(recipe, settings)
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
                            where = "recipe #$r ${recipe.light} ${recipe.profile} ${recipe.colour}, " +
                                "pixel ${i / 4 % image.width}, ${i / 4 / image.width}"
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
    private fun outputAllowed(name: String, recipe: Recipe, imageAllowed: Int): Int = when {
        recipe.hasColour() -> maxOf(imageAllowed, OUTPUT_MAX_LEVELS_COLOUR)
        name == "rawNoise" && recipe.light.hasTone() -> OUTPUT_MAX_LEVELS_NOISE_TONE
        else -> imageAllowed
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

    private companion object {
        const val TAG = "REVEAL_PARITY"

        /** The Light sliders at their ends, alone and together (always in the parity set). */
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
            colour.saturation != 0f || colour.whiteBalance.relTemperature != 0f || colour.whiteBalance.relTint != 0f

        fun Light.hasTone() = contrast != 0f || highlights != 0f || shadows != 0f || whites != 0f || blacks != 0f
        const val OUTPUT_MEAN_LEVELS = 0.1
    }
}
