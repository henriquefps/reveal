package dev.hfps.reveal.data.mask

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.PinnedModel
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import dev.hfps.reveal.ml.service.EngineLoader
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.mask.AiMaskRequest
import dev.hfps.reveal.pipeline.recipe.AiRefine
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskPoint
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test

/** The editor's AI masks (docs/adr/0022 "M4/B (editor)"): the adapter from MaskService to the engine's source. */
class AiMasksTest {
    /** Sky: positive logits in the top half of DeepLab's 513 x 513 output; anything else fails. */
    private class Loader(private val fail: Boolean = false) : EngineLoader {
        var loads = 0

        override fun load(model: PinnedModel): InferenceEngine {
            loads++
            check(!fail) { "no GPU memory" }
            return object : InferenceEngine {
                override val backend = Backend.CPU

                override fun runAll(input: FloatArray): List<FloatArray> = when (model) {
                    MaskModels.DEEPLAB_ADE20K_SKY -> listOf(FloatArray(513 * 513) { if (it / 513 < 256) 6f else -6f })
                    else -> error("no fake for ${model.key}")
                }

                override fun close() = Unit
            }
        }
    }

    private val image = MlImage(64, 48, FloatArray(64 * 48 * 3) { 0.5f })
    private val sky = MaskComponent.Ai(model = "deeplab-v3-ade20k-sky@1", kind = "SKY")

    private fun request(c: MaskComponent.Ai) = AiMaskRequest("editor", c, 640, 480)

    @Test
    fun `a mask is computed once in the background, then served from memory`() = runBlocking {
        val loader = Loader()
        val session = AiMasks(MaskService(loader)).session { image }
        val ready = CountDownLatch(2)
        assertThat(session.mask(request(sky)) { ready.countDown() }).isNull()
        assertThat(session.mask(request(sky)) { ready.countDown() }).isNull()
        assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue()
        val mask = checkNotNull(session.mask(request(sky)) {})
        assertThat(mask.width).isEqualTo(64)
        assertThat(mask.values[0]).isGreaterThan(0.9f)
        assertThat(mask.values.last()).isLessThan(0.1f)
        assertThat(session.states.value[AiMasks.key(sky)]).isEqualTo(AiMaskState.READY)
        assertThat(loader.loads).isEqualTo(1)
        session.close()
    }

    @Test
    fun `a kind or model this app does not have is unavailable, never a crash`() = runBlocking {
        val session = AiMasks(MaskService(Loader())).session { image }
        val newer = MaskComponent.Ai(model = "sky-net@9", kind = "SKY")
        val alien = MaskComponent.Ai(model = "x@1", kind = "TAIL")
        val told = CountDownLatch(1)
        assertThat(session.mask(request(newer)) { told.countDown() }).isNull()
        assertThat(session.mask(request(alien)) {}).isNull()
        assertThat(told.await(30, TimeUnit.SECONDS)).isTrue()
        val states = withTimeout(30_000) { session.states.first { it.size == 2 } }
        assertThat(states[AiMasks.key(newer)]).isEqualTo(AiMaskState.UNAVAILABLE)
        assertThat(states[AiMasks.key(alien)]).isEqualTo(AiMaskState.UNAVAILABLE)
        // Asked again: no new run.
        assertThat(session.mask(request(newer)) {}).isNull()
        session.close()
    }

    @Test
    fun `a model that fails leaves the mask failed until retried`() = runBlocking {
        val loader = Loader(fail = true)
        val session = AiMasks(MaskService(loader)).session { image }
        session.request(sky)
        assertThat(session.mask(request(sky)) {}).isNull()
        // The run may fail before or after mask() registers its callback: wait for the state, not the callback.
        val states = withTimeout(30_000) { session.states.first { it[AiMasks.key(sky)] == AiMaskState.FAILED } }
        assertThat(states[AiMasks.key(sky)]).isEqualTo(AiMaskState.FAILED)
        session.retry()
        assertThat(session.states.value[AiMasks.key(sky)]).isNull()
        session.close()
    }

    @Test
    fun `components map to requests with prompts, seeds, refinement and the model`() {
        val subject = MaskComponent.Ai(
            model = "",
            kind = "SUBJECT",
            prompts = listOf(MaskPoint(0.25f, 0.5f)),
            refine = AiRefine(7.6f, 0.002f),
            autoSeeds = false,
        )
        val r = AiMasks.request(subject, MaskKind.SUBJECT, image, "img:1")
        assertThat(r.model).isNull()
        assertThat(r.autoSeeds).isFalse()
        assertThat(r.prompts.single().x).isEqualTo(0.25f)
        assertThat(r.refine!!.radius).isEqualTo(8)
        assertThat(r.inputVersion).isEqualTo(MaskInputs.VERSION)
        // No taps: the automatic seeds whatever the flag says (the subject needs a seed).
        assertThat(AiMasks.request(subject.copy(prompts = emptyList()), MaskKind.SUBJECT, image).autoSeeds).isTrue()
        assertThat(AiMasks.request(subject.copy(refine = null), MaskKind.SUBJECT, image).refine).isNull()
        assertThat(AiMasks.kindOf(subject)).isEqualTo(MaskKind.SUBJECT)
        assertThat(AiMasks.kindOf(subject.copy(kind = "TAIL"))).isNull()
        // The key changes with everything that changes the mask, and not with the op or invert.
        assertThat(AiMasks.key(subject)).isNotEqualTo(AiMasks.key(subject.copy(prompts = emptyList())))
        assertThat(AiMasks.key(subject)).isNotEqualTo(AiMasks.key(subject.copy(autoSeeds = true)))
        assertThat(AiMasks.key(subject)).isEqualTo(AiMasks.key(subject.copy(invert = true)))
    }

    @Test
    fun `the model input is the recipe-independent render at most 1024 px, and its key is stable`() {
        assertThat(MaskInputs.size(6000, 4000)).isEqualTo(1024 to 683)
        assertThat(MaskInputs.size(800, 600)).isEqualTo(800 to 600)
        val input = SyntheticImages.scene(1500, 1000)
        val a = MaskInputs.render(input)
        val b = MaskInputs.render(input)
        assertThat(a.width).isEqualTo(1024)
        assertThat(a.height).isEqualTo(683)
        assertThat(a.rgb.all { it in 0f..1f }).isTrue()
        assertThat(MaskInputs.contentKey(a)).isEqualTo(MaskInputs.contentKey(b))
        assertThat(MaskInputs.contentKey(a)).startsWith("img:")
        val other = MaskInputs.render(SyntheticImages.scene(1500, 999))
        assertThat(MaskInputs.contentKey(other)).isNotEqualTo(MaskInputs.contentKey(a))
        assertThat(MaskInputs.downscale(input).width).isEqualTo(1024)
    }
}
