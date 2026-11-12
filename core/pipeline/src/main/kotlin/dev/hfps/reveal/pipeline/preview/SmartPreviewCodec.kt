@file:Suppress("MagicNumber", "NestedBlockDepth", "ThrowsCount", "SpreadOperator")

package dev.hfps.reveal.pipeline.preview

import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.NoiseInfo
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.Mat3
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.optics.LensfunDistortion
import dev.hfps.reveal.pipeline.optics.LensfunProfile
import dev.hfps.reveal.pipeline.optics.LensfunTca
import dev.hfps.reveal.pipeline.optics.SonyLensProfile
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A smart preview (ADR 0018 "Smart previews"): the editor's own input at about 2560 px — a [PipelineInput] (linear
 * camera RGB of a RAW, or the linear pixels of a JPEG, upright, with matrix, as-shot white balance, saturation, noise
 * and lens profile) — so a device without the original develops it with the same pipeline, plus a small sRGB JPEG for
 * the grid and the viewer.
 *
 * ```
 * "RVSP" (4 bytes), format version (1 byte = 1)
 * u32 header length, header (UTF-8 JSON, [Header])
 * u32 JPEG length, JPEG bytes (the display rendering, may be empty)
 * pixels: deflate stream of three planes, each row-predicted from its left neighbour, u16 little-endian:
 *   Y  = round((log2(max(g, 0) + 2^-16) + 16) * 65535 / 20)           full resolution (green, 20 stops)
 *   Cr = round((log2(r') - log2(g') + 8) * 4095 / 16), Cb likewise   half resolution (2 x 2 means of the logs)
 * ```
 *
 * Luma keeps 0.0003 stop steps (invisible after any edit); chroma is quantised to 0.004 stops at half resolution,
 * like the colour of a JPEG. Values below 0 (noise under the black level) come back as 0.
 */
