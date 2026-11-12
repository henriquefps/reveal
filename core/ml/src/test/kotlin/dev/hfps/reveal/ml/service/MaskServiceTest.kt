package dev.hfps.reveal.ml.service

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.MaskPrompt
import dev.hfps.reveal.ml.MlImage
import dev.hfps.reveal.ml.PinnedModel
import dev.hfps.reveal.ml.face.SyntheticFace
import dev.hfps.reveal.ml.refine.RefineDefaults
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.InferenceEngine
import java.nio.file.Files
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Test

class MaskServiceTest {
    /** Fake runtimes per model, counting runs and closes. */
    private inner class FakeLoader : EngineLoader {
        val runs = HashMap<String, Int>()
        val closed = HashMap<String, Int>()
        var onRun: (String) -> Unit = {}

        override fun load(model: PinnedModel): InferenceEngine = object : InferenceEngine {
            override val backend = Backend.CPU

            override fun runAll(input: FloatArray): List<FloatArray> {
                runs.merge(model.key, 1, Int::plus)
                onRun(model.key)
                return outputs(model, input)
            }

            override fun close() {
                closed.merge(model.key, 1, Int::plus)
            }
        }
    }

    private fun outputs(model: PinnedModel, input: FloatArray): List<FloatArray> = when (model) {
        // Selfie Multiclass: hair (class 1) on the left half, background (class 0) on the right.
        MaskModels.SELFIE_MULTICLASS -> listOf(
            FloatArray(256 * 256 * 6) { i ->
                val x = (i / 6) % 256
                val c = i % 6
                val winner = if (x < 128) 1 else 0
                if (c == winner) 10f else 0f
            },
        )

        // Sky: positive logits in the top half.
        MaskModels.DEEPLAB_ADE20K_SKY -> listOf(FloatArray(513 * 513) { if (it / 513 < 256) 6f else -6f })

        // MagicTouch: the prompt disc itself (the input's fourth channel).
        MaskModels.MAGIC_TOUCH -> listOf(FloatArray(512 * 512) { input[it * 4 + 3] })

        MaskModels.FACE_LANDMARKER -> {
            val f = SyntheticFace.landmarks(cx = 128f, cy = 128f, scale = 40f)
            listOf(
                FloatArray(478 * 3) {
                    if (it % 3 ==
                        0
                    ) {
                        f.x[it / 3]
                    } else if (it % 3 == 1) {
                        f.y[it / 3]
                    } else {
                        0f
                    }
                },
                floatArrayOf(5f),
            )
        }

        else -> error("no fake for ${model.key}")
    }

    private val image = MlImage(96, 64, FloatArray(96 * 64 * 3) { i -> ((i * 37) % 101) / 100f })
    private val face = FaceBox(0.3f, 0.2f, 0.7f, 0.8f, 0.4f, 0.4f, 0.6f, 0.4f)
    private var cueCalls = 0
    private val loader = FakeLoader()
    private val dir = Files.createTempDirectory("mask-service").toFile()

    private fun service(cache: MaskDiskCache? = MaskDiskCache(dir)) =
        MaskService(loader, cache, Dispatchers.Unconfined) {
            cueCalls++
            PhotoCues(listOf(face), null)
        }

    private fun request(
        kind: MaskKind,
        vararg prompts: MaskPrompt,
        refine: RefineDefaults.Setting? = RefineDefaults.of(kind),
    ) = MaskRequest("photo-1", image, kind, prompts = prompts.toList(), refine = refine)

    @Test
    fun `one Selfie Multiclass run serves all of its kinds`() = runBlocking {
        val s = service()
        val hair = s.mask(request(MaskKind.HAIR))
        assertThat(hair.model).isEqualTo("selfie-multiclass@2")
        assertThat(hair.mask.width).isEqualTo(96)
        assertThat(hair.mask[10, 30]).isGreaterThan(0.9f)
        assertThat(hair.mask[90, 30]).isLessThan(0.1f)
        val people = s.mask(request(MaskKind.PEOPLE))
        assertThat(people.mask[10, 30]).isGreaterThan(0.9f)
        assertThat(people.mask[90, 30]).isLessThan(0.1f)
        assertThat(loader.runs["selfie-multiclass@2"]).isEqualTo(1)
    }

