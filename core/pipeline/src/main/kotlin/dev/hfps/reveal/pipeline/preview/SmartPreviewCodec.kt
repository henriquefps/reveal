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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A smart preview (ADR 0018 "Smart previews"): the editor's own input at about 2560 px — a [PipelineInput] (linear
 * camera RGB of a RAW, or the linear pixels of a JPEG, upright, with matrix, as-shot white balance, saturation, noise
 * and lens profile) — so a device without the original develops it with the same pipeline, plus a small sRGB JPEG for
 * the grid and the viewer.
 *
 * ```
 * "RVSP" (4 bytes), format version (1 byte: 2 written, 1 and 2 read)
 * u32 header length, header (UTF-8 JSON, [Header]; version 2 adds the quantiser tables `luma` and `chroma`)
 * u32 JPEG length, JPEG bytes (the display rendering, may be empty)
 * pixels: one deflate stream of three planes, green at full resolution, then log2(r/g) and log2(b/g) as 2 x 2 means
 * ```
 *
 * Version 1 ([PlanesV1], 0.5.0-0.5.1): green as 16-bit log codes over 20 stops (0.0003-stop steps), chroma at 12 bits
 * (0.004 stops), left-predicted u16: about 9 MB for a 24 MP RAW, almost all of it the photo's own noise coded
 * exactly. Version 2 ([PlanesV2], 0.5.2): the steps follow the noise the photo already has ([PreviewNoise],
 * [PreviewSteps]: one noise sigma, clamped to 0.0004-0.02 stops for green and 0.004-0.015 for chroma), LOCO-I median
 * prediction, split low/high residual bytes and deflate level 9. Values below 0 (noise under the black level) come
 * back as 0 in both.
 */
object SmartPreviewCodec {
    private val MAGIC = byteArrayOf('R'.code.toByte(), 'V'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())

    /** The version this app writes; [decode] also reads [VERSION_1]. */
    const val VERSION: Int = 2
    const val VERSION_1: Int = 1

    /** The long edge a device makes its previews at. */
    const val LONG_EDGE: Int = 2560

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
    fun encode(input: PipelineInput, jpeg: ByteArray, fullWidth: Int, fullHeight: Int, out: OutputStream) =
        encode(input, jpeg, fullWidth, fullHeight, out, VERSION)

