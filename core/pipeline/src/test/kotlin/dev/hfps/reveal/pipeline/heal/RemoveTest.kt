@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.PipelineInputTileSource
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.util.concurrent.Executors
import org.junit.Test

/** Remove (docs/adr/0023): the patch method, the contract, the same fill at every resolution. */
class RemoveTest {
    private val w = 200
    private val h = 150
    private val clean = HealScenes.stripes(w, h)
    private val withObject = HealScenes.withObject(clean, 100, 75, 24)
    private val spot = HealScenes.spot("r", Heal.KIND_REMOVE, w, h, 100f, 75f, 18f, feather = 20f)

    private fun fills() = RemoveFills(executor = Executors.newSingleThreadExecutor())

    @Test
    fun `the patch method continues the stripes`() {
        val out = HealOp.apply(withObject, listOf(spot), HealContext(fills())).linearRgb
        val before = HealScenes.meanError(withObject.linearRgb, clean.linearRgb, w, 88, 63, 112, 87)
        val after = HealScenes.meanError(out, clean.linearRgb, w, 88, 63, 112, 87)
        assertThat(after).isLessThan(before * 0.2f)
        // Better than a smooth membrane: the stripes are there.
        val membrane = membraneFill(withObject, spot)
        assertThat(after).isLessThan(HealScenes.meanError(membrane, clean.linearRgb, w, 88, 63, 112, 87))
    }

    @Test
    fun `the fill is deterministic`() {
        val a = HealOp.apply(withObject, listOf(spot), HealContext(fills())).linearRgb
        val b = HealOp.apply(withObject, listOf(spot), HealContext(fills())).linearRgb
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `without a source a Remove spot renders nothing`() {
        val out = HealOp.apply(withObject, listOf(spot), HealContext.NONE).linearRgb
        assertThat(out).isEqualTo(withObject.linearRgb)
    }

    @Test
    fun `the crop is defined on the original whatever the rendered size`() {
        val spec = checkNotNull(RemoveSpec.of(spot, w, h))
        assertThat(spec.factor).isEqualTo(1)
        assertThat(spec.width).isAtMost(RemoveSpec.MAX_SIDE)
        val big = checkNotNull(RemoveSpec.of(spot, 6000, 4500))
        assertThat(big.factor).isGreaterThan(1)
        assertThat(maxOf(big.width, big.height)).isAtMost(RemoveSpec.MAX_SIDE)
        val hole = spec.hole(spot)
        assertThat(hole.count { it > 0f }).isGreaterThan(900)
    }

    @Test
    fun `a half-size render uses the original's fill`() {
        // The original is full size; the render is a half-size proxy of it: the fill is the original's.
        val half = halfSize(withObject)
        val source = fills()
        val context = HealContext(source, PixelReader.of(withObject))
        val proxy = HealOp.apply(half, listOf(spot), context).linearRgb
        val full = HealOp.apply(withObject, listOf(spot), context).linearRgb
        val fullHalf = halfSize(PipelineInput.linearSrgb(w, h, full)).linearRgb
        val err = HealScenes.meanError(proxy, fullHalf, w / 2, 44, 31, 56, 43)
        assertThat(err).isLessThan(0.05f)
    }

    @Test
    fun `a model method's pixels are upsampled and decoded`() {
        // A fake "model" that paints the hole flat mid-grey (display 0.5): the output must decode to that grey.
        val grey = RemoveMethod { input ->
            val rgb = input.rgb.copyOf()
            for (i in 0 until input.width * input.height) if (input.hole[i] > 0f) for (c in 0..2) rgb[i * 3 + c] = 0.5f
            RemoveOutput(rgb)
        }
        val source =
            RemoveFills(
                mapOf(Heal.METHOD_PATCH to PatchFill, "model:grey@1" to grey),
                executor = Executors.newSingleThreadExecutor(),
            )
        val modelSpot = spot.copy(method = "model:grey@1", feather = 0f)
        val out = HealOp.apply(withObject, listOf(modelSpot), HealContext(source)).linearRgb
        val i = (75 * w + 100) * 3
        // The red object is gone: the model's grey, blended into the stripes' light by the membrane.
        assertThat(out[i]).isLessThan(0.6f)
        assertThat(out[i + 1]).isGreaterThan(0.1f)
        // Away from the spot nothing changed.
        assertThat(out[(5 * w + 5) * 3]).isEqualTo(withObject.linearRgb[(5 * w + 5) * 3])
    }

    @Test
    fun `a tiled source gives the same pixels as the whole image`() {
        val source = fills()
        val whole = HealOp.apply(withObject, listOf(spot), HealContext(source)).linearRgb
        val tiled = HealedTileSource(PipelineInputTileSource(withObject), listOf(spot), source, wait = true)
        val out = FloatArray(37 * 29 * 4)
        tiled.read(81, 60, 37, 29, out)
        for (y in 0 until 29) {
            for (x in 0 until 37) {
                for (c in 0..2) {
                    assertThat(out[(y * 37 + x) * 4 + c]).isEqualTo(whole[((60 + y) * w + 81 + x) * 3 + c])
                }
            }
        }
    }

    private fun membraneFill(image: PipelineInput, heal: Heal): FloatArray {
        val fp = checkNotNull(Footprint.of(heal, w, h, feather = 0f))
        val state = ByteArray(w * h) { if (fp.at(it % w, it / w) > 0f) Membrane.INSIDE else Membrane.FIXED }
        val values = image.linearRgb.copyOf()
        Membrane.solve(w, h, state, values)
        return values
    }

    private fun halfSize(input: PipelineInput): PipelineInput {
        val hw = input.width / 2
        val hh = input.height / 2
        val rgb = FloatArray(hw * hh * 3)
        for (y in 0 until hh) {
            for (x in 0 until hw) {
                for (c in 0..2) {
                    var s = 0f
                    for (dy in 0..1) {
                        for (dx in 0..1) {
                            s +=
                                input.linearRgb[((y * 2 + dy) * input.width + x * 2 + dx) * 3 + c]
                        }
                    }
                    rgb[(y * hw + x) * 3 + c] = s / 4f
                }
            }
        }
        return PipelineInput.linearSrgb(hw, hh, rgb)
    }
}
