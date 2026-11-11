@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoBlackWhite
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.BandValues
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.ColourGrading
import dev.hfps.reveal.pipeline.recipe.ColourMixer
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.GradeWheel
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.ParametricCurve
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in before/after sheets of the colour tools for PRs and ADR 0013 (public CC0 RAWs only): for every `*.rdump` in
 * `REVEAL_COLOUR_SHEET_DIR` (from `scripts/calibration/rawdump.sh`), six tiles: as opened, a tone curve, the colour
 * mixer, colour grading, black & white with Auto mix, and B&W with a split tone.
 * `REVEAL_COLOUR_SHEET_DIR=build/calibration REVEAL_RENDER_OUT=docs/screenshots/colour-tools/colour-tools ./gradlew
 * :core:pipeline:testDebugUnitTest --tests '*ColourToolsRenderSheetTest*' --rerun`.
 */
class ColourToolsRenderSheetTest {
    @Test
    fun `render colour tools before and after sheets`() {
        val dir = System.getenv("REVEAL_COLOUR_SHEET_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_COLOUR_SHEET_DIR not set", dir?.isDirectory == true)
        val out = System.getenv("REVEAL_RENDER_OUT")!!
        dir!!.listFiles()!!.filter { it.name.endsWith(".rdump") }.sortedBy { it.name }.forEach { dump ->
            val raw = RawDump.read(dump)
            val input = raw.pipelineInput(maxOf(1, raw.width / TILE))
            val bw = Recipe(profile = Profile(treatment = Treatment.BLACK_WHITE))
            val mix = AutoBlackWhite.run(AutoAnalyser.sample(input), bw)
            val tiles = listOf(
                "as opened" to Recipe(),
                "tone curve: strong contrast + parametric" to CURVE,
                "colour mixer: deeper sky, warmer greens" to MIXER,
                "colour grading: teal shadows, warm highlights" to GRADING,
                "B&W, Auto mix" to bw.copy(colour = Colour(greyMixer = mix)),
                "B&W, Auto mix + split tone" to bw.copy(colour = Colour(greyMixer = mix, grading = SPLIT_TONE)),
            )
            sheet(input, tiles, "$out-${dump.name.removeSuffix(".rdump")}.jpg")
            println("COLOUR-SHEET ${dump.name} auto mix ${mix.toList()}")
        }
    }

    private fun sheet(input: PipelineInput, tiles: List<Pair<String, Recipe>>, path: String) {
        val w = input.width
        val h = input.height
        val cols = 2
        val rows = (tiles.size + cols - 1) / cols
        val sheet = BufferedImage(cols * w, rows * (h + LABEL), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1c, 0x1c, 0x1c)
        g.fillRect(0, 0, sheet.width, sheet.height)
        tiles.forEachIndexed { i, (name, recipe) ->
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
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
            g.drawString(name, x0 + 10, y0 + 26)
        }
        g.dispose()
        ImageIO.write(sheet, "jpg", File(path))
    }

    private companion object {
        const val TILE = 560
        const val LABEL = 38

        val CURVE = Recipe(
            light = Light(
                curve = Curves(
                    parametric = ParametricCurve(highlights = -25f, shadows = 20f),
                    rgb = Curves.STRONG_CONTRAST,
                    blue = listOf(CurvePoint(0f, 0.04f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 0.96f)),
                ),
            ),
        )
        val MIXER = Recipe(
            colour = Colour(
                mixer = ColourMixer(
                    hue = BandValues(green = -40f, yellow = -20f),
                    saturation = BandValues(blue = 30f, green = -15f, orange = 10f),
                    luminance = BandValues(blue = -60f, aqua = -30f, orange = 15f),
                ),
            ),
        )
        val GRADING = Recipe(
            colour = Colour(
                grading = ColourGrading(
                    shadows = GradeWheel(210f, 35f, -5f),
                    highlights = GradeWheel(60f, 30f, 5f),
                    blending = 60f,
                ),
            ),
        )
        val SPLIT_TONE =
            ColourGrading(shadows = GradeWheel(240f, 25f), highlights = GradeWheel(70f, 30f), balance = 10f)
    }
}
