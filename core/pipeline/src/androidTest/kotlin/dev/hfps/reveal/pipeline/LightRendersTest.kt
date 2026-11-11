package dev.hfps.reveal.pipeline

import android.graphics.Bitmap
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.gl.GpuDevelopRenderer
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Opt-in (`-e renders true`): develops public sample RAWs found in this test app's `files/samples` (CC0, from
 * `scripts/fetch-samples.sh`; never private photos) with the Light group's extremes on the GPU, writes PNGs to
 * `files/renders` for the PR, and compares GPU and CPU on the real image (`REVEAL_RENDERS`).
 */
@RunWith(AndroidJUnit4::class)
class LightRendersTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val enabled = InstrumentationRegistry.getArguments().getString("renders") == "true"

    @Test
    fun renderLightExtremesOfTheSamples(): Unit = runBlocking {
        assumeTrue("pass -e renders true", enabled)
        val samples = File(context.filesDir, "samples").listFiles().orEmpty().filter { it.isFile }.sortedBy { it.name }
        assumeTrue("no samples in files/samples", samples.isNotEmpty())
        val out = File(context.filesDir, "renders").apply { mkdirs() }
        val renderer = GpuDevelopRenderer.create()
        try {
            for (file in samples) {
                val proxy = ImageInput.fromFile(file).use { ProxyBuilder.decodeProxy(it, LONG_EDGE) }
                val input = PipelineInputMapper.map(proxy.image, proxy.info)
                renderer.upload(input)
                val stem = file.nameWithoutExtension
                for ((name, recipe) in RECIPES) {
                    val rgba = renderer.renderOutput(recipe)
                    write(rgba, input.width, input.height, File(out, "$stem-$name.png"))
                }
                compare(stem, input, renderer)
            }
        } finally {
            renderer.close()
        }
    }

    private suspend fun compare(stem: String, input: PipelineInput, renderer: GpuDevelopRenderer) {
        val recipe = RECIPES.getValue("all")
        val gpu = renderer.renderOutput(recipe)
        val cpu = CpuDevelop.render(input, recipe)
        var max = 0
        var sum = 0L
        for (i in cpu.indices) {
            if (i % 4 == 3) continue
            val d = abs((gpu[i].toInt() and 0xff) - (cpu[i].toInt() and 0xff))
            max = maxOf(max, d)
            sum += d
        }
        Log.i(
            TAG,
            "$stem ${input.width}x${input.height} saturation ${input.saturation}: GPU vs CPU (all Light sliders) " +
                "max $max levels, mean ${sum.toDouble() / (cpu.size / 4 * 3)}",
        )
    }

    private fun write(rgba: ByteArray, width: Int, height: Int, file: File) {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        Log.i(TAG, "wrote ${file.name}")
    }

    private companion object {
        const val TAG = "REVEAL_RENDERS"
        const val LONG_EDGE = 1600

        val RECIPES: Map<String, Recipe> = linkedMapOf(
            "default" to Recipe(),
            "highlights-100" to Recipe(light = Light(highlights = -100f)),
            "shadows+100" to Recipe(light = Light(shadows = 100f)),
            "contrast+50" to Recipe(light = Light(contrast = 50f)),
            "contrast-50" to Recipe(light = Light(contrast = -50f)),
            "all" to Recipe(light = Light(highlights = -100f, shadows = 100f, contrast = 20f)),
            "exposure-2" to Recipe(light = Light(exposureEv = -2f)),
        )
    }
}
