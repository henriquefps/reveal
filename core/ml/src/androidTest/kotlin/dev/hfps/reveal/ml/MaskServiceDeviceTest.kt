package dev.hfps.reveal.ml

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.ml.runtime.LiteRtEngine
import dev.hfps.reveal.ml.service.EngineLoader
import dev.hfps.reveal.ml.service.MaskDiskCache
import dev.hfps.reveal.ml.service.MaskRequest
import dev.hfps.reveal.ml.service.MaskService
import java.io.File
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [MaskService] end to end on the device (docs/adr/0021 "M4/C"): every kind on a public-domain portrait from the
 * APK's models (`Fixtures.preferred` backend), refined, cached; the second pass comes from the disk cache. Logs
 * `REVEAL_ML service <kind> ...` with the model and refinement times.
 */
@RunWith(AndroidJUnit4::class)
class MaskServiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "mask-service-test").apply { deleteRecursively() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun everyKind() = runBlocking {
        val loader = EngineLoader { model ->
            LiteRtEngine.load(
                LiteRtEngine.Source.Asset(context.assets, MaskModels.assetPath(model)),
                listOf(Fixtures.preferred),
            )
        }
        val image = Fixtures.image("studio-portrait")
        MaskService(loader, MaskDiskCache(dir)).use { service ->
            for (pass in 1..2) {
                for (kind in MaskKind.entries) {
                    val out = service.mask(MaskRequest("fixture-studio-portrait", image, kind))
                    Log.i(
                        TAG,
                        "service pass=$pass $kind model=${out.model} backend=${out.backend} cache=${out.fromCache} " +
                            "model=${"%.0f".format(Locale.ROOT, out.modelMs)} ms " +
                            "refine=${"%.0f".format(
                                Locale.ROOT,
                                out.refineMs,
                            )} ms coverage=${"%.3f".format(Locale.ROOT, out.mask.mean())}",
                    )
                    assertThat(out.mask.width).isEqualTo(image.width)
                    assertThat(out.mask.height).isEqualTo(image.height)
                    // The background is the subject inverted: its first request already finds the subject cached.
                    val cached = pass == 2 || kind == MaskKind.BACKGROUND
                    assertWithMessage("$kind cached").that(out.fromCache).isEqualTo(cached)
                    if (kind != MaskKind.SKY) assertWithMessage("$kind found").that(out.mask.mean()).isGreaterThan(0f)
                }
            }
        }
        assertThat(MaskDiskCache(dir).entries()).isNotEmpty()
    }

    private companion object {
        const val TAG = "REVEAL_ML"
    }
}