    /**
     * [encode] in a given [version] ([VERSION_1]: what 0.5.0-0.5.1 wrote; tests prove those keep opening), with
     * version 2's [steps] (measurements only; the app uses the default).
     */
    fun encode(
        input: PipelineInput,
        jpeg: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        out: OutputStream,
        version: Int,
        steps: PreviewSteps = PreviewSteps.DEFAULT,
    ) {
        require(version == VERSION || version == VERSION_1) { "unknown version $version" }
        val matrix = List(9) { input.cameraToXyz[it / 3, it % 3] }
        // JSON has no NaN or infinity (kotlinx throws an IllegalArgumentException): a matrix like that cannot be
        // developed anyway, and a lens profile like that is left out (the photo develops without it).
        if (matrix.any { !it.isFinite() }) throw IOException("the colour matrix is not finite")
        var header = Header(
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
        val quantisers = if (version ==
            VERSION
        ) {
            PreviewNoise.quantisers(input.linearRgb, input.width, input.height, steps)
        } else {
            null
        }
        if (quantisers != null) {
            header = header.copy(luma = quantisers.luma.toList(), chroma = quantisers.chroma.toList())
        }
        val data = DataOutputStream(out)
        data.write(MAGIC)
        data.writeByte(version)
        val headerBytes = json.encodeToString(Header.serializer(), header).toByteArray(Charsets.UTF_8)
        data.writeInt(headerBytes.size)
        data.write(headerBytes)
        data.writeInt(jpeg.size)
        data.write(jpeg)
        val deflater = Deflater(if (quantisers != null) Deflater.BEST_COMPRESSION else Deflater.DEFAULT_COMPRESSION)
        DeflaterOutputStream(data, deflater, BUFFER).let { stream ->
            if (quantisers != null) {
                PlanesV2.write(input.linearRgb, input.width, input.height, quantisers, stream)
            } else {
                PlanesV1.write(input.linearRgb, input.width, input.height, stream)
            }
            stream.finish()
        }
        deflater.end()
        data.flush()
    }

    fun encode(input: PipelineInput, jpeg: ByteArray, fullWidth: Int, fullHeight: Int): ByteArray =
        encode(input, jpeg, fullWidth, fullHeight, VERSION)

    /** [encode] in a given [version], to bytes. */
    fun encode(
        input: PipelineInput,
        jpeg: ByteArray,
        fullWidth: Int,
        fullHeight: Int,
        version: Int,
        steps: PreviewSteps = PreviewSteps.DEFAULT,
    ): ByteArray = ByteArrayOutputStream().also {
        encode(input, jpeg, fullWidth, fullHeight, it, version, steps)
    }.toByteArray()

    /** What a smart preview holds: the input, the display JPEG and the original's size. */
    class Decoded(val input: PipelineInput, val jpeg: ByteArray, val fullWidth: Int, val fullHeight: Int)

    /** Reads a smart preview. Throws [IOException] when it is not one or is damaged. */
    fun decode(input: InputStream): Decoded {
        val data = DataInputStream(input.buffered(BUFFER))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!isSmartPreview(magic)) throw IOException("not a smart preview")
        val version = data.readUnsignedByte()
        if (version !in VERSION_1..VERSION) throw IOException("smart preview version $version is newer than this app")
        val headerSize = data.readInt()
        if (headerSize !in 1..MAX_HEADER) throw IOException("bad header")
        val headerText = String(ByteArray(headerSize).also(data::readFully), Charsets.UTF_8)
        val header = runCatching { json.decodeFromString(Header.serializer(), headerText) }
            .getOrElse { throw IOException("bad header", it) }
        if (header.width <= 0 || header.height <= 0 || header.width.toLong() * header.height > MAX_PIXELS) {
            throw IOException("bad size")
        }
        val jpegSize = data.readInt()
        if (jpegSize !in 0..MAX_JPEG) throw IOException("bad jpeg")
        val jpeg = ByteArray(jpegSize).also(data::readFully)
        val quantisers = if (version == VERSION) quantisers(header) else null
        val pixels = InflaterInputStream(data, java.util.zip.Inflater(), BUFFER).use {
            if (quantisers != null) {
                PlanesV2.read(header.width, header.height, quantisers, it)
            } else {
                PlanesV1.read(header.width, header.height, it)
            }
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

    private fun quantisers(header: Header): Quantisers = Quantisers(
        header.luma?.toIntArray() ?: throw IOException("no quantisers"),
        header.chroma?.toIntArray() ?: throw IOException("no quantisers"),
    )

    /** The format version of a smart preview. Throws [IOException] when it is not one. */
    fun version(input: InputStream): Int {
        val head = ByteArray(MAGIC.size + 1)
        DataInputStream(input).readFully(head)
        if (!isSmartPreview(head)) throw IOException("not a smart preview")
        return head[MAGIC.size].toInt() and 0xFF
    }

    /** The pixel size (width, height) of a smart preview, from its header. Throws [IOException] when it is not one. */
    fun size(input: InputStream): Pair<Int, Int> {
        val data = DataInputStream(input.buffered(BUFFER))
        val magic = ByteArray(MAGIC.size)
        data.readFully(magic)
        if (!isSmartPreview(magic) ||
            data.readUnsignedByte() !in VERSION_1..VERSION
        ) {
            throw IOException("not a smart preview")
        }
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
        if (!isSmartPreview(magic) ||
            data.readUnsignedByte() !in VERSION_1..VERSION
        ) {
            throw IOException("not a smart preview")
        }
        val headerSize = data.readInt()
        if (headerSize !in 1..MAX_HEADER) throw IOException("bad header")
        data.skipNBytesCompat(headerSize.toLong())
        val jpegSize = data.readInt()
        if (jpegSize !in 0..MAX_JPEG) throw IOException("bad jpeg")
        return ByteArray(jpegSize).also(data::readFully)
    }

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
        /** Version 2: green codes per level bin ([Quantisers.luma]). */
        val luma: List<Int>? = null,
        /** Version 2: chroma steps per stop per level bin ([Quantisers.chroma]). */
        val chroma: List<Int>? = null,
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
