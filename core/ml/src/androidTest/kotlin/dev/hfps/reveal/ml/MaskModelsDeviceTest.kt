package dev.hfps.reveal.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.ml.runtime.Backend
import java.util.Locale
import kotlin.math.abs
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every bundled model on public-domain photos (docs/adr/0021): it loads from the APK, compiles on the GPU (and the
 * CPU), finds what it should where it should, and the GPU agrees with the CPU.
 */
@RunWith(AndroidJUnit4::class)
class MaskModelsDeviceTest {
    /** Mean of [mask] over the normalised box [x0, x1) x [y0, y1). */
    private fun Mask.meanIn(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        var s = 0.0
        var n = 0
        for (y in (y0 * height).toInt() until (y1 * height).toInt()) {
            for (x in (x0 * width).toInt() until (x1 * width).toInt()) {
                s += this[x, y]
                n++
            }
        }
        return (s / n).toFloat()
    }

    private val portrait by lazy { Fixtures.image("studio-portrait") }
    private val group by lazy { Fixtures.image("outdoor-group") }
    private val landscape by lazy { Fixtures.image("landscape") }

    @Test
    fun everyBundledModelIsInTheApk() {
        for (spec in MaskModels.bundled) assertWithMessage(spec.asset).that(Fixtures.bundled(spec)).isTrue()
    }

    @Test
    fun sky() {
        for (spec in listOf(MaskModels.DEEPLAB_ADE20K)) {
            Fixtures.load(spec, Backend.GPU).use { model ->
                val sky = model.segment(group).masks.getValue(MaskKind.SKY)
                assertWithMessage("${spec.id} sky top-left").that(sky.meanIn(0f, 0f, 0.2f, 0.07f)).isGreaterThan(0.7f)
                assertWithMessage("${spec.id} ground").that(sky.meanIn(0f, 0.85f, 1f, 1f)).isLessThan(0.05f)
                val overcast = model.segment(landscape).masks.getValue(MaskKind.SKY)
                assertWithMessage("${spec.id} overcast").that(overcast.meanIn(0f, 0f, 1f, 0.1f)).isGreaterThan(0.6f)
                assertWithMessage("${spec.id} sand").that(overcast.meanIn(0f, 0.5f, 1f, 1f)).isLessThan(0.05f)
            }
        }
    }

    @Test
    fun people() {
        val models = listOf(
            MaskModels.SELFIE_MULTICLASS,
            MaskModels.LRASPP_VOC,
            MaskModels.SELFIE_SEGMENTER,
            MaskModels.MODNET,
        )
        for (spec in models) {
            Fixtures.load(spec, Backend.GPU).use { model ->
                val people = model.segment(portrait).masks.getValue(MaskKind.PEOPLE)
                assertWithMessage("${spec.id} face").that(people.meanIn(0.38f, 0.32f, 0.52f, 0.45f)).isGreaterThan(0.8f)
                assertWithMessage("${spec.id} backdrop").that(people.meanIn(0.8f, 0.05f, 0.98f, 0.3f)).isLessThan(0.1f)
                val empty = model.segment(landscape).masks.getValue(MaskKind.PEOPLE)
                assertWithMessage("${spec.id} no people").that(empty.mean()).isLessThan(0.05f)
            }
        }
        // The general models also find a group of small people outdoors.
        for (spec in listOf(MaskModels.SELFIE_MULTICLASS, MaskModels.LRASPP_VOC)) {
            Fixtures.load(spec, Backend.GPU).use { model ->
                val people = model.segment(group).masks.getValue(MaskKind.PEOPLE)
                assertWithMessage("${spec.id} group").that(people.meanIn(0.25f, 0.35f, 0.8f, 0.7f)).isGreaterThan(0.3f)
                assertWithMessage("${spec.id} sky").that(people.meanIn(0f, 0f, 1f, 0.1f)).isLessThan(0.05f)
            }
        }
    }

    @Test
    fun peopleParts() {
        Fixtures.load(MaskModels.SELFIE_MULTICLASS, Backend.GPU).use { model ->
            val m = model.segment(portrait).masks
            assertThat(m.getValue(MaskKind.HAIR).meanIn(0.33f, 0.18f, 0.53f, 0.24f)).isGreaterThan(0.6f)
            assertThat(m.getValue(MaskKind.FACE_SKIN).meanIn(0.4f, 0.33f, 0.5f, 0.43f)).isGreaterThan(0.6f)
            assertThat(m.getValue(MaskKind.CLOTHES).meanIn(0.35f, 0.75f, 0.6f, 0.85f)).isGreaterThan(0.5f)
            assertThat(m.getValue(MaskKind.HAIR).meanIn(0.4f, 0.33f, 0.5f, 0.43f)).isLessThan(0.2f)
        }
        Fixtures.load(MaskModels.HAIR_SEGMENTER, Backend.GPU).use { model ->
            val hair = model.segment(portrait).masks.getValue(MaskKind.HAIR)
            assertThat(hair.meanIn(0.33f, 0.18f, 0.53f, 0.24f)).isGreaterThan(0.6f)
            assertThat(hair.meanIn(0.4f, 0.33f, 0.5f, 0.43f)).isLessThan(0.2f)
        }
    }

    @Test
    fun tapSelectsTheObjectUnderTheFinger() {
        Fixtures.load(MaskModels.MAGIC_TOUCH, Backend.GPU).use { model ->
            val subject = model.segment(portrait, MaskPrompt(0.45f, 0.6f)).masks.getValue(MaskKind.SUBJECT)
            assertThat(subject.meanIn(0.4f, 0.55f, 0.5f, 0.65f)).isGreaterThan(0.7f)
            assertThat(subject.meanIn(0.8f, 0.05f, 0.98f, 0.3f)).isLessThan(0.1f)
        }
    }

    @Test
    fun gpuMatchesCpu() {
        for (spec in MaskModels.bundled) {
            val prompt = if (spec.interactive) MaskPrompt(0.45f, 0.6f) else null
            val gpu = Fixtures.load(spec, Backend.GPU).use { it.segment(portrait, prompt) }
            val cpu = Fixtures.load(spec, Backend.CPU).use { it.segment(portrait, prompt) }
            for ((kind, g) in gpu.masks) {
                val c = cpu.masks.getValue(kind)
                val diff = g.values.indices.sumOf { abs(g.values[it] - c.values[it]).toDouble() } / g.values.size
                Log.i(TAG, "parity ${spec.id} $kind mean |gpu-cpu| = ${"%.4f".format(Locale.ROOT, diff)}")
                assertWithMessage("${spec.id} $kind").that(diff).isLessThan(0.02)
            }
        }
    }

    private companion object {
        const val TAG = "REVEAL_ML"
    }
}
