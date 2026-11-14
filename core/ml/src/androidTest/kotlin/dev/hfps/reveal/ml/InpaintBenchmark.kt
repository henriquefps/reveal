@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litert.Accelerator
import com.google.ai.edge.litert.CompiledModel
import com.google.ai.edge.litert.Environment
import com.google.ai.edge.litert.LiteRtException
import java.io.File
import java.util.Locale
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * M5/R spike (docs/adr/0024): speed, memory and GPU/CPU parity of inpainting candidates on the device. The files are
 * not bundled: push them into the test app's `files/models` (run-as dev.hfps.reveal.ml.test). One line per file and
 * backend, tag `REVEAL_ML`:
 *
 *   adb shell am instrument -w -e class dev.hfps.reveal.ml.InpaintBenchmark \
 *     dev.hfps.reveal.ml.test/androidx.test.runner.AndroidJUnitRunner
 *
 * Layouts: `big-lama*` = one NHWC input (rgb x (1 - mask), mask) in 0..1, output NHWC 0..1; `migan*` = one NCHW
 * input concat(keep - 0.5, rgb x keep) in -1..1, output NCHW -1..1; `lama*` = NHWC image
 * 0..1 + NHWC mask (1 = fill), output NHWC 0..1. "crop1024" adds what a 1024 px crop costs around the model: an area
 * downscale to 512, the model, a bilinear upscale back and the feathered blend, on the CPU in Kotlin.
 */
