package dev.hfps.reveal.ml

import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.reveal.ml.runtime.Backend
import dev.hfps.reveal.ml.runtime.LiteRtEngine

/** Test images and model loading for the instrumented mask tests. */
object Fixtures {
    private val context get() = InstrumentationRegistry.getInstrumentation().context
    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A public-domain fixture (`assets/fixtures/<name>.jpg`) as the models see it. */
    fun image(name: String): MlImage = context.assets.open("fixtures/$name.jpg").use { stream ->
        val bmp = checkNotNull(BitmapFactory.decodeStream(stream))
        val px = IntArray(bmp.width * bmp.height)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        MlImage.fromArgb(bmp.width, bmp.height, px)
    }

    /** True when the model's asset is in the APK under test (the library's merged assets). */
    fun bundled(spec: ModelSpec): Boolean =
        runCatching { target.assets.openFd(MaskModels.assetPath(spec)).close() }.isSuccess ||
            runCatching { target.assets.open(MaskModels.assetPath(spec)).close() }.isSuccess

    /** [spec] loaded from the APK's assets on [backend] only. */
    fun load(spec: ModelSpec, backend: Backend): SegmentationModel = SegmentationModel(
        spec,
        LiteRtEngine.load(
            LiteRtEngine.Source.Asset(target.assets, MaskModels.assetPath(spec)),
            listOf(backend),
        ),
    )
}
