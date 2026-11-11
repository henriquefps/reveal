@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
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
 * Opt-in before/after sheets of the Effects (docs/adr/0014) on public CC0 RAWs (`scripts/fetch-samples.sh`, dumped with
 * `scripts/calibration/rawdump.sh <file> <out.rdump>`), rendered by the CPU reference: each sheet is the photo before
 * (left) and after (right), about 700 px wide each, at the scale a proxy would render it (`pixelScale` = full width /
 * rendered width); texture is shown as a 100 % crop, where it acts. Writes `<out>-<sample>-<effect>.jpg`.
 *
 * `REVEAL_EFFECTS_DUMPS=<a.rdump>,<b.rdump> REVEAL_EFFECTS_OUT=<prefix> [REVEAL_EFFECTS_EV=0.3]
 * ./gradlew :core:pipeline:testDebugUnitTest --tests '*EffectsRenderSheetTest*' --rerun`
 */
class EffectsRenderSheetTest {
    @Test
    fun `render effects sheets`() {
        val dumps = System.getenv("REVEAL_EFFECTS_DUMPS")?.split(',')?.map { File(it.trim()) }?.filter { it.isFile }
        assumeTrue("REVEAL_EFFECTS_DUMPS not set", !dumps.isNullOrEmpty())
        val out = System.getenv("REVEAL_EFFECTS_OUT")!!
        val ev = System.getenv("REVEAL_EFFECTS_EV")?.takeIf { it.isNotBlank() }?.toFloat() ?: 0.3f
        val base = Recipe(light = Light(exposureEv = ev, contrast = 10f))
        for (file in dumps!!) {
            val raw = RawDump.read(file)
            val name = file.nameWithoutExtension
            val factor = maxOf(1, raw.width / WIDTH)
            val small = raw.pipelineInput(factor)
            // A half-size dump: one dump pixel is two full-resolution pixels.
            val scale = factor * 2f
            val sheets = listOf(
                "clarity" to base.copy(effects = Effects(clarity = 60f)),
                "dehaze" to base.copy(effects = Effects(dehaze = 50f)),
                "haze-added" to base.copy(effects = Effects(dehaze = -50f)),
                "vignette-grain" to base.copy(
                    geometry = Geometry(crop = CropRect(0.1f, 0.05f, 0.85f, 0.95f)),
                    effects = Effects(vignetteAmount = -60f, vignetteFeather = 70f, grainAmount = 40f),
                ),
                "all" to base.copy(
                    effects = Effects(texture = 30f, clarity = 35f, dehaze = 20f, vignetteAmount = -25f),
                ),
            )
            for ((effect, recipe) in sheets) {
                val before = base.copy(geometry = recipe.geometry)
                pair(small, before, recipe, scale, "$out-$name-$effect.jpg")
            }
            val crop = raw.crop(raw.width / 2 - CROP / 2, raw.height / 2 - CROP / 2, CROP, CROP)
            pair(crop, base, base.copy(effects = Effects(texture = 80f)), 2f, "$out-$name-texture.jpg")
            pair(crop, base, base.copy(effects = Effects(texture = -80f)), 2f, "$out-$name-texture-smooth.jpg")
            println("EFFECTS sheets $name done")
        }
    }

    private fun pair(input: PipelineInput, before: Recipe, after: Recipe, scale: Float, path: String) {
        val mapping = CpuDevelop.mapping(input, before)
        val a = CpuDevelop.render(input, before, pixelScale = scale)
        val b = CpuDevelop.render(input, after, pixelScale = scale)
        val w = mapping.outputWidth
        val h = mapping.outputHeight
        val image = BufferedImage(w * 2 + GAP, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                image.setRGB(x, y, rgb(a, o))
                image.setRGB(w + GAP + x, y, rgb(b, o))
            }
            for (x in w until w + GAP) image.setRGB(x, y, 0x1c1c1c)
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

    private fun rgb(p: ByteArray, o: Int): Int =
        (p[o].toInt() and 0xff shl 16) or (p[o + 1].toInt() and 0xff shl 8) or (p[o + 2].toInt() and 0xff)

    private companion object {
        const val WIDTH = 700
        const val CROP = 480
        const val GAP = 8
        const val QUALITY = 0.8f
    }
}
