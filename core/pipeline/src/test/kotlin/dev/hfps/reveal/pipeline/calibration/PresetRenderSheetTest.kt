@file:Suppress("MagicNumber", "LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "MaxLineLength")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.auto.AutoAnalyser
import dev.hfps.reveal.pipeline.auto.AutoRules
import dev.hfps.reveal.pipeline.auto.AutoScope
import dev.hfps.reveal.pipeline.auto.PhotoFeatures
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.preset.BuiltInPresets
import dev.hfps.reveal.pipeline.preset.SmartCorrector
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
import java.util.stream.IntStream
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in contact sheets of the built-in presets (docs/adr/0020, "Built-in presets"): every preset on up to 12 photos,
 * before (the photo with Auto, how a preset is usually applied) and after, plus an overview of all presets on four
 * photos, and aggregates per preset (`PRESET-CHECK`: new clipping, faces' skin hue shift, median L* change).
 *
 * Input: `REVEAL_PRESET_DUMPS`, a folder of `rdump.sh` dumps (with the camera `.JPG` beside them for the
 * orientation) and/or JPEGs (the public-domain face fixtures). Output: `REVEAL_PRESET_OUT`. Sheets of the owner's
 * photos go only to his private review folder, never into the repository.
 *
 *   REVEAL_PRESET_DUMPS=<dir> REVEAL_PRESET_OUT=<dir> ./gradlew :core:pipeline:testDebugUnitTest \
 *     --tests '*PresetRenderSheetTest*' --rerun
 */
class PresetRenderSheetTest {
    private class Photo(val input: PipelineInput, val features: PhotoFeatures, val before: Recipe)

    @Test
    fun `render preset contact sheets`() {
        val dir = System.getenv("REVEAL_PRESET_DUMPS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("REVEAL_PRESET_DUMPS not set", dir?.isDirectory == true)
        val out = File(System.getenv("REVEAL_PRESET_OUT")!!).apply { mkdirs() }
        val limit = System.getenv("REVEAL_PRESET_PHOTOS")?.toIntOrNull() ?: PHOTOS
        val only = System.getenv("REVEAL_PRESET_ONLY")?.takeIf { it.isNotBlank() }?.split(',')?.toSet()
        val files = dir!!.listFiles()!!.filter {
            it.name.endsWith(".rdump") ||
                (
                    it.extension.lowercase() in setOf("jpg", "jpeg") &&
                        !File(it.parentFile, it.nameWithoutExtension + ".rdump").exists()
                    )
        }.sortedBy { it.name }
        val step = max(1.0, files.size.toDouble() / limit)
        val chosen = (0 until minOf(limit, files.size)).map { files[(it * step).toInt()] }
        val photos = chosen.map(::photo)
        val presets = BuiltInPresets.all.filter { only == null || it.key in only }
        val overview = ArrayList<List<BufferedImage>>()
        for (p in presets) {
            val tiles = arrayOfNulls<kotlin.Pair<BufferedImage, BufferedImage>>(photos.size)
            val checks = arrayOfNulls<DoubleArray>(photos.size)
            IntStream.range(0, photos.size).parallel().forEach { i ->
                val ph = photos[i]
                val start = if (p.preset.smart != null) Recipe() else ph.before
                val after = p.preset.apply(start, 1f, smartCorrection = SmartCorrector(ph.features))
                val a = image(ph.input, ph.before)
                val b = image(ph.input, after)
                tiles[i] = a to b
                checks[i] = check(a, b, ph)
            }
            sheet(p.preset.name, tiles.map { it!! }, File(out, "preset-${p.key}.jpg"))
            overview += tiles.take(OVERVIEW_PHOTOS).map { it!!.second }
            val c = checks.map { it!! }
            fun mean(k: Int) =
                c.map { it[k] }.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            fun maxOf(k: Int) = c.map { it[k] }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
            println(
                String.format(
                    Locale.ROOT,
                    "PRESET-CHECK %-24s new clip %.2f %% (max %.2f)  skin hue %+.1f° (max |%.1f|)  skin L* %+.1f  median L* %+.1f",
                    p.key, mean(0), maxOf(0), mean(1),
                    c.map { abs(it[1]) }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN,
                    mean(2), mean(3),
                ),
            )
        }
        overviewSheet(presets.map { it.preset.name }, overview, File(out, "overview.jpg"))
    }

    /**
     * Auto review (opt-in, `REVEAL_AUTO_COMPARE=1` with the same folders): per photo the camera JPEG, M1's Auto (no
     * features) and M3's Auto (faces + subject + face WB), detected faces outlined on the last, three photos per sheet.
     */
    @Test
    fun `render auto comparison sheets`() {
        val dir = System.getenv("REVEAL_PRESET_DUMPS")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue(
            "REVEAL_PRESET_DUMPS not set",
            dir?.isDirectory == true && System.getenv("REVEAL_AUTO_COMPARE") == "1",
        )
        val out = File(System.getenv("REVEAL_PRESET_OUT")!!).apply { mkdirs() }
        val dumps = dir!!.listFiles()!!.filter { it.name.endsWith(".rdump") }.sortedBy { it.name }
        dumps.chunked(3).forEachIndexed { sheetIndex, chunk ->
            val rows = chunk.map { dump ->
                val jpg = File(dump.parentFile, dump.name.removeSuffix(".rdump") + ".JPG")
                val turns = UprightPairs.turns(UprightPairs.orientation(jpg))
                val photo = photo(dump)
                val auto = Recipe(colour = Colour(WhiteBalance(mode = WbMode.AUTO)))
                val m1 = AutoRules.run(PhotoFeatures(photo.features.sample, photo.features.wb), auto, AutoScope.ALL)
                val camera = UprightPairs.rotate(ImageIO.read(jpg), turns)
                val m3 = image(photo.input, photo.before)
                val g = m3.createGraphics()
                g.color = Color(0x4f, 0xc3, 0xf7)
                g.stroke = java.awt.BasicStroke(2f)
                for (f in photo.features.faces) {
                    g.drawRect(
                        (f.left * m3.width).toInt(),
                        (f.top * m3.height).toInt(),
                        (f.width * m3.width).toInt(),
                        (
                            f.height *
                                m3.height
                            ).toInt(),
                    )
                }
                g.dispose()
                listOf(camera, image(photo.input, m1), m3)
            }
            val cell = TILE + GAP
            val sheet = BufferedImage(3 * cell, HEADER + rows.size * cell, BufferedImage.TYPE_INT_RGB)
            val g = sheet.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.color = Color(0x1c, 0x1c, 0x1c)
            g.fillRect(0, 0, sheet.width, sheet.height)
            g.color = Color(0xe6, 0xe6, 0xe6)
            g.font = Font(Font.SANS_SERIF, Font.BOLD, 20)
            g.drawString("Camera JPEG  |  Auto M1  |  Auto M3 (faces outlined)", 12, 30)
            rows.forEachIndexed { r, row ->
                row.forEachIndexed { c, img ->
                    val t = fit(img, TILE)
                    g.drawImage(t, c * cell + (TILE - t.width) / 2, HEADER + r * cell + (TILE - t.height) / 2, null)
                }
            }
            g.dispose()
            ImageIO.write(sheet, "jpg", File(out, String.format(Locale.ROOT, "auto-%02d.jpg", sheetIndex + 1)))
        }
    }

    private fun photo(file: File): Photo {
        val input = if (file.name.endsWith(".rdump")) {
            val raw = RawDump.read(file)
            val jpg = listOf("JPG", "jpg").map {
                File(file.parentFile, file.name.removeSuffix(".rdump") + ".$it")
            }.firstOrNull { it.exists() }
            val turns = jpg?.let { UprightPairs.turns(UprightPairs.orientation(it)) } ?: 0
            UprightPairs.rotate(raw.pipelineInput(maxOf(1, maxOf(raw.width, raw.height) / SIDE)), turns)
        } else {
            jpeg(file)
        }
        val features = PhotoFeatures.of(input, findLines = true)
        val auto = AutoRules.run(features, Recipe(colour = Colour(WhiteBalance(mode = WbMode.AUTO))), AutoScope.ALL)
        return Photo(input, features, auto)
    }

    private fun jpeg(file: File): PipelineInput {
        val src = ImageIO.read(file)
        val s = minOf(1.0, SIDE.toDouble() / maxOf(src.width, src.height))
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
        return PipelineInput.linearSrgb(w, h, rgb)
    }

    private fun image(input: PipelineInput, recipe: Recipe): BufferedImage {
        val mapping = CpuDevelop.mapping(input, recipe)
        val rgba = CpuDevelop.render(input, recipe)
        val w = mapping.outputWidth
        val h = mapping.outputHeight
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val o = (y * w + x) * 4
                img.setRGB(
                    x,
                    y,
                    (rgba[o].toInt() and 0xff shl 16) or (rgba[o + 1].toInt() and 0xff shl 8) or
                        (rgba[o + 2].toInt() and 0xff),
                )
            }
        }
        return img
    }

    /** New clipping (%), the faces' skin hue and L* change, the median L* change (after minus before). */
    private fun check(before: BufferedImage, after: BufferedImage, photo: Photo): DoubleArray {
        if (before.width != after.width ||
            before.height != after.height
        ) {
            return doubleArrayOf(Double.NaN, Double.NaN, Double.NaN, Double.NaN)
        }
        var clip = 0
        val lab = FloatArray(3)
        val skin = Array(2) { DoubleArray(3) }
        var n = 0
        val lb = ArrayList<Double>()
        val la = ArrayList<Double>()
        for (y in 0 until before.height) {
            for (x in 0 until before.width) {
                val p = before.getRGB(x, y)
                val q = after.getRGB(x, y)
                if (maxChannel(q) >= 254 && maxChannel(p) < 254) clip++
                val u = (x + 0.5f) / before.width
                val v = (y + 0.5f) / before.height
                lb += lStar(p)
                la += lStar(q)
                if (photo.features.faces.any { AutoAnalyser.inSkin(it, u, v) }) {
                    for ((k, c) in listOf(p, q).withIndex()) {
                        OkLab.fromRgb(LIN[c shr 16 and 0xff], LIN[c shr 8 and 0xff], LIN[c and 0xff], lab)
                        skin[k][0] += lab[0].toDouble()
                        skin[k][1] += lab[1].toDouble()
                        skin[k][2] += lab[2].toDouble()
                    }
                    n++
                }
            }
        }
        fun hue(s: DoubleArray) = Math.toDegrees(StrictMath.atan2(s[2], s[1]))
        var dh = if (n == 0) Double.NaN else hue(skin[1]) - hue(skin[0])
        while (dh > 180) dh -= 360
        while (dh < -180) dh += 360
        val dl = if (n == 0) Double.NaN else (skin[1][0] - skin[0][0]) / n * 100
        lb.sort()
        la.sort()
        return doubleArrayOf(100.0 * clip / (before.width * before.height), dh, dl, la[la.size / 2] - lb[lb.size / 2])
    }

    private fun maxChannel(p: Int) = maxOf(p shr 16 and 0xff, p shr 8 and 0xff, p and 0xff)

    private fun lStar(p: Int): Double {
        val y = 0.2126 * LIN[p shr 16 and 0xff] + 0.7152 * LIN[p shr 8 and 0xff] + 0.0722 * LIN[p and 0xff]
        return if (y > 216.0 / 24389) 116 * StrictMath.cbrt(y) - 16 else y * 24389.0 / 27
    }

    private fun fit(img: BufferedImage, side: Int): BufferedImage {
        val s = side.toDouble() / maxOf(img.width, img.height)
        val w = (img.width * s).toInt()
        val h = (img.height * s).toInt()
        return BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).also {
            it.createGraphics().apply {
                setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
                drawImage(img, 0, 0, w, h, null)
                dispose()
            }
        }
    }

    /** Before/after pairs, three pairs per row. */
    private fun sheet(title: String, tiles: List<kotlin.Pair<BufferedImage, BufferedImage>>, file: File) {
        val cell = TILE + GAP
        val cols = PAIRS_PER_ROW * 2
        val rows = (tiles.size + PAIRS_PER_ROW - 1) / PAIRS_PER_ROW
        val sheet = BufferedImage(cols * cell + GAP * PAIRS_PER_ROW, HEADER + rows * cell, BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1c, 0x1c, 0x1c)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.color = Color(0xe6, 0xe6, 0xe6)
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 22)
        g.drawString("$title  (left: Auto, right: preset)", 12, 30)
        tiles.forEachIndexed { i, (a, b) ->
            val x0 = (i % PAIRS_PER_ROW) * (2 * cell + GAP)
            val y0 = HEADER + (i / PAIRS_PER_ROW) * cell
            for ((k, img) in listOf(a, b).withIndex()) {
                val t = fit(img, TILE)
                g.drawImage(t, x0 + k * cell + (TILE - t.width) / 2, y0 + (TILE - t.height) / 2, null)
            }
        }
        g.dispose()
        ImageIO.write(sheet, "jpg", file)
    }

    private fun overviewSheet(names: List<String>, rows: List<List<BufferedImage>>, file: File) {
        val cols = rows.maxOf { it.size }
        val sheet = BufferedImage(NAME_W + cols * (SMALL + GAP), rows.size * (SMALL + GAP), BufferedImage.TYPE_INT_RGB)
        val g = sheet.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x1c, 0x1c, 0x1c)
        g.fillRect(0, 0, sheet.width, sheet.height)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, 16)
        rows.forEachIndexed { r, row ->
            g.color = Color(0xe6, 0xe6, 0xe6)
            g.drawString(names[r], 8, r * (SMALL + GAP) + SMALL / 2)
            row.forEachIndexed { c, img ->
                val t = fit(img, SMALL)
                g.drawImage(
                    t,
                    NAME_W + c * (SMALL + GAP) + (SMALL - t.width) / 2,
                    r * (SMALL + GAP) + (SMALL - t.height) / 2,
                    null,
                )
            }
        }
        g.dispose()
        ImageIO.write(sheet, "jpg", file)
    }

    private companion object {
        const val PHOTOS = 12
        const val OVERVIEW_PHOTOS = 4
        const val SIDE = 900
        const val TILE = 300
        const val SMALL = 150
        const val GAP = 6
        const val HEADER = 44
        const val NAME_W = 200
        const val PAIRS_PER_ROW = 3
        val LIN = FloatArray(256) {
            val v = it / 255.0
            (if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)).toFloat()
        }
    }
}
