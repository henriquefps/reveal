package dev.hfps.reveal.ml.tensor

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MlImage
import org.junit.Test

class TensorSpecTest {
    private val image = MlImage(2, 1, floatArrayOf(0f, 0.5f, 1f, 1f, 0.5f, 0f))

    @Test
    fun `NHWC keeps pixels together and NCHW makes planes`() {
        val nhwc = InputSpec(2, 1, Layout.NHWC, Normalisation.ZERO_ONE).encode(image)
        assertThat(nhwc.toList()).containsExactly(0f, 0.5f, 1f, 1f, 0.5f, 0f).inOrder()
        val nchw = InputSpec(2, 1, Layout.NCHW, Normalisation.ZERO_ONE).encode(image)
        assertThat(nchw.toList()).containsExactly(0f, 1f, 0.5f, 0.5f, 1f, 0f).inOrder()
    }

    @Test
    fun `normalisations`() {
        val m11 = InputSpec(2, 1, Layout.NHWC, Normalisation.MINUS_ONE_ONE).encode(image)
        assertThat(m11.toList()).containsExactly(-1f, 0f, 1f, 1f, 0f, -1f).inOrder()
        val centred = InputSpec(2, 1, Layout.NHWC, Normalisation.CENTRED).encode(image)
        assertThat(centred[0]).isEqualTo(-0.5f)
        val imagenet = InputSpec(2, 1, Layout.NHWC, Normalisation.IMAGENET).encode(image)
        assertThat(imagenet[0]).isWithin(1e-5f).of((0f - 0.485f) / 0.229f)
        assertThat(imagenet[1]).isWithin(1e-5f).of((0.5f - 0.456f) / 0.224f)
        val scaled = InputSpec(2, 1, Layout.NHWC, Normalisation.MAX_THEN_IMAGENET)
            .encode(MlImage(1, 1, floatArrayOf(0.5f, 0.25f, 0f)))
        assertThat(scaled[0]).isWithin(1e-5f).of((1f - 0.485f) / 0.229f)
    }

    @Test
    fun `the extra channel goes fourth`() {
        val spec = InputSpec(2, 1, Layout.NCHW, Normalisation.ZERO_ONE, extraChannel = true)
        val out = spec.encode(image, floatArrayOf(0.25f, 0.75f))
        assertThat(out.size).isEqualTo(8)
        assertThat(out.copyOfRange(6, 8).toList()).containsExactly(0.25f, 0.75f).inOrder()
        val nhwc = InputSpec(2, 1, Layout.NHWC, Normalisation.ZERO_ONE, extraChannel = true).encode(image)
        assertThat(nhwc[3]).isEqualTo(0f)
    }

    @Test
    fun `softmax gives probabilities per pixel`() {
        val spec = OutputSpec(2, 1, 3, Layout.NHWC, Activation.SOFTMAX)
        val planes = spec.decode(floatArrayOf(1f, 2f, 3f, 100f, 0f, -100f))
        for (i in 0 until 2) assertThat(planes.sumOf { it[i].toDouble() }).isWithin(1e-6).of(1.0)
        assertThat(planes[2][0]).isGreaterThan(planes[1][0])
        assertThat(planes[0][1]).isWithin(1e-6f).of(1f)
    }

    @Test
    fun `NCHW outputs, sigmoid and min-max`() {
        val sig = OutputSpec(2, 1, 1, Layout.NCHW, Activation.SIGMOID).decode(floatArrayOf(0f, 100f))
        assertThat(sig[0].toList()).containsExactly(0.5f, 1f).inOrder()
        val mm = OutputSpec(3, 1, 1, Layout.NCHW, Activation.MIN_MAX).decode(floatArrayOf(0.2f, 0.4f, 0.6f))
        assertThat(mm[0][1]).isWithin(1e-6f).of(0.5f)
        val planes = OutputSpec(1, 1, 2, Layout.NCHW, Activation.NONE).decode(floatArrayOf(1.5f, -0.5f))
        assertThat(planes[0][0]).isEqualTo(1f)
        assertThat(planes[1][0]).isEqualTo(0f)
    }
}
