@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.WhiteBalanceModel
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contact sheets for PRs and the ADR (public CC0 RAWs only, e.g. raw.pixls.us): every profile and every
 * white-balance mode rendered by the CPU reference. `REVEAL_RENDER_DUMP=<file.rdump from rawdump.sh>
 * REVEAL_RENDER_OUT=<png prefix> ./gradlew :core:pipeline:testDebugUnitTest --tests '*SampleRenderSheetTest*'`.
 */
class SampleRenderSheetTest {
    @Test
    fun `render profile and white balance sheets`() {
        val dump = System.getenv("REVEAL_RENDER_DUMP")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_RENDER_DUMP not set", dump?.isFile == true)
        val out = System.getenv("REVEAL_RENDER_OUT")!!
        val raw = RawDump.read(dump!!)
        val input = raw.pipelineInput(maxOf(1, raw.width / TILE))
        val profiles = BaseLook.entries.map { look -> look.name.lowercase() to Recipe(profile = Profile(look)) } +
            listOf(
                "vivid 200 %" to Recipe(profile = Profile(BaseLook.VIVID, 200f)),
                "standard, vibrance +60" to Recipe(colour = Colour(vibrance = 60f)),
                "standard, saturation -60" to Recipe(colour = Colour(saturation = -60f)),
            )
        sheet(input, profiles, "$out-profiles.png")
        val auto = AutoWhiteBalance.whiteBalance(AutoWhiteBalance.analyse(input))
        val modes =
            listOf(WbMode.DAYLIGHT, WbMode.CLOUDY, WbMode.SHADE, WbMode.TUNGSTEN, WbMode.FLUORESCENT, WbMode.FLASH)
        val wbs = listOf("as shot" to WhiteBalance(), "auto (${auto.temperatureK!!.toInt()} K)" to auto) +
            modes.map { m ->
                val wb = WhiteBalanceModel.preset(m, input)!!
                "${m.name.lowercase()} (${wb.temperatureK!!.toInt()} K)" to wb
            }
        sheet(input, wbs.map { (n, wb) -> n to Recipe(colour = Colour(wb)) }, "$out-white-balance.png")
    }

    private fun sheet(input: dev.hfps.reveal.pipeline.PipelineInput, tiles: List<Pair<String, Recipe>>, path: String) {
        val cols = 3
        val rows = (tiles.size + cols - 1) / cols
        val label = 34
        val sheet = BufferedImage(cols * input.width, rows * (input.height + label), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1c, 0x1c, 0x1c)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 22)
        tiles.forEachIndexed { i, (name, recipe) ->
            val rgba = CpuDevelop.render(input, recipe)
            val x0 = (i % cols) * input.width
            val y0 = (i / cols) * (input.height + label)
            for (y in 0 until input.height) {
                for (x in 0 until input.width) {
                    val o = (y * input.width + x) * 4
                    val p = (rgba[o].toInt() and 0xff shl 16) or (rgba[o + 1].toInt() and 0xff shl 8) or
                        (rgba[o + 2].toInt() and 0xff)
                    sheet.setRGB(x0 + x, y0 + label + y, p)
                }
            }
            g.color = Color(0xe6, 0xe6, 0xe6)
            g.drawString(name, x0 + 10, y0 + 25)
        }
        g.dispose()
        ImageIO.write(sheet, "png", File(path))
    }

    private companion object {
        const val TILE = 640
    }
}
