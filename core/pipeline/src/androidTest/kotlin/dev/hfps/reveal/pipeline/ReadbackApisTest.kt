package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.gl.ClippingWarnings
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor's additive renderer APIs on the GPU: the clipping-warning flag of the output pass against the CPU
 * classification ([ClippingWarnings]), and the source readback of the eyedropper ([GpuDevelopRenderer.sampleSource]).
 */
@RunWith(AndroidJUnit4::class)
class ReadbackApisTest {
    private lateinit var renderer: GpuDevelopRenderer

    @Before
    fun setUp() {
        renderer = runBlocking { GpuDevelopRenderer.create() }
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    @Test
    fun clippingOverlaysMarkWhatTheCpuSaysIsClippedAndNothingElse() = runBlocking {
        // Linear 0..2: black at the left, past white on the right.
        val ramp = SyntheticImages.grayRamp(512, 4, max = 2f)
        renderer.setInput(ramp)
        for (recipe in listOf(Recipe.DEFAULT, Recipe(light = Light(exposureEv = -0.5f)))) {
            val plain = OutputSettings(dither = false)
            val cpu = CpuDevelop.render(ramp, recipe, plain)
            val warned = renderer.renderOutput(recipe, plain, ClippingWarnings(shadows = true, highlights = true))
            val off = renderer.renderOutput(recipe, plain, ClippingWarnings.NONE)
            var shadows = 0
            var highlights = 0
            for (i in 0 until ramp.width * ramp.height) {
                val c = IntArray(3) { cpu[i * 4 + it].toInt() and 0xFF }
                val g = IntArray(3) { warned[i * 4 + it].toInt() and 0xFF }
                val max = c.max()
                when {
                    max == 255 -> {
                        assertThat(g.toList()).isEqualTo(ClippingWarnings.HIGHLIGHT_RGB8.toList())
                        highlights++
                    }

                    max == 0 -> {
                        assertThat(g.toList()).isEqualTo(ClippingWarnings.SHADOW_RGB8.toList())
                        shadows++
                    }

                    max in 2..253 -> for (ch in 0..2) assertThat(abs(g[ch] - c[ch])).isAtMost(1)

                    else -> Unit // one level from a threshold: float16 rounding may go either way
                }
                // Off = the plain output, never an overlay colour.
                for (ch in 0..2) assertThat(abs((off[i * 4 + ch].toInt() and 0xFF) - c[ch])).isAtMost(1)
            }
            Log.i(TAG, "clipping ${recipe.light.exposureEv} EV: $shadows shadow px, $highlights highlight px")
            assertThat(shadows).isGreaterThan(0)
            assertThat(highlights).isGreaterThan(0)
        }
    }

    @Test
    fun sourceSamplesAreTheMeanOfTheUploadedPixels() = runBlocking {
        val input = SyntheticImages.rawNoise(64, 48, seed = 7)
        renderer.upload(input)
        for ((x, y, r) in listOf(Triple(10, 12, 4), Triple(0, 0, 3), Triple(63, 47, 2), Triple(30, 20, 0))) {
            val gpu = renderer.sampleSource(x, y, r)
            val expected = DoubleArray(3)
            var n = 0
            for (yy in (y - r).coerceAtLeast(0)..(y + r).coerceAtMost(input.height - 1)) {
                for (xx in (x - r).coerceAtLeast(0)..(x + r).coerceAtMost(input.width - 1)) {
                    for (c in 0..2) expected[c] += input.linearRgb[(yy * input.width + xx) * 3 + c].toDouble()
                    n++
                }
            }
            for (c in 0..2) {
                val e = expected[c] / n
                // float16 storage: 11 significant bits.
                assertThat(gpu[c].toDouble()).isWithin(abs(e) * 2e-3 + 1e-4).of(e)
            }
        }
    }

    private companion object {
        const val TAG = "REVEAL_PARITY"
    }
}
