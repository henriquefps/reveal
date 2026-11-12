package dev.hfps.reveal.pipeline.faces

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.util.Random
import kotlin.math.max
import org.junit.Test

/**
 * The faster face analysis (M3/F, docs/adr/0020 "Measurements") computes exactly what the plain loops computed: the
 * blocked pointwise convolution and the parallel detection image are bit-identical to the plain loops (ADD and RELU
 * in place are the same arithmetic), so the detections on every photo are exactly the ones before.
 */
class FaceSpeedupTest {
    @Test
    fun `the blocked pointwise convolution is bit-identical to the plain loop`() {
        val random = Random(7)
        for ((n, ic, oc) in listOf(Triple(1, 1, 1), Triple(5, 3, 7), Triple(48 * 48, 32, 64), Triple(9, 96, 6))) {
            val x = FloatArray(n * ic) { random.nextFloat() * 4 - 2 }
            val w = FloatArray(oc * ic) { random.nextFloat() - 0.5f }
            val b = FloatArray(oc) { random.nextFloat() }
            val fast = FloatArray(n * oc)
            val plain = FloatArray(n * oc)
            NetworkGraph.pointwise(x, w, b, fast, n, ic, oc)
            NetworkGraph.pointwiseReference(x, w, b, plain, n, ic, oc)
            for (i in fast.indices) assertThat(fast[i].toRawBits()).isEqualTo(plain[i].toRawBits())
        }
    }

    @Test
    fun `the detection image is bit-identical to the sequential per-pixel conversion`() {
        for (name in listOf("studio-trio", "outdoor-group")) {
            val input = FaceFixtures.input(name)
            val fast = FaceInput.from(input)
            val reference = reference(input)
            assertThat(fast.width).isEqualTo(reference.width)
            for (i in fast.rgb.indices) assertThat(fast.rgb[i].toRawBits()).isEqualTo(reference.rgb[i].toRawBits())
        }
    }

    /** `FaceInput.from` as it was: sequential downscale, `Mat3.times` per pixel. */
    @Suppress("LongMethod", "NestedBlockDepth") // A verbatim copy of the former code, the reference.
    private fun reference(input: dev.hfps.reveal.pipeline.PipelineInput): DetectionImage {
        val long = max(input.width, input.height)
        val side = FaceDetector.DETECT_SIDE
        val small = if (long <= side) {
            input
        } else {
            val w = max(1, (input.width.toLong() * side / long).toInt())
            val h = max(1, (input.height.toLong() * side / long).toInt())
            val out = FloatArray(w * h * 3)
            for (y in 0 until h) {
                val y0 = (y.toLong() * input.height / h).toInt()
                val y1 = max(y0 + 1, ((y + 1L) * input.height / h).toInt())
                for (x in 0 until w) {
                    val x0 = (x.toLong() * input.width / w).toInt()
                    val x1 = max(x0 + 1, ((x + 1L) * input.width / w).toInt())
                    var r = 0.0
                    var g = 0.0
                    var b = 0.0
                    for (yy in y0 until y1) {
                        for (xx in x0 until x1) {
                            val i = (yy * input.width + xx) * 3
                            r += input.linearRgb[i]
                            g += input.linearRgb[i + 1]
                            b += input.linearRgb[i + 2]
                        }
                    }
                    val k = 1.0 / ((y1 - y0) * (x1 - x0))
                    val o = (y * w + x) * 3
                    out[o] = (r * k).toFloat()
                    out[o + 1] = (g * k).toFloat()
                    out[o + 2] = (b * k).toFloat()
                }
            }
            dev.hfps.reveal.pipeline.PipelineInput(
                input.kind,
                w,
                h,
                out,
                input.cameraToXyz,
                input.asShotMultipliers?.copyOf(),
                input.saturation,
                input.noise,
                input.lens,
            )
        }
        val rgb = CpuDevelop.renderLinear(small, Recipe())
        val gain = FaceInput.gain(rgb)
        val m = ColorSpaces.SRGB.fromXyz * ColorSpaces.REC2020.toXyz
        val out = FloatArray(rgb.size)
        val p = DoubleArray(3)
        for (i in 0 until rgb.size / 3) {
            val o = i * 3
            p[0] = rgb[o] * gain
            p[1] = rgb[o + 1] * gain
            p[2] = rgb[o + 2] * gain
            val s = m.times(p)
            for (c in 0..2) out[o + c] = FaceInput.encode(s[c].coerceIn(0.0, 1.0)).toFloat()
        }
        return DetectionImage(small.width, small.height, out)
    }
}
