@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in 100 % crops of the Detail operator (docs/adr/0012) on public CC0 high-ISO RAWs: a full-size dump
 * (`scripts/calibration/rawdump.sh <file.ARW> <out.rdump> full`), a crop, rendered by the CPU reference before and after
 * noise reduction and sharpening. Writes `<out>-<name>.jpg` (small JPEGs for docs/screenshots/detail) and an overview.
 *
 * `REVEAL_DETAIL_DUMP=<rdump> REVEAL_DETAIL_OUT=<prefix> [REVEAL_DETAIL_CROP=x,y,w,h] [REVEAL_DETAIL_EV=0.0]
 * ./gradlew :core:pipeline:testDebugUnitTest --tests '*DetailRenderSheetTest*' --rerun`
 */
class DetailRenderSheetTest {
    @Test
    fun `render detail crops`() {
        val dump = System.getenv("REVEAL_DETAIL_DUMP")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_DETAIL_DUMP not set", dump?.isFile == true)
        val out = System.getenv("REVEAL_DETAIL_OUT")!!
        val raw = RawDump.read(dump!!)
        val ev = System.getenv("REVEAL_DETAIL_EV")?.takeIf { it.isNotBlank() }?.toFloat() ?: 0f
        val light = Light(exposureEv = ev)
        write(raw.pipelineInput(maxOf(1, raw.width / OVERVIEW)), Recipe(light = light), "$out-overview.jpg")
        val crop = System.getenv("REVEAL_DETAIL_CROP")?.takeIf { it.isNotBlank() }?.split(',')?.map { it.trim().toInt() }
            ?: listOf(raw.width / 2 - CROP / 2, raw.height / 2 - CROP / 2, CROP, CROP)
        val input = raw.crop(crop[0], crop[1], crop[2], crop[3])
        val recipes = listOf(
            "before" to Detail(),
            "nr-luminance-40" to Detail(nrLuminance = 40f),
            "nr-luminance-70" to Detail(nrLuminance = 70f),
            "nr-colour-50" to Detail(nrColour = 50f),
            "nr-40-colour-50" to Detail(nrLuminance = 40f, nrColour = 50f),
            "nr-70-colour-50-detail-80" to Detail(nrLuminance = 70f, nrLuminanceDetail = 80f, nrColour = 50f),
            "nr-70-contrast-60" to Detail(nrLuminance = 70f, nrLuminanceContrast = 60f, nrColour = 50f),
            "sharpen-60" to Detail(sharpenAmount = 60f),
            "after" to Detail(sharpenAmount = 50f, sharpenMasking = 30f, nrLuminance = 45f, nrColour = 50f),
            "mask-60" to Detail(sharpenAmount = 50f, sharpenMasking = 60f),
        )
        for ((name, detail) in recipes) {
            val recipe = Recipe(light = light, detail = detail)
            val start = System.nanoTime()
            if (name.startsWith("mask")) {
                writeMask(input, recipe, "$out-$name.jpg")
            } else {
                write(input, recipe, "$out-$name.jpg")
            }
            println("DETAIL $name ${(System.nanoTime() - start) / 1_000_000} ms")
        }
    }

    private fun write(input: PipelineInput, recipe: Recipe, path: String) {
        val rgba = CpuDevelop.render(input, recipe)
        save(rgba, input.width, input.height, path)
    }

    private fun writeMask(input: PipelineInput, recipe: Recipe, path: String) {
        val params = dev.hfps.reveal.pipeline.cpu.DevelopParams.from(input, recipe)
        val rgb = CpuDevelop.renderLinear(input, recipe.copy(detail = Detail()))
        dev.hfps.reveal.pipeline.cpu.DetailOp.mask(rgb, input.width, input.height, params.detail)
        val rgba = dev.hfps.reveal.pipeline.cpu.OutputTransformOp.apply(
            rgb,
            input.width,
            input.height,
            dev.hfps.reveal.pipeline.cpu.OutputSettings(),
        )
        save(rgba, input.width, input.height, path)
    }

    private fun save(rgba: ByteArray, w: Int, h: Int, path: String) {
        val image = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                image.setRGB(
                    x,
                    y,
                    (rgba[o].toInt() and 0xff shl 16) or (rgba[o + 1].toInt() and 0xff shl 8) or
                        (rgba[o + 2].toInt() and 0xff),
                )
            }
        }
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        ImageIO.createImageOutputStream(File(path)).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
    }

    private companion object {
        const val OVERVIEW = 1200
        const val CROP = 480
        const val QUALITY = 0.92f
    }
}
