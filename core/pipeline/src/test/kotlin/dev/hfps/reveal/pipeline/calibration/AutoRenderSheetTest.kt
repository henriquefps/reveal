@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.cpu.AutoWhiteBalance
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in before/after sheets of Auto for PRs and ADR 0010 (public CC0 RAWs only): for every `*.rdump` in
 * `REVEAL_AUTO_SHEET_DIR` (from `scripts/calibration/rawdump.sh`), one row: as opened, Auto (as-shot white balance),
 * Auto with Auto WB, and the same RAW 1.5 EV under-exposed with Auto. The slider values are printed under each tile.
 * `REVEAL_AUTO_SHEET_DIR=build/calibration REVEAL_RENDER_OUT=build/calibration/auto ./gradlew
 * :core:pipeline:testDebugUnitTest --tests '*AutoRenderSheetTest*' --rerun`.
 */
class AutoRenderSheetTest {
    @Test
    fun `render auto before and after sheets`() {
        val dir = System.getenv("REVEAL_AUTO_SHEET_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_AUTO_SHEET_DIR not set", dir?.isDirectory == true)
        val out = System.getenv("REVEAL_RENDER_OUT")!!
        dir!!.listFiles()!!.filter { it.name.endsWith(".rdump") }.sortedBy { it.name }.forEach { dump ->
            val raw = RawDump.read(dump)
            val input = raw.pipelineInput(maxOf(1, raw.width / TILE))
            val sample = AutoAnalyser.sample(input)
            val wb = AutoWhiteBalance.analyse(input)
            val auto = AutoRules.run(sample, Recipe(), AutoScope.ALL)
            val autoWb = AutoRules.run(
                sample,
                Recipe(colour = Colour(WhiteBalance(mode = WbMode.AUTO))),
                AutoScope.ALL,
                wb,
            )
            val dark = under(input)
            val darkAuto = AutoRules.run(AutoAnalyser.sample(dark), Recipe(), AutoScope.ALL)
            val tiles = listOf(
                Triple("as opened", input, Recipe()),
                Triple("Auto", input, auto),
                Triple("Auto + Auto WB", input, autoWb),
                Triple("-1.5 EV, Auto", dark, darkAuto),
            )
            sheet(tiles, "$out-${dump.name.removeSuffix(".rdump")}.jpg")
            println("AUTO-SHEET ${dump.name} ${label(auto)} | WB ${label(autoWb)} | dark ${label(darkAuto)}")
        }
    }

    private fun under(src: PipelineInput) = PipelineInput(
        src.kind,
        src.width,
        src.height,
        FloatArray(src.linearRgb.size) { src.linearRgb[it] * UNDER },
        src.cameraToXyz,
        src.asShotMultipliers,
        src.saturation,
    )

    private fun label(r: Recipe): String {
        val l = r.light
        val wb = r.colour.whiteBalance.temperatureK?.let { String.format(Locale.ROOT, " %.0f K", it) }.orEmpty()
        return String.format(
            Locale.ROOT,
            "EV %+.2f  C %+.0f  H %+.0f  S %+.0f  W %+.0f  B %+.0f  Vib %+.0f%s",
            l.exposureEv,
            l.contrast,
            l.highlights,
            l.shadows,
            l.whites,
            l.blacks,
            r.colour.vibrance,
            wb,
        )
    }

    private fun sheet(tiles: List<Triple<String, PipelineInput, Recipe>>, path: String) {
        val w = tiles[0].second.width
        val h = tiles[0].second.height
        val cols = 2
        val rows = (tiles.size + cols - 1) / cols
        val sheet = BufferedImage(cols * w, rows * (h + LABEL), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1c, 0x1c, 0x1c)
        g.fillRect(0, 0, sheet.width, sheet.height)
        tiles.forEachIndexed { i, (name, input, recipe) ->
            val rgba = CpuDevelop.render(input, recipe)
            val x0 = (i % cols) * w
            val y0 = (i / cols) * (h + LABEL)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val o = (y * w + x) * 4
                    val p = (rgba[o].toInt() and 0xff shl 16) or (rgba[o + 1].toInt() and 0xff shl 8) or
                        (rgba[o + 2].toInt() and 0xff)
                    sheet.setRGB(x0 + x, y0 + LABEL + y, p)
                }
            }
            g.color = Color(0xe6, 0xe6, 0xe6)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            g.drawString(name, x0 + 10, y0 + 24)
            g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
            if (i > 0) g.drawString(label(recipe), x0 + 10, y0 + 48)
        }
        g.dispose()
        ImageIO.write(sheet, "jpg", File(path))
    }

    private companion object {
        const val TILE = 560
        const val LABEL = 58
        const val UNDER = 0.35355339f
    }
}
