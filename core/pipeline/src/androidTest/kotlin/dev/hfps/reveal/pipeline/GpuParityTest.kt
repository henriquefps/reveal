package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
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
    )

    private fun recipes(count: Int): List<Recipe> {
        val random = Random(1234)
        return listOf(Recipe()) + List(count) {
            Recipe(
                light = Light(random.nextFloat() * 6f - 3f),
                colour = Colour(
                    WhiteBalance(
                        temperatureK = if (random.nextBoolean()) 2500f + random.nextFloat() * 9500f else null,
                        tint = if (random.nextBoolean()) random.nextFloat() * 160f - 80f else null,
                    ),
                ),
            )
        }
    }

    @Test
    fun linearStageMatchesCpuReference() = runBlocking {
        var maxAbs = 0f
        var sumAbs = 0.0
        var count = 0L
        var worstRatio = 0f
        for ((name, image) in images) {
            renderer.setInput(image)
            for (recipe in recipes(RANDOM_RECIPES)) {
                val cpu = CpuDevelop.renderLinear(image, recipe)
                val gpu = renderer.renderLinear(recipe)
                for (p in 0 until image.width * image.height) {
                    val scale = maxOf(abs(cpu[p * 3]), abs(cpu[p * 3 + 1]), abs(cpu[p * 3 + 2]))
                    for (c in 0 until 3) {
                        val error = abs(gpu[p * 3 + c] - cpu[p * 3 + c])
                        val allowed = LINEAR_ABS + LINEAR_REL * scale
                        maxAbs = maxOf(maxAbs, error)
                        sumAbs += error
                        count++
                        worstRatio = maxOf(worstRatio, error / allowed)
                        assertThat(error).isAtMost(allowed)
                    }
                }
            }
            Log.i(TAG, "linear $name ok")
        }
        Log.i(
            TAG,
            "linear: max abs error $maxAbs, mean abs error ${sumAbs / count}, worst error / tolerance $worstRatio",
        )
        assertThat(sumAbs / count).isAtMost(LINEAR_MEAN)
    }

    @Test
    fun outputMatchesCpuReferenceIn8Bit() = runBlocking {
        for (space in OutputSpace.entries) {
            var maxDiff = 0
            var sum = 0L
            var count = 0L
            var overOne = 0L
            for ((name, image) in images) {
                var imageMax = 0
                val allowed = if (name == "rawNoise") OUTPUT_MAX_LEVELS_NOISE else OUTPUT_MAX_LEVELS
                renderer.setInput(image)
                for (recipe in recipes(RANDOM_RECIPES)) {
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
                        imageMax = maxOf(imageMax, diff)
                        assertThat(diff).isAtMost(allowed)
                    }
                }
                Log.i(TAG, "output $space $name: max diff $imageMax levels")
            }
            Log.i(
                TAG,
                "output $space: max diff $maxDiff levels, mean diff ${sum.toDouble() / count}, " +
                    "off by >1: $overOne of $count",
            )
            assertThat(sum.toDouble() / count).isAtMost(OUTPUT_MEAN_LEVELS)
        }
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

    private companion object {
        const val TAG = "REVEAL_PARITY"
        const val RANDOM_RECIPES = 12

        // Linear stage: |gpu - cpu| <= LINEAR_ABS + LINEAR_REL * (largest |channel| of that pixel); docs/adr/0002.
        const val LINEAR_ABS = 1e-6f
        const val LINEAR_REL = 3e-3f
        const val LINEAR_MEAN = 3e-4

        // 8-bit output: dithering turns sub-level float differences into at most one level of change.
        // The random-noise image is a stress test: saturated pixels with tiny channels in the darks, where one
        // float16 input rounding is worth up to ~3 levels of the steep sRGB toe.
        const val OUTPUT_MAX_LEVELS = 2
        const val OUTPUT_MAX_LEVELS_NOISE = 4
        const val OUTPUT_MEAN_LEVELS = 0.1
    }
}
