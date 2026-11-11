@file:Suppress("MagicNumber", "NestedBlockDepth", "ComplexCondition", "ReturnCount", "LoopWithTooManyJumpStatements")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.image.LinearImage
import dev.hfps.image.color.CfaKind
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.PixelRect
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.color.RawLevels
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.DataInputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * One sampled patch of a RAW+JPEG pair: Reveal's scene-linear render of the RAW (linear Rec.2020 after the as-shot
 * white balance and the camera matrix, before exposure and the profile) and the camera JPEG's linear sRGB, both
 * averaged over the same block. [radius] is the distance from the image centre, 1 = the corner.
 */
class Patch(val pair: Int, val reveal: DoubleArray, val jpeg: DoubleArray, val radius: Double)

/**
 * Reads the calibration pairs: `<name>.rdump` (written by `scripts/calibration/rawdump.sh` from a COPY of the ARW)
 * next to `<name>.JPG` (a copy of the camera JPEG). The RAW goes through the app's own colour path
 * ([PipelineInputMapper] for the as-shot multipliers, saturation and camera matrix, [DevelopParams] for the white
 * balance and camera-to-working matrix), so the fit sees exactly what the pipeline sees.
 *
 * Alignment: LibRaw's half-size image covers the active area (6024 x 4024 for the ZV-E10, 3012 x 2012 at half size);
 * the camera JPEG (6000 x 4000) is its centred crop. The offset is confirmed per pair by a small correlation search.
 * Patches are blocks of [BLOCK] half-size pixels kept only where the JPEG is smooth (so a residual misalignment or the
 * camera's sharpening cannot bias the mean), unclipped in both images and not black.
 */
object CalibrationPairs {
    private const val BLOCK = 8
    private const val MAX_JPEG_STD = 2.5 // 8-bit levels of luma inside the block
    private const val MAX_JPEG_LEVEL = 250
    private const val MIN_JPEG_LEVEL = 3
    private const val RAW_CLIP_MARGIN = 0.9

    /** Pairs in [dir], sorted by name; only the index is ever reported. */
    fun pairs(dir: File): List<Pair<File, File>> = dir.listFiles()!!.filter { it.name.endsWith(".rdump") }
        .sortedBy { it.name }
        .mapNotNull { dump ->
            val base = dump.name.removeSuffix(".rdump")
            val jpg = listOf("JPG", "jpg", "JPEG", "jpeg").map { File(dir, "$base.$it") }.firstOrNull { it.isFile }
            jpg?.let { dump to it }
        }

    /** Every usable patch of one pair. */
    fun patches(index: Int, dump: File, jpg: File): List<Patch> {
        val raw = RawDump.read(dump)
        val input = raw.colourInput()
        val params = DevelopParams.from(input, Recipe())
        val m = params.cameraToWorking
        val mul = params.wbMultipliers
        val clip = (params.highlights?.let { minOf(it.clipR, it.clipG, it.clipB) } ?: Float.MAX_VALUE) * RAW_CLIP_MARGIN
        val image = ImageIO.read(jpg)
        val jw = image.width
        val jh = image.height
        val rgb = IntArray(jw * jh)
        image.getRGB(0, 0, jw, jh, rgb, 0, jw)
        val (ox, oy) = align(raw, rgb, jw, jh, mul)
        val out = ArrayList<Patch>()
        val bw = (jw / 2) / BLOCK
        val bh = (jh / 2) / BLOCK
        val cx = jw / 2.0
        val cy = jh / 2.0
        val corner = sqrt(cx * cx + cy * cy)
        for (by in 0 until bh) {
            for (bx in 0 until bw) {
                val jx0 = bx * BLOCK * 2
                val jy0 = by * BLOCK * 2
                val j = jpegBlock(rgb, jw, jx0, jy0, BLOCK * 2) ?: continue
                val r = raw.blockMean(jx0 / 2 + ox, jy0 / 2 + oy, BLOCK) ?: continue
                val balanced = DoubleArray(3) { r[it] * mul[it] }
                if (balanced.any { it > clip } || raw.blockMax(jx0 / 2 + ox, jy0 / 2 + oy, BLOCK, mul) > clip) continue
                val reveal = DoubleArray(3) { c ->
                    m[c * 3] * balanced[0] + m[c * 3 + 1] * balanced[1] + m[c * 3 + 2] * balanced[2]
                }
                val dx = jx0 + BLOCK - cx
                val dy = jy0 + BLOCK - cy
                out += Patch(index, reveal, j, sqrt(dx * dx + dy * dy) / corner)
            }
        }
        return out
    }