object SmartPreviewCodec {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())
    const val VERSION: Int = 1

    /** The long edge a device makes its previews at. */
    const val LONG_EDGE: Int = 2560

    private const val FLOOR = 1.0 / 65536.0
    private const val Y_STOPS = 20.0
    private const val Y_LOW = -16.0
    private const val Y_MAX = 65535.0
    private const val C_STOPS = 16.0
    private const val C_MAX = 4095.0
    private const val MAX_HEADER = 1 shl 20
    private const val MAX_JPEG = 32 shl 20
    private const val MAX_PIXELS = 40_000_000L

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** True when [head] (the first bytes of a file) starts a smart preview. */
    fun isSmartPreview(head: ByteArray): Boolean =
        head.size >= MAGIC.size && MAGIC.indices.all { head[it] == MAGIC[it] }

    /**
     * Writes [input] and the display [jpeg] (may be empty). [fullWidth] x [fullHeight] is the original's upright size.
     */
    fun encode(input: PipelineInput, jpeg: ByteArray, fullWidth: Int, fullHeight: Int, out: OutputStream) {
        val matrix = List(9) { input.cameraToXyz[it / 3, it % 3] }
        // JSON has no NaN or infinity (kotlinx throws an IllegalArgumentException): a matrix like that cannot be
        // developed anyway, and a lens profile like that is left out (the photo develops without it).
        if (matrix.any { !it.isFinite() }) throw IOException("the colour matrix is not finite")
        val header = Header(
            kind = input.kind.name,
            width = input.width,
            height = input.height,
            fullWidth = fullWidth,
            fullHeight = fullHeight,
            cameraToXyz = matrix,
            asShot = input.asShotMultipliers?.toList(),
            saturation = input.saturation,
            noise = input.noise?.let { NoiseHeader(it.iso, it.make, it.model) },
            lens = input.lens?.let(::lensHeader)?.takeIf(::finite),
        )
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(VERSION)
        val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
        data.writeInt(headerBytes.size)
        data.write(headerBytes)
        data.writeInt(jpeg.size)
        data.write(jpeg)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        DeflaterOutputStream(data, deflater, BUFFER).let { stream ->
            writePlanes(input, stream)
            stream.finish()
        }
        deflater.end()
        data.flush()
    }

    fun encode(input: PipelineInput, jpeg: ByteArray, fullWidth: Int, fullHeight: Int): ByteArray =
        ByteArrayOutputStream().also { encode(input, jpeg, fullWidth, fullHeight, it) }.toByteArray()

    /** What a smart preview holds: the input, the display JPEG and the original's size. */
    class Decoded(val input: PipelineInput, val jpeg: ByteArray, val fullWidth: Int, val fullHeight: Int)

    /** Reads a smart preview. Throws [IOException] when it is not one or is damaged. */
    fun decode(input: InputStream): Decoded {
        val data = DataInputStream(input.buffered(BUFFER))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!isSmartPreview(magic)) throw IOException("not a smart preview")
        val version = data.readUnsignedByte()
        if (version != VERSION) throw IOException("smart preview version $version is newer than this app")
        val headerSize = data.readInt()
        if (headerSize !in 1..MAX_HEADER) throw IOException("bad header")
        val header = json.decodeFromString(
            Header.serializer(),
            String(ByteArray(headerSize).also(data::readFully), Charsets.UTF_8),
        )
        if (header.width <= 0 || header.height <= 0 || header.width.toLong() * header.height > MAX_PIXELS) {
            throw IOException("bad size")
        }
        val jpegSize = data.readInt()
        if (jpegSize !in 0..MAX_JPEG) throw IOException("bad jpeg")
        val jpeg = ByteArray(jpegSize).also(data::readFully)
        val pixels = InflaterInputStream(data, java.util.zip.Inflater(), BUFFER).use {
            readPlanes(header.width, header.height, it)
        }
        val m = header.cameraToXyz
        if (m.size != 9) throw IOException("bad matrix")
        val decoded = PipelineInput(
            kind = InputKind.entries.firstOrNull { it.name == header.kind } ?: throw IOException("bad kind"),
            width = header.width,
            height = header.height,
            linearRgb = pixels,
            cameraToXyz = Mat3(*m.toDoubleArray()),
            asShotMultipliers = header.asShot?.toFloatArray(),
            saturation = header.saturation,
            noise = header.noise?.let { NoiseInfo(it.iso, it.make, it.model) },
            lens = header.lens?.let(::lensProfile),
        )
        return Decoded(
            decoded,
            jpeg,
            header.fullWidth.coerceAtLeast(header.width),
            header.fullHeight.coerceAtLeast(header.height),
        )
    }

    /** The pixel size (width, height) of a smart preview, from its header. Throws [IOException] when it is not one. */
    fun size(input: InputStream): Pair<Int, Int> {
        val data = DataInputStream(input.buffered(BUFFER))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!isSmartPreview(magic) || data.readUnsignedByte() != VERSION) throw IOException("not a smart preview")
        val headerSize = data.readInt()
        if (headerSize !in 1..MAX_HEADER) throw IOException("bad header")
        val header = runCatching {
            json.decodeFromString(
                Header.serializer(),
                String(ByteArray(headerSize).also(data::readFully), Charsets.UTF_8),
            )
        }.getOrElse { throw IOException("bad header", it) }
        return header.width to header.height
    }

    /** Only the display JPEG of a smart preview (the grid and the viewer of a preview-only photo). */
    fun readJpeg(input: InputStream): ByteArray {
        val data = DataInputStream(input.buffered(BUFFER))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!isSmartPreview(magic) || data.readUnsignedByte() != VERSION) throw IOException("not a smart preview")
        val headerSize = data.readInt()
        if (headerSize !in 1..MAX_HEADER) throw IOException("bad header")
        data.skipNBytesCompat(headerSize.toLong())
        val jpegSize = data.readInt()
        if (jpegSize !in 0..MAX_JPEG) throw IOException("bad jpeg")
        return ByteArray(jpegSize).also(data::readFully)
    }

    // ------------------------------------------------------------------ planes

    /** A sample as a non-negative finite value: NaN (never expected) is 0, so it can never fail an encode. */
    private fun clean(v: Float): Double = if (v > 0f) minOf(v.toDouble(), Float.MAX_VALUE.toDouble()) else 0.0

    private fun lumaCode(v: Float): Int {
        val l = ln(clean(v) + FLOOR) / LN2
        return ((l - Y_LOW) * Y_MAX / Y_STOPS).roundToInt().coerceIn(0, Y_MAX.toInt())
    }

    private fun lumaValue(code: Int): Float = (exp((code * Y_STOPS / Y_MAX + Y_LOW) * LN2) - FLOOR).toFloat()

    private fun log2(v: Float): Double = ln(clean(v) + FLOOR) / LN2

    private fun chromaCode(diff: Double): Int =
        ((diff + C_STOPS / 2) * C_MAX / C_STOPS).roundToInt().coerceIn(0, C_MAX.toInt())

    private fun chromaValue(code: Int): Double = code * C_STOPS / C_MAX - C_STOPS / 2

    private fun writePlanes(input: PipelineInput, out: OutputStream) {
        val w = input.width
        val h = input.height
        val px = input.linearRgb
        val row = ByteArray(w * 2)
        val luma = IntArray(w * h)
        for (y in 0 until h) {
            var prev = 0
            for (x in 0 until w) {
                val i = y * w + x
                val code = lumaCode(px[i * 3 + 1])
                luma[i] = code
                putU16(row, x, code - prev)
                prev = code
            }
            out.write(row, 0, w * 2)
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val crow = ByteArray(cw * 2)
        // Chroma: the mean over 2 x 2 of log2(c) - log2(g), as the decoded luma will be (quantised g).
        for (channel in intArrayOf(0, 2)) {
            for (cy in 0 until ch) {
                var prev = 0
                for (cx in 0 until cw) {
                    var sum = 0.0
                    var n = 0
                    for (dy in 0..1) {
                        val y = cy * 2 + dy
                        if (y >= h) continue
                        for (dx in 0..1) {
                            val x = cx * 2 + dx
                            if (x >= w) continue
                            val i = y * w + x
                            sum += log2(px[i * 3 + channel]) - (luma[i] * Y_STOPS / Y_MAX + Y_LOW)
                            n++
                        }
                    }
                    val code = chromaCode(sum / n)
                    putU16(crow, cx, code - prev)
                    prev = code
                }
                out.write(crow, 0, cw * 2)
            }
        }
    }

    private fun readPlanes(w: Int, h: Int, input: InputStream): FloatArray {
        val data = DataInputStream(input)
        val out = FloatArray(w * h * 3)
        val lumaLog = FloatArray(w * h)
        val row = ByteArray(w * 2)
        for (y in 0 until h) {
            data.readFully(row)
            var code = 0
            for (x in 0 until w) {
                code = (code + getU16(row, x)) and 0xFFFF
                val i = y * w + x
                out[i * 3 + 1] = lumaValue(code)
                lumaLog[i] = (code * Y_STOPS / Y_MAX + Y_LOW).toFloat()
            }
        }
        val cw = (w + 1) / 2
        val ch = (h + 1) / 2
        val crow = ByteArray(cw * 2)
        for (channel in intArrayOf(0, 2)) {
            for (cy in 0 until ch) {
                data.readFully(crow)
                var code = 0
                for (cx in 0 until cw) {
                    code = (code + getU16(crow, cx)) and 0xFFFF
                    val diff = chromaValue(code)
                    for (dy in 0..1) {
                        val y = cy * 2 + dy
                        if (y >= h) continue
                        for (dx in 0..1) {
                            val x = cx * 2 + dx
                            if (x >= w) continue
                            val i = y * w + x
                            out[i * 3 + channel] = (exp((lumaLog[i] + diff) * LN2) - FLOOR).toFloat().coerceAtLeast(0f)
                        }
                    }
                }
            }
        }
        for (i in out.indices step 3) out[i + 1] = out[i + 1].coerceAtLeast(0f)
        return out
    }

    private fun putU16(buffer: ByteArray, index: Int, value: Int) {
        buffer[index * 2] = (value and 0xFF).toByte()
        buffer[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun getU16(buffer: ByteArray, index: Int): Int =
        (buffer[index * 2].toInt() and 0xFF) or ((buffer[index * 2 + 1].toInt() and 0xFF) shl 8)

    private val LN2 = ln(2.0)
    private const val BUFFER = 64 * 1024

    private fun DataInputStream.skipNBytesCompat(n: Long) {
        var left = n
        while (left > 0) {
            val skipped = skip(left)
            if (skipped <= 0) {
                if (read() < 0) throw IOException("truncated")
                left--
            } else {
                left -= skipped
            }
        }
    }

    // ------------------------------------------------------------------ header

    @Serializable
    private data class Header(
        val kind: String,
        val width: Int,
        val height: Int,
        val fullWidth: Int,
        val fullHeight: Int,
        val cameraToXyz: List<Double>,
        val asShot: List<Float>? = null,
        val saturation: Float? = null,
        val noise: NoiseHeader? = null,
        val lens: LensHeader? = null,
    )

    @Serializable
    private data class NoiseHeader(val iso: Int? = null, val make: String? = null, val model: String? = null)

    @Serializable
    private data class LensHeader(
        val type: String,
        val name: String,
        val distortion: List<Int>? = null,
        val red: List<Int>? = null,
        val blue: List<Int>? = null,
        val vignetting: List<Int>? = null,
        val distortionModel: String? = null,
        val distortionTerms: List<Double>? = null,
        val tcaModel: String? = null,
        val tcaTerms: List<Double>? = null,
        val vignettingTerms: List<Double>? = null,
        val cropCalibration: Double? = null,
        val aspectCalibration: Double? = null,
        val cropImage: Double? = null,
    )

    private fun finite(header: LensHeader): Boolean = listOfNotNull(
        header.distortionTerms,
        header.tcaTerms,
        header.vignettingTerms,
        listOfNotNull(header.cropCalibration, header.aspectCalibration, header.cropImage),
    ).flatten().all { it.isFinite() }

    private fun lensHeader(lens: LensProfile): LensHeader? = when (lens) {
        is SonyLensProfile -> LensHeader(
            type = "sony",
            name = lens.name,
            distortion = lens.distortionKnots?.toList(),
            red = lens.redKnots?.toList(),
            blue = lens.blueKnots?.toList(),
            vignetting = lens.vignettingKnots?.toList(),
        )

        is LensfunProfile -> LensHeader(
            type = "lensfun",
            name = lens.name,
            distortionModel = lens.distortionModel.name,
            distortionTerms = lens.distortionTerms,
            tcaModel = lens.tcaModel.name,
            tcaTerms = lens.tcaTerms,
            vignettingTerms = lens.vignettingTerms,
            cropCalibration = lens.cropCalibration,
            aspectCalibration = lens.aspectCalibration,
            cropImage = lens.cropImage,
        )
    }

    private fun lensProfile(header: LensHeader): LensProfile? = runCatching {
        when (header.type) {
            "sony" -> SonyLensProfile(
                header.name,
                header.distortion?.toIntArray(),
                header.red?.toIntArray(),
                header.blue?.toIntArray(),
                header.vignetting?.toIntArray(),
            )

            "lensfun" -> LensfunProfile(
                name = header.name,
                distortionModel = LensfunDistortion.valueOf(checkNotNull(header.distortionModel)),
                distortionTerms = header.distortionTerms.orEmpty(),
                tcaModel = LensfunTca.valueOf(checkNotNull(header.tcaModel)),
                tcaTerms = header.tcaTerms.orEmpty(),
                vignettingTerms = header.vignettingTerms.orEmpty(),
                cropCalibration = checkNotNull(header.cropCalibration),
                aspectCalibration = checkNotNull(header.aspectCalibration),
                cropImage = checkNotNull(header.cropImage),
            )

            else -> null
        }
    }.getOrNull()
}
