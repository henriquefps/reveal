package dev.hfps.reveal.pipeline

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.gl.MaskOverlay
import dev.hfps.reveal.pipeline.gl.ViewTransform
import dev.hfps.reveal.pipeline.mask.AiMask
import dev.hfps.reveal.pipeline.mask.AiMaskSource
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.mask.MaskRaster
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The masks on the GPU (docs/adr/0022): coverage equals the CPU's, a stroke in progress updates only its bins and ends
 * where a fresh render does, an AI mask that arrives later renders, the overlay shows the coverage, and a mask that
 * covers nothing changes nothing. Numbers under `REVEAL_MASKS`.
 */
@RunWith(AndroidJUnit4::class)
class MaskRenderTest {
    private lateinit var renderer: GpuDevelopRenderer
    private val image = SyntheticImages.scene(320, 214)

    @Before
    fun setUp(): Unit = runBlocking {
        renderer = GpuDevelopRenderer.create()
        renderer.upload(image)
        renderer.setMaskSource(MaskFixtures.source, "fixture")
    }

    @After
    fun tearDown() {
        renderer.close()
    }

    private fun recipeOf(vararg components: MaskComponent) = Recipe(
        masks = listOf(Mask("m", adjustments = LocalAdjustments(exposure = 1f), components = components.toList())),
    )

    @Test
    fun coverageMatchesTheCpu() = runBlocking {
        val brush = MaskComponent.Brush(
            strokes = listOf(
                BrushStroke.raw(MaskFixtures.scribble(200), size = 0.06f, feather = 70f, flow = 60f),
                BrushStroke.raw(MaskFixtures.scribble(40, 2.0), size = 0.03f, erase = true, flow = 80f),
            ),
        )
        val cases = listOf(
            "linear" to recipeOf(MaskComponent.Linear(x0 = 0.1f, y0 = 0.2f, x1 = 0.9f, y1 = 0.7f, feather = 70f)),
            "radial" to recipeOf(MaskComponent.Radial(cx = 0.4f, cy = 0.6f, rx = 0.3f, ry = 0.1f, rotation = 33f)),
            "brush" to recipeOf(brush),
            "ai" to recipeOf(MaskComponent.Ai(model = "fake@1", kind = "SUBJECT")),
            "combined" to recipeOf(
                MaskComponent.Linear(x0 = 0f, y0 = 0f, x1 = 1f, y1 = 0f),
                brush.copy(op = MaskOp.ADD),
                MaskComponent.Radial(op = MaskOp.SUBTRACT, cx = 0.5f, cy = 0.5f, rx = 0.15f, feather = 30f),
                MaskComponent.Ai(op = MaskOp.INTERSECT, invert = true, model = "fake@1", kind = "SUBJECT"),
            ),
        )
        for ((name, recipe) in cases) {
            val gpu = checkNotNull(renderer.readCoverage(recipe, "m"))
            val plan = MaskPlan.of(recipe.masks, image.width, image.height)
            val cpu = MaskRaster.coverage(
                plan,
                MaskRaster.Region(0, 0, image.width, image.height),
                null,
                plan.ai.map { MaskFixtures.fakeAi },
            ).single()
            var worst = 0f
            for (i in cpu.indices) worst = maxOf(worst, abs(cpu[i] - gpu[i]))
            Log.i(TAG, "coverage $name: max |gpu - cpu| $worst, covered ${cpu.count { it > 0f }}")
            assertThat(worst).isAtMost(COVERAGE_TOLERANCE)
        }
    }

    @Test
    fun aStrokeInProgressUpdatesItsBinsAndEndsLikeAFreshRender() = runBlocking {
        val points = MaskFixtures.scribble(400)
        fun recipe(n: Int) = recipeOf(MaskComponent.Brush(strokes = listOf(BrushStroke.raw(points.take(n), size = 0.05f))))
        var lastBins = 0
        for (n in listOf(20, 60, 61, 120, 250, 400)) {
            renderer.readCoverage(recipe(n), "m")
            val stats = renderer.maskStats
            Log.i(TAG, "stroke at $n points: $stats")
            if (n > 20) {
                // Only the bins the new dabs reach, never the whole layer again.
                assertThat(stats.brushFull).isEqualTo(0)
                assertThat(stats.brushBins).isLessThan(((image.width + 63) / 64) * ((image.height + 63) / 64))
            }
            lastBins = stats.brushBins
        }
        assertThat(lastBins).isGreaterThan(0)
        val incremental = checkNotNull(renderer.readCoverage(recipe(400), "m"))
        val fresh = GpuDevelopRenderer.create()
        try {
            fresh.upload(image)
            val once = checkNotNull(fresh.readCoverage(recipe(400), "m"))
            assertThat(incremental.toList()).isEqualTo(once.toList())
        } finally {
            fresh.close()
        }
    }