    /** Mean linear sRGB of a smooth, unclipped JPEG block, or null. */
    private fun jpegBlock(rgb: IntArray, w: Int, x0: Int, y0: Int, size: Int): DoubleArray? {
        val sum = DoubleArray(3)
        var lumaSum = 0.0
        var luma2 = 0.0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) {
                val p = rgb[y * w + x]
                val r = p shr 16 and 0xff
                val g = p shr 8 and 0xff
                val b = p and 0xff
                if (maxOf(r, g, b) > MAX_JPEG_LEVEL || minOf(r, g, b) < MIN_JPEG_LEVEL) return null
                sum[0] += SRGB_TO_LINEAR[r]
                sum[1] += SRGB_TO_LINEAR[g]
                sum[2] += SRGB_TO_LINEAR[b]
                val l = 0.2126 * r + 0.7152 * g + 0.0722 * b
                lumaSum += l
                luma2 += l * l
            }
        }
        val n = (size * size).toDouble()
        val mean = lumaSum / n
        val std = sqrt((luma2 / n - mean * mean).coerceAtLeast(0.0))
        if (std > MAX_JPEG_STD) return null
        return DoubleArray(3) { sum[it] / n }
    }

    /**
     * Offset (half-size pixels) of the JPEG's top-left in the RAW, found by minimising the difference of
     * log-luminance gradients over a coarse grid around the nominal centred crop.
     */
    private fun align(raw: RawDump, rgb: IntArray, jw: Int, jh: Int, mul: FloatArray): Pair<Int, Int> {
        val nominalX = (raw.width - jw / 2) / 2
        val nominalY = (raw.height - jh / 2) / 2
        val step = 16
        val gw = (jw / 2) / step - 2
        val gh = (jh / 2) / step - 2
        val jl =
            Array(gh) { gy -> DoubleArray(gw) { gx -> jpegLogLuma(rgb, jw, (gx + 1) * step * 2, (gy + 1) * step * 2) } }
        var best = nominalX to nominalY
        var bestScore = Double.MAX_VALUE
        for (dy in -SEARCH..SEARCH) {
            for (dx in -SEARCH..SEARCH) {
                var score = 0.0
                for (gy in 0 until gh - 1) {
                    for (gx in 0 until gw - 1) {
                        val x = (gx + 1) * step + nominalX + dx
                        val y = (gy + 1) * step + nominalY + dy
                        val a = raw.logLuma(x, y, mul)
                        val gxR = raw.logLuma(x + step, y, mul) - a
                        val gyR = raw.logLuma(x, y + step, mul) - a
                        val gxJ = jl[gy][gx + 1] - jl[gy][gx]
                        val gyJ = jl[gy + 1][gx] - jl[gy][gx]
                        score += abs(gxR - gxJ) + abs(gyR - gyJ)
                    }
                }
                if (score < bestScore) {
                    bestScore = score
                    best = (nominalX + dx) to (nominalY + dy)
                }
            }
        }
        return best
    }

    private fun jpegLogLuma(rgb: IntArray, w: Int, x: Int, y: Int): Double {
        var s = 0.0
        for (yy in y until y + 2) {
            for (xx in x until x + 2) {
                val p = rgb[yy * w + xx]
                s += 0.2126 * SRGB_TO_LINEAR[p shr 16 and 0xff] + 0.7152 * SRGB_TO_LINEAR[p shr 8 and 0xff] +
                    0.0722 * SRGB_TO_LINEAR[p and 0xff]
            }
        }
        return kotlin.math.ln(s / 4 + 1e-4)
    }

    private const val SEARCH = 4

    private val SRGB_TO_LINEAR = DoubleArray(256) {
        val v = it / 255.0
        if (v <= 0.04045) v / 12.92 else StrictMath.pow((v + 0.055) / 1.055, 2.4)
    }
}

/** The host dump of one RAW (see `scripts/calibration/rawdump.cpp`). */
class RawDump(val width: Int, val height: Int, private val data: ShortArray, private val info: RawColorInfo) {
    /** A 1x1 pipeline input carrying this RAW's colour data, through the app's own mapper. */
    fun colourInput(): PipelineInput {
        val mapped = PipelineInputMapper.map(LinearImage(1, 1, ShortArray(3)), info, 1)
        return PipelineInput(
            InputKind.SCENE_REFERRED,
            1,
            1,
            FloatArray(3),
            mapped.cameraToXyz,
            mapped.asShotMultipliers,
            mapped.saturation,
        )
    }

