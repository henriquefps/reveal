package dev.hfps.reveal.ml.face

import com.google.common.truth.Truth.assertThat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Test

class SoftPolygonTest {
    private val square = SoftPolygon(floatArrayOf(10f, 30f, 30f, 10f), floatArrayOf(10f, 10f, 30f, 30f), feather = 2f)

    @Test
    fun `inside is 1, outside 0, the outline 0_5 and the ramp linear`() {
        assertThat(square.at(20f, 20f)).isEqualTo(1f)
        assertThat(square.at(50f, 50f)).isEqualTo(0f)
        assertThat(square.at(10f, 20f)).isWithin(1e-6f).of(0.5f)
        assertThat(square.at(10.5f, 20f)).isWithin(1e-6f).of(0.75f)
        assertThat(square.at(9.5f, 20f)).isWithin(1e-6f).of(0.25f)
        assertThat(square.at(8.9f, 20f)).isEqualTo(0f)
    }

    @Test
    fun `the summed coverage is the polygon's area`() {
        var sum = 0.0
        for (y in 0 until 40) for (x in 0 until 40) sum += square.at(x + 0.5f, y + 0.5f)
        // A linear ramp centred on the edge keeps the area (up to the corners).
        assertThat(sum).isWithin(2.0).of(400.0)
    }

    @Test
    fun `winding direction does not matter`() {
        val reversed = SoftPolygon(floatArrayOf(10f, 10f, 30f, 30f), floatArrayOf(10f, 30f, 30f, 10f), feather = 2f)
        for (p in listOf(20f to 20f, 10.3f to 15f, 31f to 31f)) {
            assertThat(reversed.at(p.first, p.second)).isWithin(1e-6f).of(square.at(p.first, p.second))
        }
    }

    @Test
    fun `the smoothed contour passes through the landmarks and stays close to a circle`() {
        val n = 12
        val xs = FloatArray(n) { 50f + 20f * cos(2 * PI * it / n).toFloat() }
        val ys = FloatArray(n) { 50f + 20f * sin(2 * PI * it / n).toFloat() }
        val (sx, sy) = SoftPolygon.smooth(xs, ys)
        assertThat(sx.size).isEqualTo(n * SoftPolygon.SMOOTH_STEPS)
        for (i in 0 until n) {
            assertThat(sx[i * SoftPolygon.SMOOTH_STEPS]).isWithin(1e-4f).of(xs[i])
            assertThat(sy[i * SoftPolygon.SMOOTH_STEPS]).isWithin(1e-4f).of(ys[i])
        }
        for (i in sx.indices) {
            val r = kotlin.math.hypot(sx[i] - 50f, sy[i] - 50f)
            assertThat(abs(r - 20f)).isLessThan(0.2f)
        }
    }

    @Test
    fun `the box covers the feather and clips to the image`() {
        val box = SoftPolygon.box(listOf(square), 25, 100)
        assertThat(box.toList()).containsExactly(8, 8, 25, 33).inOrder()
    }
}