    @Test
    fun anAiMaskThatArrivesLaterRenders() = runBlocking {
        val ready = AtomicReference<AiMask?>(null)
        val callback = AtomicReference<(() -> Unit)?>(null)
        renderer.setMaskSource(
            AiMaskSource { _, onReady ->
                ready.get() ?: run {
                    callback.set(onReady)
                    null
                }
            },
            "later",
        )
        val recipe = recipeOf(MaskComponent.Ai(model = "fake@1", kind = "SUBJECT"))
        val before = renderer.renderLinear(recipe)
        assertThat(before.toList()).isEqualTo(renderer.renderLinear(Recipe()).toList())
        ready.set(MaskFixtures.fakeAi)
        checkNotNull(callback.get()).invoke()
        val after = renderer.renderLinear(recipe)
        val cpu = CpuDevelop.renderLinear(image, recipe, masks = MaskFixtures.context)
        var worst = 0f
        for (i in cpu.indices) worst = maxOf(worst, abs(cpu[i] - after[i]) / maxOf(1f, abs(cpu[i])))
        Log.i(TAG, "ai arrived: worst relative error $worst")
        assertThat(after.toList()).isNotEqualTo(before.toList())
        assertThat(worst).isAtMost(LINEAR_TOLERANCE)
    }

    @Test
    fun theOverlayShowsTheCoverage() = runBlocking {
        val recipe = Recipe(
            masks = listOf(
                Mask(
                    "o",
                    components = listOf(MaskComponent.Radial(cx = 0.5f, cy = 0.5f, rx = 0.2f, feather = 0f)),
                ),
            ),
        )
        renderer.setRecipe(recipe)
        val view = ViewTransform(1f, image.width / 2f, image.height / 2f)
        val settings = OutputSettings(dither = false)
        val plain = renderer.renderView(view, image.width, image.height, settings)
        renderer.setMaskOverlay(MaskOverlay("o", MaskOverlay.Mode.GREYSCALE))
        val grey = renderer.renderView(view, image.width, image.height, settings)
        fun px(a: ByteArray, x: Int, y: Int, c: Int) = a[(y * image.width + x) * 4 + c].toInt() and 0xff
        // A mask without adjustments still shows its shape: white inside, black outside.
        assertThat(px(grey, 160, 107, 0)).isEqualTo(255)
        assertThat(px(grey, 5, 5, 1)).isEqualTo(0)
        renderer.setMaskOverlay(MaskOverlay("o", MaskOverlay.Mode.COLOUR, 0xFF0000, 1f))
        val red = renderer.renderView(view, image.width, image.height, settings)
        assertThat(px(red, 160, 107, 0)).isEqualTo(255)
        assertThat(px(red, 160, 107, 1)).isAtMost(1)
        assertThat(px(red, 5, 5, 0)).isEqualTo(px(plain, 5, 5, 0))
        renderer.setMaskOverlay(null)
        val again = renderer.renderView(view, image.width, image.height, settings)
        assertThat(again.toList()).isEqualTo(plain.toList())
    }

    @Test
    fun aMaskThatCoversNothingChangesNothing() = runBlocking {
        val nowhere = MaskComponent.Radial(cx = 5f, cy = 5f, rx = 0.01f, feather = 0f)
        val recipe = Recipe(
            masks = listOf(
                Mask(
                    "n",
                    adjustments = LocalAdjustments(exposure = 1f, contrast = 50f, saturation = 50f, hue = 30f),
                    components = listOf(nowhere),
                ),
            ),
        )
        assertThat(renderer.renderOutput(recipe).toList()).isEqualTo(renderer.renderOutput(Recipe()).toList())
    }

    private companion object {
        const val TAG = "REVEAL_MASKS"

        /** Float16 storage of the coverage, fp32 transcendental differences. */
        const val COVERAGE_TOLERANCE = 2e-3f
        const val LINEAR_TOLERANCE = 2e-2f
    }
}