    @Test
    fun `a repeated request comes from the cache with the same pixels`() = runBlocking {
        val s = service()
        val first = s.mask(request(MaskKind.SKY))
        val second = s.mask(request(MaskKind.SKY))
        assertThat(first.fromCache).isFalse()
        assertThat(second.fromCache).isTrue()
        assertThat(second.mask.values.toList()).isEqualTo(first.mask.values.toList())
        assertThat(loader.runs["deeplab-v3-ade20k-sky@1"]).isEqualTo(1)
        // A new service on the same directory (the app restarted) still hits.
        val third = service().mask(request(MaskKind.SKY))
        assertThat(third.fromCache).isTrue()
        assertThat(loader.runs["deeplab-v3-ade20k-sky@1"]).isEqualTo(1)
    }

    @Test
    fun `changing the refinement never re-runs the model`() = runBlocking {
        val s = service()
        val refined = s.mask(request(MaskKind.SKY))
        val plain = s.mask(request(MaskKind.SKY, refine = null))
        val wide = s.mask(request(MaskKind.SKY, refine = RefineDefaults.Setting(radius = 2, eps = 1e-2f)))
        assertThat(loader.runs["deeplab-v3-ade20k-sky@1"]).isEqualTo(1)
        assertThat(plain.refine).isNull()
        assertThat(wide.mask.values.toList()).isNotEqualTo(refined.mask.values.toList())
        // Top half sky, bottom not, whatever the refinement.
        for (m in listOf(refined, plain, wide)) {
            assertThat(m.mask[48, 5]).isGreaterThan(0.9f)
            assertThat(m.mask[48, 60]).isLessThan(0.1f)
        }
    }

    @Test
    fun `without a cache every request runs the model`() = runBlocking {
        val s = service(cache = null)
        s.mask(request(MaskKind.SKY))
        s.mask(request(MaskKind.SKY))
        assertThat(loader.runs["deeplab-v3-ade20k-sky@1"]).isEqualTo(2)
    }

    @Test
    fun `the subject unites MagicTouch at the face, the body below it and the taps`() = runBlocking {
        val s = service()
        val out = s.mask(request(MaskKind.SUBJECT, MaskPrompt(0.1f, 0.1f)))
        // Two automatic seeds (the face centre, the body two face heights below: outside the frame here, dropped)
        // plus the tap.
        assertThat(loader.runs["magic-touch@1"]).isEqualTo(2)
        assertThat(cueCalls).isEqualTo(1)
        assertThat(out.raw[256, 256]).isEqualTo(1f) // the face centre's disc, at the model's resolution
        assertThat(out.raw[(0.1f * 511).toInt(), (0.1f * 511).toInt()]).isEqualTo(1f)
        assertThat(out.raw[400, 100]).isEqualTo(0f)
    }

    @Test
    fun `the background is the subject inverted, refined or not`() = runBlocking {
        val s = service()
        val subject = s.mask(request(MaskKind.SUBJECT))
        val background = s.mask(request(MaskKind.BACKGROUND))
        assertThat(background.kind).isEqualTo(MaskKind.BACKGROUND)
        assertThat(background.fromCache).isTrue()
        for (i in subject.mask.values.indices) {
            assertThat(abs(background.mask.values[i] - (1f - subject.mask.values[i]))).isLessThan(1e-6f)
        }
    }

