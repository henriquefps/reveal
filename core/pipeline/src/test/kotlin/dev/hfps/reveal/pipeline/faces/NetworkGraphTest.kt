package dev.hfps.reveal.pipeline.faces

import com.google.common.truth.Truth.assertThat
import kotlin.math.abs
import kotlin.math.sin
import org.junit.Test

/**
 * The Kotlin network against TensorFlow Lite: the reference values below are LiteRT 1.x's (XNNPACK) outputs of
 * `face_detection_full_range.tflite` for the same synthetic input (`sin(0.07 x + 0.11 y + 1.3 c) * 0.9`), so every
 * kernel (SAME padding, strides, channel padding, half-pixel resize) is checked against the original runtime.
 */
class NetworkGraphTest {
    private val graph =
        checkNotNull(javaClass.getResourceAsStream("/faces/blazeface-full-range.rvnn.gz")).use(NetworkGraph::read)

    @Test
    fun `matches TensorFlow Lite on a synthetic input`() {
        val n = FaceDetector.SIZE
        val input = FloatArray(n * n * 3)
        for (y in 0 until n) {
            for (x in 0 until n) {
                for (c in 0 until 3) {
                    input[(y * n + x) * 3 + c] = (sin(x * 0.07 + y * 0.11 + c * 1.3) * 0.9).toFloat()
                }
            }
        }
        val (reg, cls) = graph.run(input)
        assertThat(cls.size).isEqualTo(2304)
        assertThat(reg.size).isEqualTo(2304 * 16)
        assertThat(cls.sumOf { it.toDouble() }).isWithin(0.05).of(-11810.392874)
        assertThat(reg.sumOf { abs(it).toDouble() }).isWithin(1.0).of(449075.4352)
        val expected = mapOf(
            0 to floatArrayOf(-7.409686f, -3.49820f, -3.05526f, 21.02499f, 21.03045f),
            100 to floatArrayOf(-5.407166f, -2.61200f, -2.04996f, 18.44164f, 18.44111f),
            1151 to floatArrayOf(-7.534943f, -1.76050f, 0.33753f, 40.64607f, 40.64912f),
            2303 to floatArrayOf(-9.317634f, -2.48241f, -0.76301f, 50.30189f, 50.30920f),
        )
        for ((i, v) in expected) {
            assertThat(cls[i]).isWithin(1e-3f).of(v[0])
            for (k in 0 until 4) assertThat(reg[i * 16 + k]).isWithin(2e-3f).of(v[k + 1])
        }
    }

    @Test
    fun `float16 conversion is exact`() {
        assertThat(NetworkGraph.halfToFloat(0x3C00)).isEqualTo(1f)
        assertThat(NetworkGraph.halfToFloat(0xC000)).isEqualTo(-2f)
        assertThat(NetworkGraph.halfToFloat(0x7BFF)).isEqualTo(65504f)
        assertThat(NetworkGraph.halfToFloat(0x0001)).isEqualTo(5.9604645e-8f)
        assertThat(NetworkGraph.halfToFloat(0x8000)).isEqualTo(-0f)
        assertThat(NetworkGraph.halfToFloat(0x3555)).isWithin(1e-7f).of(0.33325195f)
    }
}
