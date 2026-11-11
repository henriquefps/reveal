package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.geometry.GeometryMapping
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Test

class GeometryOpTest {
    /** A W x H image whose pixel (x, y) is (x, y, x + 1000 y): every pixel is identifiable. */
    private fun coordinates(w: Int, h: Int) = FloatArray(w * h * 3).also {
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 3
            it[i] = x.toFloat()
            it[i + 1] = y.toFloat()
            it[i + 2] = x + 1000f * y
        }
    }

    @Test
    fun `catmull-rom weights sum to one and interpolate`() {
        for (t in listOf(0f, 0.1f, 0.25f, 0.5f, 0.9f, 0.999f)) {
            assertThat(GeometryOp.weights(t).sum().toDouble()).isWithin(1e-6).of(1.0)
        }
        assertThat(GeometryOp.weights(0f).map { it + 0f }).containsExactly(0f, 1f, 0f, 0f).inOrder()
    }

    @Test
    fun `catmull-rom reproduces a linear ramp exactly between samples`() {
        val w = 16
        val h = 8
        val rgb = coordinates(w, h)
        val out = FloatArray(3)
        for (x in listOf(3.2f, 5.5f, 7.77f)) {
            GeometryOp.catmullRom(rgb, w, h, x, 4.1f, out)
            // Pixel x sits at x + 0.5: the ramp value at x is x - 0.5.
            assertThat(out[0].toDouble()).isWithin(1e-4).of(x - 0.5)
            assertThat(out[1].toDouble()).isWithin(1e-4).of(4.1 - 0.5)
        }
    }

    @Test
    fun `crop, quarter turns and flips copy pixels exactly`() {
        val w = 12
        val h = 9
        val rgb = coordinates(w, h)
        val g = Geometry(crop = CropRect(2f / 12, 1f / 9, 10f / 12, 7f / 9), rotate = 1, flipH = true)
        val m = GeometryMapping.of(g, w, h)
        val out = GeometryOp.apply(rgb, m)

        assertThat(m.outputWidth).isEqualTo(6)
        assertThat(m.outputHeight).isEqualTo(8)
        for (y in 0 until m.outputHeight) for (x in 0 until m.outputWidth) {
            val p = m.outputToImage(x + 0.5, y + 0.5)
            val i = (y * m.outputWidth + x) * 3
            assertThat(out[i]).isEqualTo((p.x - 0.5).toFloat())
            assertThat(out[i + 1]).isEqualTo((p.y - 0.5).toFloat())
        }
        // Rotate 90 + flip H is a transpose: output (x, y) shows crop pixel (y, x).
        assertThat(out[0]).isEqualTo(2f)
        assertThat(out[1]).isEqualTo(1f)
        assertThat(out[(1 * 6 + 0) * 3]).isEqualTo(3f)
    }

    @Test
    fun `empty corners are black and the inside matches the mapped samples`() {
        val w = 40
        val h = 30
        val rgb = coordinates(w, h)
        val g = Geometry(angle = 30f, constrain = false)
        val m = GeometryMapping.of(g, w, h)
        val out = GeometryOp.apply(rgb, m)

        // The top-left output corner is outside the turned image.
        assertThat(out.copyOfRange(0, 3).toList()).containsExactly(0f, 0f, 0f)
        // At the centre a smooth ramp is reproduced at the mapped point.
        val cx = m.outputWidth / 2
        val cy = m.outputHeight / 2
        val p = m.outputToImage(cx + 0.5, cy + 0.5)
        val i = (cy * m.outputWidth + cx) * 3
        assertThat(out[i].toDouble()).isWithin(1e-3).of(p.x - 0.5)
        assertThat(out[i + 1].toDouble()).isWithin(1e-3).of(p.y - 0.5)
    }

    @Test
    fun `tiles render the same pixels as the whole output`() {
        val input = SyntheticImages.scene(64, 48)
        val recipe = Recipe(geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.95f), angle = -11f, rotate = 3))
        val linear = CpuDevelop.renderLinear(input, recipe)
        val m = CpuDevelop.mapping(input, recipe)
        val whole = GeometryOp.apply(linear, m)
        val random = Random(5)
        repeat(10) {
            val x0 = random.nextInt(m.outputWidth - 8)
            val y0 = random.nextInt(m.outputHeight - 8)
            val tile = GeometryOp.apply(linear, m, x0, y0, 8, 8)
            for (y in 0 until 8) for (x in 0 until 8) for (c in 0 until 3) {
                assertThat(tile[(y * 8 + x) * 3 + c]).isEqualTo(whole[((y0 + y) * m.outputWidth + x0 + x) * 3 + c])
            }
        }
    }

    @Test
    fun `straightening by a tiny angle stays close to the original`() {
        val input = SyntheticImages.scene(96, 64)
        val plain = CpuDevelop.renderGeometry(input, Recipe())
        val turned = CpuDevelop.renderGeometry(input, Recipe(geometry = Geometry(angle = 0.1f, constrain = false)))
        var sum = 0.0
        for (i in plain.indices) sum += abs(plain[i] - turned[i])
        assertThat(sum / plain.size).isLessThan(0.01)
    }

    @Test
    fun `the default geometry is the identity`() {
        val input = SyntheticImages.scene(32, 24)
        assertThat(CpuDevelop.renderGeometry(input, Recipe())).isEqualTo(CpuDevelop.renderLinear(input, Recipe()))
    }
}