    /** The whole image box-downsampled by [factor], through the app's mapper (a real RAW pipeline input). */
    fun pipelineInput(factor: Int): PipelineInput {
        val w = width / factor
        val h = height / factor
        val out = ShortArray(w * h * 3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                for (c in 0..2) {
                    var s = 0L
                    for (yy in 0 until factor) {
                        for (xx in 0 until factor) {
                            s +=
                                data[((y * factor + yy) * width + x * factor + xx) * 3 + c].toInt() and 0xffff
                        }
                    }
                    out[(y * w + x) * 3 + c] = (s / (factor * factor)).toShort()
                }
            }
        }
        return PipelineInputMapper.map(LinearImage(w, h, out), info, 1)
    }

    fun value(x: Int, y: Int, c: Int): Double = (data[(y * width + x) * 3 + c].toInt() and 0xffff) / 65535.0

    fun blockMean(x0: Int, y0: Int, size: Int): DoubleArray? {
        if (x0 < 0 || y0 < 0 || x0 + size > width || y0 + size > height) return null
        val s = DoubleArray(3)
        for (y in y0 until y0 + size) for (x in x0 until x0 + size) for (c in 0..2) s[c] += value(x, y, c)
        return DoubleArray(3) { s[it] / (size * size) }
    }

    fun blockMax(x0: Int, y0: Int, size: Int, mul: FloatArray): Double {
        var m = 0.0
        for (y in y0 until y0 + size) {
            for (x in x0 until x0 + size) {
                for (c in 0..2) {
                    m =
                        maxOf(m, value(x, y, c) * mul[c])
                }
            }
        }
        return m
    }

    fun logLuma(x: Int, y: Int, mul: FloatArray): Double {
        if (x < 0 || y < 0 || x + 1 >= width || y + 1 >= height) return 0.0
        var s = 0.0
        for (yy in y..y + 1) {
            for (xx in x..x + 1) {
                s +=
                    0.25 * value(xx, yy, 0) * mul[0] + 0.6 * value(xx, yy, 1) * mul[1] +
                    0.15 * value(xx, yy, 2) * mul[2]
            }
        }
        return kotlin.math.ln(s / 4 + 1e-4)
    }

    companion object {
        fun read(file: File): RawDump {
            DataInputStream(file.inputStream().buffered(1 shl 20)).use { input ->
                val header = HashMap<String, List<String>>()
                while (true) {
                    val line = readLine(input)
                    if (line == "data") break
                    val parts = line.split(' ')
                    header[parts[0]] = parts.drop(1)
                }
                val (w, h) = header.getValue("size").map { it.toInt() }
                val bytes = ByteArray(w * h * 3 * 2)
                input.readFully(bytes)
                val shorts = ShortArray(w * h * 3)
                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
                val d = { key: String -> header.getValue(key).map { it.toDouble() } }
                val black = d("black")[0].toInt()
                val cblack = d("cblack").map { it.toInt() }
                val maximum = d("maximum")[0].toInt()
                val linearMax = d("linear_max").map { it.toInt() }
                val camMul = d("cam_mul")
                val info = RawColorInfo(
                    make = "Sony",
                    model = header["model"]?.joinToString(" "),
                    normalizedMake = null,
                    normalizedModel = null,
                    libRawVersion = "host",
                    cameraToXyz = ColorMath.cameraToXyzD65(d("rgb_cam"), d("pre_mul")),
                    asShotWhiteBalance = ColorMath.normalizeToGreen(camMul[0], camMul[1], camMul[2]),
                    daylightWhiteBalance = null,
                    levels = RawLevels(
                        black = List(4) { black + cblack[it] },
                        white = maximum,
                        dataMaximumAboveBlack = 0,
                        saturation = List(4) { if (linearMax[it] in 1..maximum) linearMax[it] else maximum },
                        bitsPerSample = 14,
                        isFinal = true,
                    ),
                    baselineExposureEv = null,
                    orientation = 1,
                    sensorWidth = w * 2,
                    sensorHeight = h * 2,
                    activeArea = PixelRect(0, 0, w * 2, h * 2),
                    defaultCrop = null,
                    cfa = CfaKind.BAYER,
                    cfaPattern = "RGGB",
                    isDng = false,
                    iso = null,
                )
                return RawDump(w, h, shorts, info)
            }
        }

        private fun readLine(input: DataInputStream): String {
            val sb = StringBuilder()
            while (true) {
                val b = input.read()
                if (b < 0 || b == '\n'.code) return sb.toString()
                sb.append(b.toChar())
            }
        }
    }
}