    @Test
    fun `the subject needs seeds`() {
        val s = service()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.mask(MaskRequest("p", image, MaskKind.SUBJECT, autoSeeds = false)) }
        }
    }

    @Test
    fun `face parts come from the landmarks of every face`() = runBlocking {
        val s = service()
        val eyes = s.mask(request(MaskKind.EYES))
        assertThat(eyes.model).isEqualTo("face-landmarker@1")
        assertThat(eyes.refine).isNull()
        assertThat(eyes.mask.mean()).isGreaterThan(0f)
        // Two passes per face (detection crop, then tracked crop).
        assertThat(loader.runs["face-landmarker@1"]).isEqualTo(2)
        val lips = s.mask(request(MaskKind.LIPS))
        assertThat(lips.mask.mean()).isGreaterThan(0f)
        // The same photo's landmarks serve every part.
        assertThat(loader.runs["face-landmarker@1"]).isEqualTo(2)
        // The landmark model stays loaded next to a segmentation model.
        s.mask(request(MaskKind.SKY))
        s.mask(request(MaskKind.TEETH))
        assertThat(loader.closed["face-landmarker@1"]).isNull()
    }

    @Test
    fun `one segmentation model in memory at a time`() = runBlocking {
        val s = service()
        s.mask(request(MaskKind.HAIR))
        s.mask(request(MaskKind.SKY))
        assertThat(loader.closed["selfie-multiclass@2"]).isEqualTo(1)
        s.release()
        assertThat(loader.closed["deeplab-v3-ade20k-sky@1"]).isEqualTo(1)
    }

    @Test
    fun `a recipe's model must make the kind`() {
        val s = service()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.mask(MaskRequest("p", image, MaskKind.SKY, model = "selfie-multiclass@2")) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { s.mask(MaskRequest("p", image, MaskKind.SKY, model = "nothing@1")) }
        }
        val ok = runBlocking { s.mask(MaskRequest("p", image, MaskKind.HAIR, model = "selfie-multiclass@2")) }
        assertThat(ok.model).isEqualTo("selfie-multiclass@2")
    }

    @Test
    fun `the model input is at most 1024 px`() {
        val big = MlImage(1100, 10, FloatArray(1100 * 10 * 3))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { service().mask(MaskRequest("p", big, MaskKind.SKY)) }
        }
    }

    @Test
    fun `cancelling stops between the seeds`() = runBlocking {
        val s = service(cache = null)
        val job =
            async(Dispatchers.Unconfined, start = CoroutineStart.LAZY) {
                s.mask(request(MaskKind.SUBJECT, MaskPrompt(0.1f, 0.1f)))
            }
        loader.onRun = { job.cancel() }
        val result = runCatching { job.await() }
        assertThat(result.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(loader.runs["magic-touch@1"] ?: 0).isAtMost(1)
    }

    @Test
    fun `cache keys separate photos, models, kinds, inputs, seeds and refinements`() {
        val base = request(MaskKind.SUBJECT, MaskPrompt(0.25f, 0.5f))
        val key = MaskService.rawKey(base, MaskModels.MAGIC_TOUCH, MaskKind.SUBJECT)
        assertThat(key).contains("photo-1")
        assertThat(key).contains("magic-touch@1")
        assertThat(key).contains("0.2500,0.5000")
        val other = MaskRequest("photo-2", image, MaskKind.SUBJECT, prompts = base.prompts)
        assertThat(MaskService.rawKey(other, MaskModels.MAGIC_TOUCH, MaskKind.SUBJECT)).isNotEqualTo(key)
        val v2 = MaskRequest("photo-1", image, MaskKind.SUBJECT, inputVersion = 2, prompts = base.prompts)
        assertThat(MaskService.rawKey(v2, MaskModels.MAGIC_TOUCH, MaskKind.SUBJECT)).isNotEqualTo(key)
        assertThat(MaskService.refinedKey(key, RefineDefaults.HAIR))
            .isNotEqualTo(MaskService.refinedKey(key, RefineDefaults.DEFAULT))
        // A non-interactive model ignores taps in its key.
        val sky = MaskService.rawKey(
            request(MaskKind.SKY, MaskPrompt(0.1f, 0.1f)),
            MaskModels.DEEPLAB_ADE20K_SKY,
            MaskKind.SKY,
        )
        assertThat(
            sky,
        ).isEqualTo(MaskService.rawKey(request(MaskKind.SKY), MaskModels.DEEPLAB_ADE20K_SKY, MaskKind.SKY))
    }
}
