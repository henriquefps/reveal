package dev.hfps.reveal.ml

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import org.junit.Assert.assertThrows
import org.junit.Test

class SegmentationModelTest {
    /** Returns the class logits it is given, and remembers its input. */
    private class FakeEngine(val output: FloatArray) : InferenceEngine {
        var input: FloatArray? = null
        var closed = false
        override val backend = Backend.CPU

        override fun runAll(input: FloatArray): List<FloatArray> = listOf(output.also { this.input = input })

        override fun close() {
            closed = true
        }
    }

    private val image = MlImage(4, 4, FloatArray(48) { 0.5f })

    @Test
    fun `multiclass outputs map to people and parts`() {
        val spec = MaskModels.SELFIE_MULTICLASS
        val n = spec.output.width * spec.output.height
        // Every pixel: class 3 (face skin) clearly wins.
        val logits = FloatArray(n * 6) { if (it % 6 == 3) 10f else 0f }
        val engine = FakeEngine(logits)
        SegmentationModel(spec, engine).use { model ->
            val r = model.segment(image)
            assertThat(engine.input!!.size).isEqualTo(spec.input.size)
            assertThat(r.masks.keys).containsExactly(
                MaskKind.PEOPLE,
                MaskKind.HAIR,
                MaskKind.BODY_SKIN,
                MaskKind.FACE_SKIN,
                MaskKind.SKIN,
                MaskKind.CLOTHES,
            )
            assertThat(r.masks.getValue(MaskKind.PEOPLE).mean()).isGreaterThan(0.99f)
            assertThat(r.masks.getValue(MaskKind.FACE_SKIN).mean()).isGreaterThan(0.99f)
            assertThat(r.masks.getValue(MaskKind.SKIN).mean()).isGreaterThan(0.99f)
            assertThat(r.masks.getValue(MaskKind.HAIR).mean()).isLessThan(0.01f)
            assertThat(r.masks.getValue(MaskKind.HAIR).width).isEqualTo(256)
        }
        assertThat(engine.closed).isTrue()
    }

    @Test
    fun `interactive models need a prompt, others refuse one`() {
        val magic = SegmentationModel(MaskModels.MAGIC_TOUCH, FakeEngine(FloatArray(512 * 512)))
        assertThrows(IllegalArgumentException::class.java) { magic.segment(image) }
        val sky = SegmentationModel(MaskModels.DEEPLAB_ADE20K, FakeEngine(FloatArray(513 * 513 * 151)))
        assertThrows(IllegalArgumentException::class.java) { sky.segment(image, MaskPrompt(0.5f, 0.5f)) }
    }

    @Test
    fun `the prompt is a small disc at the tapped point`() {
        val engine = FakeEngine(FloatArray(512 * 512) { 0.7f })
        val model = SegmentationModel(MaskModels.MAGIC_TOUCH, engine)
        val r = model.segment(image, MaskPrompt(0f, 1f))
        val input = engine.input!!
        // NHWC with 4 channels: the prompt is channel 3; bottom-left corner.
        assertThat(input[(511 * 512) * 4 + 3]).isEqualTo(1f)
        assertThat(input[(0 * 512 + 511) * 4 + 3]).isEqualTo(0f)
        assertThat((0 until 512 * 512).count { input[it * 4 + 3] == 1f }).isIn(10..40)
        assertThat(r.masks.getValue(MaskKind.SUBJECT).mean()).isWithin(1e-6f).of(0.7f)
    }

    @Test
    fun `kind rules sum and invert classes`() {
        val planes = arrayOf(floatArrayOf(0.2f), floatArrayOf(0.3f), floatArrayOf(0.5f))
        assertThat(KindRule(listOf(1, 2)).of(planes)[0]).isWithin(1e-6f).of(0.8f)
        assertThat(KindRule(listOf(0), inverted = true).of(planes)[0]).isWithin(1e-6f).of(0.8f)
        assertThat(KindRule(listOf(2), low = 0.1f, high = 0.9f).of(planes)[0]).isWithin(1e-6f).of(0.5f)
        assertThat(KindRule(listOf(1, 2), low = 0.1f, high = 0.7f).of(planes)[0]).isEqualTo(1f)
    }

    @Test
    fun `masks resize and invert`() {
        val m = Mask(2, 1, floatArrayOf(0f, 1f))
        assertThat(m.inverted().values.toList()).containsExactly(1f, 0f).inOrder()
        assertThat(m.resized(4, 1).values.toList()).containsExactly(0f, 0.25f, 0.75f, 1f).inOrder()
        assertThat(m.mean()).isEqualTo(0.5f)
    }
}
