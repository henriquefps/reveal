@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import java.util.Random
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt
import org.junit.Test

/** Remove with a model method (docs/adr/0024): registration, the fallback to Patch, the grain of upsampled fills. */
class RemoveModelsTest {
    private val w = 200
    private val h = 150
    private val withObject = HealScenes.withObject(HealScenes.stripes(w, h), 100, 75, 24)
    private val spot = HealScenes.spot("r", Heal.KIND_REMOVE, w, h, 100f, 75f, 18f, feather = 0f)
    private val model = "model:grey@1"

    /** A "model" that paints the hole flat mid-grey (display 0.5) and counts its runs. */
    private class Grey : RemoveMethod {
        val runs = AtomicInteger()

        override fun fill(input: RemoveInput): RemoveOutput {
            runs.incrementAndGet()
            val rgb = input.rgb.copyOf()
            for (i in 0 until input.width * input.height) if (input.hole[i] > 0f) for (c in 0..2) rgb[i * 3 + c] = 0.5f
            return RemoveOutput(rgb)
        }
    }

    private fun fills() = RemoveFills(executor = Executors.newSingleThreadExecutor())

    @Test
    fun `a fill made before the model is registered is dropped by the registration`() {
        val source = fills()
        val grey = Grey()
        val modelSpot = spot.copy(method = model)
        // Rendered before the app registered the model: the patch method ran under the model's key.
        val early = HealOp.apply(withObject, listOf(modelSpot), HealContext(source)).linearRgb
        assertThat(grey.runs.get()).isEqualTo(0)
        source.register(model, grey)
        assertThat(source.models()).containsExactly(model)
        val late = HealOp.apply(withObject, listOf(modelSpot), HealContext(source)).linearRgb
        assertThat(grey.runs.get()).isEqualTo(1)
        assertThat(late).isNotEqualTo(early)
        // The patch method's own fills are untouched by a registration.
        val patch = HealOp.apply(withObject, listOf(spot), HealContext(source)).linearRgb
        source.register("model:other@1", Grey())
        assertThat(HealOp.apply(withObject, listOf(spot), HealContext(source)).linearRgb).isEqualTo(patch)
    }

    @Test
    fun `an unavailable model falls back to the patch fill and says so`() {
        val source = fills()
        source.register(model) { throw RemoveUnavailableException("not enough memory") }
        val modelSpot = spot.copy(method = model)
        val engine = HealEngine(
            w,
            h,
            PixelReader.of(withObject),
            withObject.colourOnly(),
            RemoveContext(source, PixelReader.of(withObject), withObject.colourOnly()),
        )
        val latch = java.util.concurrent.CountDownLatch(1)
        engine.update(listOf(modelSpot)) { latch.countDown() }
        latch.await(60, java.util.concurrent.TimeUnit.SECONDS)
        engine.update(listOf(modelSpot))
        assertThat(engine.fellBack).containsExactly("r")
        assertThat(engine.failed).isEmpty()
        assertThat(engine.current.single()).isNotNull()
        // The object is gone as with the patch method.
        val out = HealOp.apply(withObject, listOf(modelSpot), HealContext(source)).linearRgb
        val clean = HealScenes.stripes(w, h).linearRgb
        val before = HealScenes.meanError(withObject.linearRgb, clean, w, 88, 63, 112, 87)
        assertThat(HealScenes.meanError(out, clean, w, 88, 63, 112, 87)).isLessThan(before * 0.3f)
    }

    @Test
    fun `grain measures the ring's noise and is deterministic`() {
        val n = 120
        val sigma = 0.02f
        val rnd = Random(3)
        val rgb = FloatArray(n * n * 3) { 0.3f + sigma * rnd.nextGaussian().toFloat() }
        val outside = { x: Int, y: Int -> x !in 40..80 || y !in 40..80 }
        assertThat(Grain.of(rgb, n, n, outside, scale = 1f, seed = 1)).isNull()
        val grain = checkNotNull(Grain.of(rgb, n, n, outside, scale = 0.5f, seed = 1))
        // amount sqrt(1 - 0.25) of the measured sigma: the noise added has about that deviation
        val samples = FloatArray(30_000) {
            val out = FloatArray(3)
            grain.add(it % 300, it / 300, out, 0)
            out[0]
        }
        val mean = samples.average()
        val sd = sqrt(samples.map { (it - mean) * (it - mean) }.average())
        assertThat(sd).isWithin(0.15 * sigma).of(sqrt(0.75) * sigma)
        assertThat(mean).isWithin(0.002).of(0.0)
        val again = checkNotNull(Grain.of(rgb, n, n, outside, scale = 0.5f, seed = 1))
        val a = FloatArray(3).also { grain.add(17, 23, it, 0) }
        val b = FloatArray(3).also { again.add(17, 23, it, 0) }
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `an upsampled model fill gets the surroundings' grain, the patch fill does not change`() {
        val big = 1600
        val bh = 1200
        val sigma = 0.02f
        val rnd = Random(7)
        val noisy = PipelineInput.linearSrgb(
            big,
            bh,
            FloatArray(big * bh * 3) {
                0.3f +
                    sigma * rnd.nextGaussian().toFloat()
            },
        )
        val large = HealScenes.spot("g", Heal.KIND_REMOVE, big, bh, 800f, 600f, 160f, feather = 0f).copy(method = model)
        assertThat(checkNotNull(RemoveSpec.of(large, big, bh)).factor).isGreaterThan(1)
        val source = fills().also { it.register(model, Grey()) }
        val out = HealOp.apply(noisy, listOf(large), HealContext(source)).linearRgb
        // the centre of the hole: the flat grey decoded, plus grain about sqrt(1 - s²) sigma
        val values = ArrayList<Float>()
        for (y in 570 until 630) for (x in 770 until 830) values += out[(y * big + x) * 3 + 1]
        val mean = values.average()
        val sd = sqrt(values.map { (it - mean) * (it - mean) }.average())
        assertThat(sd).isGreaterThan(0.5 * sigma)
        assertThat(sd).isLessThan(1.2 * sigma)
    }
}
