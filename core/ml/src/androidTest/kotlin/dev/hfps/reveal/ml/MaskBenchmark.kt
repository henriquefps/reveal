package dev.hfps.reveal.ml

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.ml.face.FaceLandmarker
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.ml.service.PhotoCues
import java.io.File
import java.util.Locale
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Speed and memory of every mask model on the device (docs/adr/0021 table): per backend, the cold cost (compile +
 * first run), the warm median of [RUNS] runs (inference only and with pre/post-processing), and the memory the
 * loaded model adds (PSS and native heap). One line per model and backend, tag `REVEAL_ML`:
 *
 *   adb shell am instrument -w -e class dev.hfps.reveal.ml.MaskBenchmark \
 *     dev.hfps.reveal.ml.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Models that are not bundled (IS-Net) run when their file is in the test app's `files/models` (adb push + run-as).
 */
@RunWith(AndroidJUnit4::class)
class MaskBenchmark {
    @Test
    fun everyModel() {
        val image = Fixtures.image("outdoor-group")
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "models")
        for (spec in MaskModels.all) {
            val file = File(dir, spec.asset)
            val source = when {
                Fixtures.bundled(spec) -> LiteRtEngine.Source.Asset(
                    InstrumentationRegistry.getInstrumentation().targetContext.assets,
                    MaskModels.assetPath(spec),
                )

                file.exists() -> LiteRtEngine.Source.File(file.path)

                else -> {
                    Log.i(TAG, "${spec.id}: not bundled and no files/models/${spec.asset}, skipped")
                    continue
                }
            }
            for (backend in listOf(Backend.GPU, Backend.CPU)) measure(spec, source, backend, image)
        }
    }

    @Suppress("ExplicitGarbageCollectionCall") // the memory delta of one model needs a settled heap
    private fun measure(spec: ModelSpec, source: LiteRtEngine.Source, backend: Backend, image: MlImage) {
        Runtime.getRuntime().gc()
        val pss0 = Debug.getPss()
        val native0 = Debug.getNativeHeapAllocatedSize()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val engine = try {
            LiteRtEngine.load(source, listOf(backend))
        } catch (e: IllegalStateException) {
            Log.i(TAG, "${spec.id} $backend: does not compile (${e.cause?.message})")
            return
        }
        val prompt = if (spec.interactive) MaskPrompt(0.5f, 0.5f) else null
        SegmentationModel(spec, engine).use { model ->
            val t1 = SystemClock.elapsedRealtimeNanos()
            model.segment(image, prompt)
            val t2 = SystemClock.elapsedRealtimeNanos()
            val runs = (0 until RUNS).map { model.segment(image, prompt) }
            val pss = Debug.getPss() - pss0
            val native = (Debug.getNativeHeapAllocatedSize() - native0) / MIB
            val inference = runs.map { it.inferenceMs }.sorted()[RUNS / 2]
            val total = runs.map { it.totalMs }.sorted()[RUNS / 2]
            Log.i(
                TAG,
                "bench ${spec.id} $backend load=${ms(
                    t1 - t0,
                )} first=${ms(t2 - t1)} warmInference=${"%.1f".format(Locale.ROOT, inference)}" +
                    " warmTotal=${"%.1f".format(Locale.ROOT, total)} pssDeltaMiB=${pss / KIB} nativeDeltaMiB=$native" +
                    " input=${spec.input.width}x${spec.input.height}",
            )
        }
    }

    /** The face landmark model per backend: load, first run, warm median of one crop (`bench face-landmarker`). */
    @Test
    fun faceLandmarker() {
        val image = Fixtures.face("studio-trio")
        val crops = PhotoCues.of(image).faces.map { it.crop(image.width, image.height) }
        check(crops.isNotEmpty()) { "no face in the fixture" }
        for (backend in listOf(Backend.GPU, Backend.CPU)) {
            val t0 = SystemClock.elapsedRealtimeNanos()
            val engine = try {
                Fixtures.engine(MaskModels.FACE_LANDMARKER, backend)
            } catch (e: IllegalStateException) {
                Log.i(TAG, "face-landmarker $backend: does not compile (${e.cause?.message})")
                continue
            }
            FaceLandmarker(engine).use { lm ->
                val t1 = SystemClock.elapsedRealtimeNanos()
                lm.landmarks(image, crops[0])
                val t2 = SystemClock.elapsedRealtimeNanos()
                val runs = (0 until RUNS).map {
                    val a = SystemClock.elapsedRealtimeNanos()
                    lm.landmarks(image, crops[0])
                    SystemClock.elapsedRealtimeNanos() - a
                }.sorted()
                Log.i(
                    TAG,
                    "bench face-landmarker $backend load=${ms(t1 - t0)} first=${ms(t2 - t1)} " +
                        "warmPerCrop=${ms(runs[RUNS / 2])} faces=${crops.size}",
                )
            }
        }
    }

    private fun ms(nanos: Long) = "%.0f".format(Locale.ROOT, nanos / 1e6)

    private companion object {
        const val TAG = "REVEAL_ML"
        const val RUNS = 10
        const val KIB = 1024
        const val MIB = 1024 * 1024
    }
}
