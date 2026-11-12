package dev.hfps.reveal.ml

import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.ml.runtime.Backend
import java.util.Locale
import kotlin.math.abs
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The converted sky model (`scripts/ml/convert_deeplab_sky.py`, docs/adr/0021 "M4/C") against the published DeepLab
 * ADE20K on LiteRT's CPU, on the public-domain fixtures: the same sky (IoU of the masks at 0.5 ≥ 0.99, mean absolute
 * difference ≤ 0.01). Logs `REVEAL_ML sky-conversion ...` per image with both models' CPU times.
 */
@RunWith(AndroidJUnit4::class)
class SkyConversionTest {
    @Test
    @Suppress("NestedBlockDepth")
    fun convertedSkyMatchesTheOriginal() {
        val images = listOf("outdoor-group", "landscape", "studio-portrait").map { it to Fixtures.image(it) } +
            ("studio-trio" to Fixtures.face("studio-trio"))
        Fixtures.load(MaskModels.DEEPLAB_ADE20K, Backend.CPU).use { original ->
            Fixtures.load(MaskModels.DEEPLAB_ADE20K_SKY, Backend.CPU).use { converted ->
                for ((name, image) in images) {
                    val t0 = SystemClock.elapsedRealtimeNanos()
                    val a = original.segment(image).masks.getValue(MaskKind.SKY)
                    val t1 = SystemClock.elapsedRealtimeNanos()
                    val b = converted.segment(image).masks.getValue(MaskKind.SKY)
                    val t2 = SystemClock.elapsedRealtimeNanos()
                    var inter = 0
                    var union = 0
                    var diff = 0.0
                    for (i in a.values.indices) {
                        val x = a.values[i] >= 0.5f
                        val y = b.values[i] >= 0.5f
                        if (x && y) inter++
                        if (x || y) union++
                        diff += abs(a.values[i] - b.values[i])
                    }
                    val iou = if (union == 0) 1.0 else inter.toDouble() / union
                    val mad = diff / a.values.size
                    Log.i(
                        TAG,
                        "sky-conversion $name sky=${f(a.mean())} iou=${f(iou)} meanAbsDiff=${f(mad)} " +
                            "cpuOriginal=${(t1 - t0) / NANOS} ms cpuConverted=${(t2 - t1) / NANOS} ms",
                    )
                    assertWithMessage("$name IoU").that(iou).isAtLeast(0.99)
                    assertWithMessage("$name mean |diff|").that(mad).isAtMost(0.01)
                }
            }
        }
    }

    private fun f(v: Number) = "%.4f".format(Locale.ROOT, v.toDouble())

    private companion object {
        const val TAG = "REVEAL_ML"
        const val NANOS = 1_000_000
    }
}