@RunWith(AndroidJUnit4::class)
class InpaintBenchmark {
    @Test
    fun candidates() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "models")
        val files = dir.listFiles { f -> f.name.endsWith(".tflite") }?.sortedBy { it.name }.orEmpty()
        if (files.isEmpty()) Log.i(TAG, "inpaint: no candidate in ${dir.path}, nothing measured")
        val photo = Fixtures.image("landscape")
        for (file in files) {
            val outputs = mutableMapOf<String, FloatArray>()
            for (backend in listOf("GPU", "CPU")) measure(file, backend, photo)?.let { outputs[backend] = it }
            val gpu = outputs["GPU"]
            val cpu = outputs["CPU"]
            if (gpu != null && cpu != null) parity(file.name, gpu, cpu)
        }
    }

    private fun parity(name: String, gpu: FloatArray, cpu: FloatArray) {
        var sum = 0.0
        var max = 0f
        for (i in gpu.indices) {
            val d = abs(gpu[i] - cpu[i])
            sum += d
            if (d > max) max = d
        }
        Log.i(TAG, "inpaint parity $name meanAbs=${f4(sum / gpu.size)} maxAbs=${f4(max.toDouble())}")
    }

    // the memory delta of one model needs a settled heap
    @Suppress("ExplicitGarbageCollectionCall", "LongMethod", "CyclomaticComplexMethod")
    private fun measure(file: File, backend: String, photo: MlImage): FloatArray? {
        Runtime.getRuntime().gc()
        val pss0 = Debug.getPss()
        val t0 = SystemClock.elapsedRealtimeNanos()
        val accelerators = if (backend == "GPU") setOf(Accelerator.GPU, Accelerator.CPU) else setOf(Accelerator.CPU)
        val model = try {
            val options = CompiledModel.Options(accelerators)
            options.cpuOptions = CompiledModel.CpuOptions(4, null, null)
            CompiledModel.create(file.path, options, environment)
        } catch (e: LiteRtException) {
            Log.i(TAG, "inpaint ${file.name} $backend: does not compile (${e.message})")
            return null
        }
        val inputs = model.createInputBuffers()
        val outputs = model.createOutputBuffers()
        val lama = file.name.startsWith("lama")
        val big = file.name.startsWith("big-lama")
        val (rgb, hole) = sample(photo)
        val feed = when {
            big -> listOf(bigLamaInput(rgb, hole))
            lama -> listOf(rgb, hole)
            else -> listOf(miganInput(rgb, hole))
        }
        fun run(): FloatArray {
            feed.forEachIndexed { i, a -> inputs[i].writeFloat(a) }
            model.run(inputs, outputs)
            return outputs[0].readFloat()
        }
        try {
            val t1 = SystemClock.elapsedRealtimeNanos()
            val first = run()
            val t2 = SystemClock.elapsedRealtimeNanos()
            val warm = (0 until RUNS).map {
                val a = SystemClock.elapsedRealtimeNanos()
                run()
                SystemClock.elapsedRealtimeNanos() - a
            }.sorted()
            val pss = Debug.getPss() - pss0
            // a 1024 px crop around a hole: downscale, run, upscale, blend
            val large = upscale2(rgb)
            val bigHole = upscale2(hole, 1)
            val crop = (0 until 5).map {
                val a = SystemClock.elapsedRealtimeNanos()
                val small = downscale2(large)
                val smallHole = downscale2(bigHole, 1)
                feed.forEachIndexed { i, _ ->
                    inputs[i].writeFloat(
                        when {
                            big -> bigLamaInput(small, smallHole)
                            lama && i == 0 -> small
                            lama -> smallHole
                            else -> miganInput(small, smallHole)
                        },
                    )
                }
                model.run(inputs, outputs)
                val out = outputs[0].readFloat()
                blend(large, upscale2(if (lama || big) out else nhwc(out)), bigHole)
                SystemClock.elapsedRealtimeNanos() - a
            }.sorted()
            Log.i(
                TAG,
                "inpaint bench ${file.name} $backend load=${ms(t1 - t0)} first=${ms(t2 - t1)} " +
                    "warm=${ms(warm[RUNS / 2])} warmMin=${ms(warm[0])} crop1024=${ms(crop[2])} " +
                    "pssDeltaMiB=${pss / 1024} sizeMiB=${file.length() / (1024 * 1024)}",
            )
            return if (lama || big) first else nhwc(first)
        } finally {
            inputs.forEach { it.close() }
            outputs.forEach { it.close() }
            model.close()
        }
    }

    /** The fixture's centre 512 x 512 (nearest, enough for timing) and a central ellipse hole. */
    private fun sample(photo: MlImage): Pair<FloatArray, FloatArray> {
        val rgb = FloatArray(S * S * 3)
        val hole = FloatArray(S * S)
        val s = minOf(photo.width, photo.height) / S.toFloat()
        val ox = (photo.width - S * s) / 2
        val oy = (photo.height - S * s) / 2
        for (y in 0 until S) {
            for (x in 0 until S) {
                val sx = (ox + x * s).toInt().coerceIn(0, photo.width - 1)
                val sy = (oy + y * s).toInt().coerceIn(0, photo.height - 1)
                for (c in 0 until 3) rgb[(y * S + x) * 3 + c] = photo.rgb[(sy * photo.width + sx) * 3 + c]
                val dx = (x - S / 2) / (S * 0.18f)
                val dy = (y - S / 2) / (S * 0.3f)
                hole[y * S + x] = if (dx * dx + dy * dy <= 1f) 1f else 0f
            }
        }
        return rgb to hole
    }

    /** big-LaMa (docs/adr/0024): NHWC (rgb x (1 - mask), mask) in 0..1. */
    private fun bigLamaInput(rgb: FloatArray, hole: FloatArray): FloatArray {
        val out = FloatArray(4 * S * S)
        for (i in 0 until S * S) {
            val keep = 1f - hole[i]
            for (c in 0 until 3) out[i * 4 + c] = rgb[i * 3 + c] * keep
            out[i * 4 + 3] = hole[i]
        }
        return out
    }

    private fun miganInput(rgb: FloatArray, hole: FloatArray): FloatArray {
        val out = FloatArray(4 * S * S)
        for (i in 0 until S * S) {
            val keep = 1f - hole[i]
            out[i] = keep - 0.5f
            for (c in 0 until 3) out[(c + 1) * S * S + i] = (rgb[i * 3 + c] * 2f - 1f) * keep
        }
        return out
    }

    /** NCHW -1..1 to NHWC 0..1. */
    private fun nhwc(a: FloatArray): FloatArray {
        val out = FloatArray(S * S * 3)
        for (i in 0 until S * S) for (c in 0 until 3) out[i * 3 + c] = (a[c * S * S + i] + 1f) / 2f
        return out
    }

    private fun upscale2(a: FloatArray, ch: Int = 3): FloatArray {
        val n = S * 2
        val out = FloatArray(n * n * ch)
        for (y in 0 until n) {
            val fy = ((y + 0.5f) / 2f - 0.5f).coerceIn(0f, S - 1f)
            val y0 = fy.toInt()
            val y1 = minOf(y0 + 1, S - 1)
            val wy = fy - y0
            for (x in 0 until n) {
                val fx = ((x + 0.5f) / 2f - 0.5f).coerceIn(0f, S - 1f)
                val x0 = fx.toInt()
                val x1 = minOf(x0 + 1, S - 1)
                val wx = fx - x0
                for (c in 0 until ch) {
                    val a00 = a[(y0 * S + x0) * ch + c]
                    val a01 = a[(y0 * S + x1) * ch + c]
                    val a10 = a[(y1 * S + x0) * ch + c]
                    val a11 = a[(y1 * S + x1) * ch + c]
                    val top = a00 * (1 - wx) + a01 * wx
                    out[(y * n + x) * ch + c] = top * (1 - wy) + (a10 * (1 - wx) + a11 * wx) * wy
                }
            }
        }
        return out
    }

    private fun downscale2(a: FloatArray, ch: Int = 3): FloatArray {
        val n = S * 2
        val out = FloatArray(S * S * ch)
        for (y in 0 until S) {
            for (x in 0 until S) {
                for (c in 0 until ch) {
                    out[(y * S + x) * ch + c] = (
                        a[(2 * y * n + 2 * x) * ch + c] + a[(2 * y * n + 2 * x + 1) * ch + c] +
                            a[((2 * y + 1) * n + 2 * x) * ch + c] + a[((2 * y + 1) * n + 2 * x + 1) * ch + c]
                        ) / 4f
                }
            }
        }
        return out
    }

    private fun blend(base: FloatArray, fill: FloatArray, hole: FloatArray) {
        for (i in hole.indices) {
            val a = hole[i]
            if (a > 0f) for (c in 0 until 3) base[i * 3 + c] = base[i * 3 + c] * (1 - a) + fill[i * 3 + c] * a
        }
    }

    private fun ms(nanos: Long) = "%.1f".format(Locale.ROOT, nanos / 1e6)

    private fun f4(v: Double) = "%.4f".format(Locale.ROOT, v)

    private companion object {
        const val TAG = "REVEAL_ML"
        const val RUNS = 10
        const val S = 512
        val environment: Environment by lazy { Environment.create() }
    }
}
