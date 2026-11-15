package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
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
 * The preview's shortcuts for slots 11 and 12 (docs/adr/0012, "M2/P") render what a fresh render renders: a frame
 * at the end of a drag (the cached luminance chain and its one-pass shrinkage, the kept colour copy and blurs)
 * equals a fresh renderer's frame bit for bit, and the half-resolution write (level 1 directly) gives the coarser
 * levels the full render's mips give.
 */
@RunWith(AndroidJUnit4::class)
class PreviewPassesTest {
    private lateinit var renderer: GpuDevelopRenderer
    private val image = SyntheticImages.noisyCard(W, H, seed = 9)

    @Before
    fun setUp() {
        renderer = runBlocking { GpuDevelopRenderer.create() }
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    @Test
    fun aFrameAfterADragEqualsAFreshRender() = runBlocking {
        renderer.setInput(image, SCALE)
        for (target in RECIPES) {
            // A drag towards the target over every Detail slider family: the passes keep what each step does not
            // change (the luminance chain, the binned colour copy, the blurs), so the last frame reuses them.
            for (k in 0..3) {
                val d = target.detail
                renderer.renderLinear(
                    target.copy(
                        detail = d.copy(
                            nrLuminance = d.nrLuminance * k / 4f,
                            nrColour = d.nrColour * k / 4f,
                            sharpenRadius = 0.5f + k * 0.5f,
                        ),
                    ),
                )
            }
            val dragged = renderer.renderLinear(target)
            val fresh = GpuDevelopRenderer.create()
            val reference = try {
                fresh.setInput(image, SCALE)
                fresh.renderLinear(target)
            } finally {
                fresh.close()
            }
            val diff = dragged.indices.maxOf { abs(dragged[it] - reference[it]) }
            Log.i(TAG, "frame after a drag vs a fresh render: max abs difference $diff")
            assertThat(diff).isEqualTo(0f)
        }
    }

    @Test
    fun theHalfResolutionWriteGivesTheFullRendersCoarserLevels() = runBlocking {
        // M2/P's fit view: Detail at the proxy's size, the final pass writing the 2 x 2 means.
        renderer.coarseDetail = false
        val (worst, _) = compareFitView()
        assertThat(worst).isAtMost(MAX_LEVELS)
    }

    @Test
    fun theHalfResolutionDetailShowsWhatTheFullRenderShows() = runBlocking {
        // W2/P's fit view: every Detail and Effects pass on the half-resolution copy at twice the pixel scale.
        renderer.coarseDetail = true
        val (worst, mean) = compareFitView()
        assertThat(mean).isAtMost(COARSE_MEAN_LEVELS)
        assertThat(worst).isAtMost(COARSE_MAX_LEVELS)
    }

    @Test
    fun aHalfResolutionFrameAfterADragEqualsAFreshOne() = runBlocking {
        renderer.setInput(image, SCALE)
        val view = ViewTransform(QUARTER, W / 2f, H / 2f)
        val w = (W * QUARTER).toInt()
        val h = (H * QUARTER).toInt()
        val settings = OutputSettings(dither = false)
        for (target in RECIPES) {
            for (k in 0..3) {
                val d = target.detail
                renderer.setRecipe(
                    target.copy(
                        detail = d.copy(nrLuminance = d.nrLuminance * k / 4f, sharpenRadius = 0.5f + k * 0.5f),
                    ),
                )
                renderer.renderView(view, w, h, settings, halfStage = true)
            }
            renderer.setRecipe(target)
            val dragged = renderer.renderView(view, w, h, settings, halfStage = true)
            val fresh = GpuDevelopRenderer.create()
            val reference = try {
                fresh.setInput(image, SCALE)
                fresh.setRecipe(target)
                fresh.renderView(view, w, h, settings, halfStage = true)
            } finally {
                fresh.close()
            }
            assertThat(dragged.toList()).isEqualTo(reference.toList())
        }
    }

    /** The fit view (25 %) with the half-resolution stage against the full render's: worst and mean 8-bit levels. */
    private suspend fun compareFitView(): Pair<Int, Double> {
        renderer.setInput(image, SCALE)
        val view = ViewTransform(QUARTER, W / 2f, H / 2f)
        val w = (W * QUARTER).toInt()
        val h = (H * QUARTER).toInt()
        val settings = OutputSettings(dither = false)
        var worst = 0
        var worstMean = 0.0
        for (recipe in RECIPES) {
            renderer.setRecipe(recipe)
            val full = renderer.renderView(view, w, h, settings, halfStage = false)
            val half = renderer.renderView(view, w, h, settings, halfStage = true)
            var max = 0
            var sum = 0L
            val histogram = IntArray(256)
            for (i in full.indices) {
                if (i % 4 == 3) continue
                val d = abs((full[i].toInt() and 0xFF) - (half[i].toInt() and 0xFF))
                max = maxOf(max, d)
                sum += d
                histogram[d]++
            }
            val n = 3 * full.size / 4
            var acc = 0
            val p99 = histogram.indices.first {
                acc += histogram[it]
                acc >= n * 0.99
            }
            val mean = sum.toDouble() / n
            Log.i(
                TAG,
                "half-resolution stage (coarse ${renderer.coarseDetail}) vs full render at 25 %: max $max levels, " +
                    "p99 $p99, mean ${"%.3f".format(mean)}",
            )
            worst = maxOf(worst, max)
            worstMean = maxOf(worstMean, mean)
        }
        return worst to worstMean
    }

    private companion object {
        const val TAG = "REVEAL_PREVIEW"
        const val W = 960
        const val H = 640
        const val SCALE = 2.5f
        const val QUARTER = 0.25f

        // The driver's box mips against the final pass' own 2 x 2 means (float16 storage of both).
        const val MAX_LEVELS = 1

        // Detail at half resolution (W2/P): a display approximation of the same operator at the coarser scale.
        const val COARSE_MEAN_LEVELS = 1.5
        const val COARSE_MAX_LEVELS = 16

        val RECIPES = listOf(
            Recipe(
                light = Light(0.2f, contrast = 15f),
                detail = Detail(nrLuminance = 60f, nrColour = 50f, sharpenAmount = 80f, sharpenMasking = 30f),
                effects = Effects(texture = 40f, clarity = 30f, dehaze = 25f, vignetteAmount = -20f, grainAmount = 20f),
            ),
            Recipe(detail = Detail(nrLuminance = 100f, sharpenAmount = 150f, sharpenRadius = 3f)),
            Recipe(detail = Detail(nrLuminance = 30f, nrColour = 80f), effects = Effects(texture = -60f)),
        )
    }
}
