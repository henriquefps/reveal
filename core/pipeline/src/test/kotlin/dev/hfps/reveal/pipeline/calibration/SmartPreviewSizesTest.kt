@file:Suppress("MagicNumber", "NestedBlockDepth", "LongMethod")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.preview.PreviewSteps
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Detail
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale
import java.util.stream.IntStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.math.pow
import kotlin.math.roundToInt
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in measurement of the smart preview format (ADR 0018 "Smart previews", FIX/P): for each sample (host RAW dumps
 * of the CC0 RAWs, `scripts/calibration/rawdump.sh`, and JPEGs), the input at 2560 px is written in version 1 and in
 * the current version; prints both sizes, the display JPEG at 1600 and 1280 px (q 85, ImageIO: an estimate of the
 * device's `Bitmap.compress`), and the CIEDE2000 difference between the two previews developed with Auto + a strong
 * curve + Detail + Effects (and a +2 EV shadow lift): per pixel p99 and max, and the max over 4 x 4 block means (what
 * a viewer sees at 1:1 without counting single noisy pixels).
 *
 * `REVEAL_PREVIEW_SIZES=<a.rdump>,<b.jpg> ./gradlew --no-configuration-cache :core:pipeline:testDebugUnitTest
 *   --tests '*SmartPreviewSizesTest*' --rerun -i`. Sweeps of [PreviewSteps]: `REVEAL_PREVIEW_STEPS=0.5,1` (green, in
 *   noise sigmas), `REVEAL_PREVIEW_CHROMA=1,2`, `REVEAL_PREVIEW_CAPS=0.02:0.015,0.03:0.03` (max green:chroma stops).
 */
class SmartPreviewSizesTest {
    @Test
    fun `preview sizes and developed differences`() {
        val files = System.getenv("REVEAL_PREVIEW_SIZES")?.split(',')?.map { File(it.trim()) }?.filter { it.isFile }
        assumeTrue("REVEAL_PREVIEW_SIZES not set", !files.isNullOrEmpty())
        val default = PreviewSteps.DEFAULT
        val steps = System.getenv("REVEAL_PREVIEW_STEPS")?.split(',')?.map { it.trim().toDouble() }
            ?: listOf(default.luma)
        val chromaSteps = System.getenv("REVEAL_PREVIEW_CHROMA")?.split(',')?.map { it.trim().toDouble() }
            ?: listOf(default.chroma)
        for (file in files!!) {
            val (input, scale) = load(file)
            val name = file.nameWithoutExtension
            val v1 = SmartPreviewCodec.encode(
                input,
                ByteArray(0),
                input.width,
                input.height,
                SmartPreviewCodec.VERSION_1,
            )
            val decodedV1 = SmartPreviewCodec.decode(ByteArrayInputStream(v1)).input
            val features = PhotoFeatures.of(decodedV1, detectFaces = false)
            val auto = AutoRules.run(features, Recipe(colour = Colour(WhiteBalance(mode = WbMode.AUTO))), AutoScope.ALL)
            val recipes = listOf(
                "strong" to auto.copy(
                    light = auto.light.copy(
                        curve = Curves(
                            rgb = listOf(
                                CurvePoint(0f, 0f),
                                CurvePoint(0.25f, 0.15f),
                                CurvePoint(0.75f, 0.88f),
                                CurvePoint(1f, 1f),
                            ),
                        ),
                    ),
                    detail = Detail(sharpenAmount = 80f, nrLuminance = 30f, nrColour = 40f),
                    effects = Effects(texture = 40f, clarity = 40f, dehaze = 25f),
                ),
                "lift" to auto.copy(light = auto.light.copy(exposureEv = auto.light.exposureEv + 2f, shadows = 80f)),
            )
            val reference = recipes.map { (_, r) -> labOf(CpuDevelop.renderGeometry(decodedV1, r, pixelScale = scale)) }
            val display = displayImage(decodedV1, auto)
            println(
                "PREVIEW %s %dx%d v1 %.2f MB, display JPEG 1600 %.0f KB, 1280 %.0f KB".format(
                    Locale.ROOT,
                    name,
                    input.width,
                    input.height,
                    v1.size / 1e6,
                    jpegBytes(display, 1600) / 1e3,
                    jpegBytes(display, 1280) / 1e3,
                ),
            )
            val caps =
                System.getenv("REVEAL_PREVIEW_CAPS")?.split(',')?.map { it.trim().split(':').map(String::toDouble) }
                    ?: listOf(listOf(default.maxLumaStops, default.maxChromaStops))
            for (cap in caps) {
                for (step in steps) {
                    for (chromaStep in chromaSteps) {
                        val setting = default.copy(
                            luma = step,
                            chroma = chromaStep,
                            maxLumaStops = cap[0],
                            maxChromaStops = cap[1],
                        )
                        val v2 = SmartPreviewCodec.encode(
                            input,
                            ByteArray(0),
                            input.width,
                            input.height,
                            SmartPreviewCodec.VERSION,
                            setting,
                        )
                        val decoded = SmartPreviewCodec.decode(ByteArrayInputStream(v2)).input
                        val parts = recipes.mapIndexed { k, (label, r) ->
                            val d = difference(
                                reference[k],
                                labOf(CpuDevelop.renderGeometry(decoded, r, pixelScale = scale)),
                                input,
                            )
                            "%s p99 %.2f max %.2f block %.2f".format(Locale.ROOT, label, d[0], d[1], d[2])
                        }
                        println(
                            "PREVIEW   caps %.4f:%.4f step %.2f chroma %.2f: %.2f MB (%.0f %% of v1) | %s".format(
                                Locale.ROOT,
                                cap[0],
                                cap[1],
                                step,
                                chromaStep,
                                v2.size / 1e6,
                                100.0 * v2.size / v1.size,
                                parts.joinToString(" | "),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** The input at 2560 px and its pixel scale (full-resolution pixels per preview pixel). */
    private fun load(file: File): Pair<PipelineInput, Float> {
        if (file.extension.lowercase() in setOf("jpg", "jpeg")) {
            val src = ImageIO.read(file)
            val s = minOf(1.0, EDGE.toDouble() / maxOf(src.width, src.height))
            val w = (src.width * s).toInt()
            val h = (src.height * s).toInt()
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(src, 0, 0, w, h, null)
                dispose()
            }
            val rgb = FloatArray(w * h * 3)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val p = img.getRGB(x, y)
                    for (c in 0..2) rgb[(y * w + x) * 3 + c] = LIN[p shr (16 - 8 * c) and 0xff]
                }
            }
            return PipelineInput.linearSrgb(w, h, rgb) to (1 / s).toFloat()
        }
        val dump = RawDump.read(file)
        val input = PreviewSamples.resample(dump.pipelineInput(1), EDGE)
        // A half-size dump: one dump pixel is two sensor pixels.
        return input to (dump.width * 2f / input.width)
    }

    /** [0] per-pixel p99, [1] per-pixel max, [2] max over 4 x 4 block means (CIEDE2000 of the sRGB outputs). */
    private fun difference(labA: Array<DoubleArray>, labB: Array<DoubleArray>, input: PipelineInput): DoubleArray {
        val w = input.width
        val h = input.height
        val de = DoubleArray(w * h) { ColourDifference.deltaE2000(labA[it], labB[it]) }
        val sorted = de.sortedArray()
        var block = 0.0
        for (by in 0 until h / 4) {
            for (bx in 0 until w / 4) {
                val ma = DoubleArray(3)
                val mb = DoubleArray(3)
                for (y in by * 4 until by * 4 + 4) {
                    for (x in bx * 4 until bx * 4 + 4) {
                        for (c in 0..2) {
                            ma[c] += labA[y * w + x][c] / 16
                            mb[c] += labB[y * w + x][c] / 16
                        }
                    }
                }
                block = maxOf(block, ColourDifference.deltaE2000(ma, mb))
            }
        }
        return doubleArrayOf(sorted[(sorted.size * 0.99).toInt()], sorted.last(), block)
    }

    /** CIELAB of a linear Rec.2020 render (clipped to sRGB, as the display shows it), before any 8-bit rounding. */
    private fun labOf(rgb: FloatArray): Array<DoubleArray> {
        val n = rgb.size / 3
        val out = arrayOfNulls<DoubleArray>(n)
        IntStream.range(0, n).parallel().forEach { i ->
            out[i] = ColourDifference.lab(
                ModelMaths.toSrgb(DoubleArray(3) { c -> rgb[i * 3 + c].toDouble() }),
            )
        }
        return out.requireNoNulls()
    }

    private fun displayImage(input: PipelineInput, recipe: Recipe): BufferedImage {
        val rgba = CpuDevelop.render(input, recipe)
        val img = BufferedImage(input.width, input.height, BufferedImage.TYPE_INT_RGB)
        for (i in 0 until input.width * input.height) {
            val r = rgba[i * 4].toInt() and 0xff
            val g = rgba[i * 4 + 1].toInt() and 0xff
            val b = rgba[i * 4 + 2].toInt() and 0xff
            img.setRGB(i % input.width, i / input.width, (r shl 16) or (g shl 8) or b)
        }
        return img
    }

    private fun jpegBytes(src: BufferedImage, edge: Int): Int {
        val s = edge.toDouble() / maxOf(src.width, src.height)
        val w = (src.width * s).toInt()
        val h = (src.height * s).toInt()
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        img.createGraphics().apply {
            setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            drawImage(src, 0, 0, w, h, null)
            dispose()
        }
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        val out = ByteArrayOutputStream()
        ImageIO.createImageOutputStream(out).use { stream ->
            writer.output = stream
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.85f
            }
            writer.write(null, IIOImage(img, null, null), param)
        }
        writer.dispose()
        return out.size()
    }

    private companion object {
        const val EDGE = 2560
        val LIN = FloatArray(256) {
            val v = it / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }
    }
}

/** Sample inputs for the preview measurements. */
object PreviewSamples {
    /** [input] area-resampled to a long edge of [edge] (a proxy's size). */
    fun resample(input: PipelineInput, edge: Int): PipelineInput {
        val long = maxOf(input.width, input.height)
        if (long <= edge) return input
        val s = long.toDouble() / edge
        val w = (input.width / s).roundToInt()
        val h = (input.height / s).roundToInt()
        val out = FloatArray(w * h * 3)
        val src = input.linearRgb
        for (y in 0 until h) {
            val y0 = y * s
            val y1 = y0 + s
            for (x in 0 until w) {
                val x0 = x * s
                val x1 = x0 + s
                val acc = DoubleArray(3)
                var wsum = 0.0
                var sy = y0.toInt()
                while (sy < y1 && sy < input.height) {
                    val wy = minOf(y1, sy + 1.0) - maxOf(y0, sy.toDouble())
                    var sx = x0.toInt()
                    while (sx < x1 && sx < input.width) {
                        val wx = minOf(x1, sx + 1.0) - maxOf(x0, sx.toDouble())
                        val wgt = wx * wy
                        val p = (sy * input.width + sx) * 3
                        for (c in 0..2) acc[c] += src[p + c] * wgt
                        wsum += wgt
                        sx++
                    }
                    sy++
                }
                for (c in 0..2) out[(y * w + x) * 3 + c] = (acc[c] / wsum).toFloat()
            }
        }
        return PipelineInput(
            input.kind, w, h, out, input.cameraToXyz, input.asShotMultipliers, input.saturation, input.noise,
            input.lens,
        )
    }
}
