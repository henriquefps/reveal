@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.image.format.SonyLensCorrectionReader
import dev.hfps.image.source.FileSource
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.geometry.CropEditing
import dev.hfps.reveal.pipeline.geometry.LineDetector
import dev.hfps.reveal.pipeline.geometry.Upright
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Transform
import dev.hfps.reveal.pipeline.recipe.UprightMode
import dev.hfps.reveal.pipeline.synthetic.PerspectiveScenes
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in before/after sheets of the optics (docs/adr/0015) on the public CC0 samples (`scripts/fetch-samples.sh`, half
 * size dumps from `scripts/calibration/rawdump.sh`), rendered by the CPU reference; small JPEGs for
 * docs/screenshots/optics:
 *
 * - `<out>/<sample>-lens.jpg`: the whole frame without | with the camera's lens profile (distortion, vignetting, CA);
 * - `<out>/<sample>-ca-corner.jpg`: a corner at 100 % of the half-size dump, 2x, without | with CA removal;
 * - `<out>/upright-facade.jpg`: a synthetic facade with converging verticals, without | with Upright Vertical.
 *
 * `REVEAL_OPTICS_OUT=<dir> ./gradlew :core:pipeline:testDebugUnitTest --tests '*OpticsRenderSheetTest*' --rerun`
 * (dumps read from `build/calibration/<sample>.rdump`, made first with rawdump.sh).
 */
class OpticsRenderSheetTest {
    private val root = File("../..")

    @Test
    fun `render optics sheets`() {
        val out = System.getenv("REVEAL_OPTICS_OUT")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_OPTICS_OUT not set", out != null)
        checkNotNull(out).mkdirs()
        for ((name, sample, dumpName, at) in SAMPLES) {
            val arw = File(root, "core/image/build/samples/$sample")
            val dump = File(root, "build/calibration/$dumpName")
            if (!arw.isFile || !dump.isFile) {
                println("OPTICS skip $name: run fetch-samples.sh and rawdump.sh first")
                continue
            }
            val data = checkNotNull(FileSource(arw).use { SonyLensCorrectionReader.read(it) })
            val lens =
                checkNotNull(SonyLensProfile.of(name, data.distortion, data.vignetting, data.chromaticAberration))
            val raw = RawDump.read(dump)
            val light = Light(exposureEv = 0.3f)
            val off = Recipe(light = light)
            val on = Recipe(light = light, optics = Optics(lensCorrections = true, removeCa = true))
            val small = raw.pipelineInput(maxOf(1, raw.width / OVERVIEW)).withLens(lens)
            sideBySide(render(small, off), render(small, on), File(out, "$name-lens.jpg"))
            val full = raw.pipelineInput(1).withLens(lens)
            val caOff = Recipe(light = light, optics = Optics(lensCorrections = true))
            sideBySide(
                corner(render(full, caOff), at),
                corner(render(full, on), at),
                File(out, "$name-ca-corner.jpg"),
                zoom = 2,
            )
            println("OPTICS $name: ${lens.hasDistortion}/${lens.hasVignetting}/${lens.hasChromatic}")
        }
        facade(File(out, "upright-facade.jpg"))
    }

    private data class Sample(val name: String, val file: String, val dump: String, val at: Pair<Double, Double>)

    private class Picture(val rgba: ByteArray, val width: Int, val height: Int)

    private fun render(input: PipelineInput, recipe: Recipe): Picture {
        val mapping = CpuDevelop.mapping(input, recipe)
        return Picture(CpuDevelop.render(input, recipe), mapping.outputWidth, mapping.outputHeight)
    }

    /** [CORNER] x [CORNER] pixels at [at] (fractions of the picture: an edge-rich spot far from the centre). */
    private fun corner(p: Picture, at: Pair<Double, Double>): Picture {
        val x0 = (at.first * p.width).toInt().coerceIn(0, p.width - CORNER)
        val y0 = (at.second * p.height).toInt().coerceIn(0, p.height - CORNER)
        val out = ByteArray(CORNER * CORNER * 4)
        for (y in 0 until CORNER) {
            System.arraycopy(p.rgba, ((y + y0) * p.width + x0) * 4, out, y * CORNER * 4, CORNER * 4)
        }
        return Picture(out, CORNER, CORNER)
    }

    private fun facade(file: File) {
        val w = 900
        val h = 600
        val gray = PerspectiveScenes.facade(w, h, Transform(vertical = 30f))
        val rgb = FloatArray(w * h * 3) { gray[it / 3] * 0.6f }
        val input = PipelineInput.linearSrgb(w, h, rgb)
        val lines = Upright.normalise(LineDetector.detect(gray, w, h), w, h, null)
        val t = checkNotNull(Upright.solve(UprightMode.VERTICAL, lines, Transform()))
        val g = CropEditing.transform(Geometry(), t, w, h)
        println("OPTICS upright facade: $t")
        sideBySide(render(input, Recipe()), render(input, Recipe(geometry = g)), file)
    }

    private fun sideBySide(a: Picture, b: Picture, file: File, zoom: Int = 1) {
        val height = maxOf(a.height, b.height) * zoom
        val image = BufferedImage((a.width + b.width) * zoom + GAP, height, BufferedImage.TYPE_INT_RGB)
        fun draw(p: Picture, x0: Int) {
            val top = (height - p.height * zoom) / 2
            for (y in 0 until p.height * zoom) {
                for (x in 0 until p.width * zoom) {
                    val o = ((y / zoom) * p.width + x / zoom) * 4
                    val c = (p.rgba[o].toInt() and 0xff shl 16) or (p.rgba[o + 1].toInt() and 0xff shl 8) or
                        (p.rgba[o + 2].toInt() and 0xff)
                    image.setRGB(x0 + x, top + y, c)
                }
            }
        }
        draw(a, 0)
        draw(b, a.width * zoom + GAP)
        val writer = ImageIO.getImageWritersByFormatName("jpg").next()
        val param = writer.defaultWriteParam.apply {
            compressionMode = ImageWriteParam.MODE_EXPLICIT
            compressionQuality = QUALITY
        }
        ImageIO.createImageOutputStream(file).use { stream ->
            writer.output = stream
            writer.write(null, IIOImage(image, null, null), param)
        }
        writer.dispose()
    }

    private companion object {
        const val OVERVIEW = 800
        const val CORNER = 200
        const val GAP = 8
        const val QUALITY = 0.85f
        val SAMPLES = listOf(
            Sample("zv-e10", "sony-zv-e10.arw", "zv.rdump", 0.84 to 0.02),
            Sample("a7c", "sony-a7c-iso2000.arw", "a7c2000.rdump", 0.80 to 0.02),
        )
    }
}
