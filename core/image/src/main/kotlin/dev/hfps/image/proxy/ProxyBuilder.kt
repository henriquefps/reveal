package dev.hfps.image.proxy

import dev.hfps.image.ImageInput
import dev.hfps.image.LinearImage
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.PixelRect
import dev.hfps.image.format.ImageKind
import dev.hfps.image.raw.Demosaic
import dev.hfps.image.raw.RawDecodeOptions
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.raw.RawPhase
import dev.hfps.image.source.CancelToken
import dev.hfps.image.standard.StandardDecodeOptions
import dev.hfps.image.standard.StandardDecoder
import java.io.IOException

/** How a proxy was produced. */
enum class ProxySource {
    /** LibRaw half-size (2x2 binning) decode, then area-averaged down. */
    RAW_HALF,

    /** LibRaw full demosaic, then area-averaged down (the request was larger than half size). */
    RAW_FULL,

    /** JPEG/HEIF/PNG/WebP via the platform decoder with power-of-two subsampling, then area-averaged down. */
    STANDARD,
}

/** Steps of a proxy build, in order, for a progress indicator. RAW files go through every step. */
enum class ProxyStep {
    /** Reading the header and opening the decoder. */
    OPEN,

    /** RAW only: reading and decompressing the sensor data. */
    UNPACK,

    /** Demosaic / binning (RAW) or the platform decode (other formats). */
    DECODE,

    /** Area-average resampling to the requested size. */
    RESAMPLE,
}

/** Timing and sizes of a proxy build. */
data class ProxyStats(
    val source: ProxySource,
    /** Pixel size of the decoder output before resampling. */
    val decodedWidth: Int,
    val decodedHeight: Int,
    val decodeMs: Long,
    val resampleMs: Long,
    val totalMs: Long,
    /** Size of the whole image at full resolution (stored orientation): the RAW's active area, the file's size. */
    val fullWidth: Int = decodedWidth,
    val fullHeight: Int = decodedHeight,
)

/** A proxy: [image] (about the requested long edge), what it means ([info]) and how it was made ([stats]). */
data class ProxyResult(val image: LinearImage, val info: ColorInfo, val stats: ProxyStats)

/** Builds editing proxies: linear images of about a requested size, as fast as the format allows. */
object ProxyBuilder {

    /**
     * Decodes [input] to a linear image whose long edge is [longEdge] (or the image's own long edge when smaller:
     * never upscaled). RAW files use LibRaw half-size when it is large enough, else a full decode; other formats use
     * the platform decoder's power-of-two subsampling. The result is then area-averaged to the exact target.
     */
    fun decodeProxy(
        input: ImageInput,
        longEdge: Int,
        cancel: CancelToken = CancelToken.NONE,
        demosaic: Demosaic = Demosaic.DEFAULT,
        onStep: (ProxyStep) -> Unit = {},
    ): ProxyResult {
        require(longEdge > 0) { "longEdge must be positive: $longEdge" }
        val started = System.nanoTime()
        onStep(ProxyStep.OPEN)
        return when (input.type.kind) {
            ImageKind.RAW -> rawProxy(input, longEdge, cancel, demosaic, started, onStep)
            ImageKind.OTHER -> throw IOException("Not an image: ${input.name}")
            else -> standardProxy(input, longEdge, cancel, started, onStep)
        }
    }

    private fun rawProxy(
        input: ImageInput,
        longEdge: Int,
        cancel: CancelToken,
        demosaic: Demosaic,
        started: Long,
        onStep: (ProxyStep) -> Unit,
    ): ProxyResult {
        // Half size has half the active area's pixels per axis: use it when that still covers the request.
        val header = RawDecoder.readInfo(input.descriptor)
        val halfLong = (maxOf(header.activeArea.width, header.activeArea.height) + 1) / 2
        val half = halfLong >= longEdge
        val options = RawDecodeOptions(halfSize = half, demosaic = demosaic)
        // Decodes stay in native memory: a full-size image (144 MB at 24 MP) does not fit a default Java heap.
        val decoded = RawDecoder.decodeNative(input.descriptor, options, cancel) { phase ->
            when (phase) {
                RawPhase.UNPACK -> onStep(ProxyStep.UNPACK)
                RawPhase.PROCESS -> onStep(ProxyStep.DECODE)
                else -> Unit
            }
        }
        decoded.image.use { native ->
            cancel.throwIfCancelled()
            onStep(ProxyStep.RESAMPLE)
            val decodeEnd = System.nanoTime()
            val size = LinearResample.targetSize(native.width, native.height, longEdge)
            val resampled = LinearResample.downscale(native, size.width, size.height)
            val end = System.nanoTime()
            return ProxyResult(
                resampled,
                decoded.info,
                ProxyStats(
                    if (half) ProxySource.RAW_HALF else ProxySource.RAW_FULL,
                    native.width,
                    native.height,
                    (decodeEnd - started) / NANOS_PER_MILLI,
                    (end - decodeEnd) / NANOS_PER_MILLI,
                    (end - started) / NANOS_PER_MILLI,
                    header.activeArea.width,
                    header.activeArea.height,
                ),
            )
        }
    }

    private fun standardProxy(
        input: ImageInput,
        longEdge: Int,
        cancel: CancelToken,
        started: Long,
        onStep: (ProxyStep) -> Unit,
    ): ProxyResult {
        onStep(ProxyStep.DECODE)
        val decoded = StandardDecoder.decode(
            input.descriptor,
            StandardDecodeOptions(region = null as PixelRect?, minLongEdge = longEdge),
        )
        cancel.throwIfCancelled()
        onStep(ProxyStep.RESAMPLE)
        val decodeEnd = System.nanoTime()
        val resampled = LinearResample.fitLongEdge(decoded.image, longEdge)
        val end = System.nanoTime()
        return ProxyResult(
            resampled,
            decoded.info,
            ProxyStats(
                ProxySource.STANDARD,
                decoded.image.width,
                decoded.image.height,
                (decodeEnd - started) / NANOS_PER_MILLI,
                (end - decodeEnd) / NANOS_PER_MILLI,
                (end - started) / NANOS_PER_MILLI,
                decoded.sourceWidth,
                decoded.sourceHeight,
            ),
        )
    }

    private const val NANOS_PER_MILLI = 1_000_000L
}
