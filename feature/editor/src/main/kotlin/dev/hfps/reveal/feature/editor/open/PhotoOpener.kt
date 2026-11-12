package dev.hfps.reveal.feature.editor.open

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ImageKind
import dev.hfps.image.preview.FirstImageLoader
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.proxy.ProxySource
import dev.hfps.image.proxy.ProxyStats
import dev.hfps.image.proxy.ProxyStep
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.CancelledException
import dev.hfps.image.source.SourceInputStream
import dev.hfps.image.source.readRange
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.gl.GlesRequirementException
import dev.hfps.reveal.pipeline.input.BitmapTileSource
import dev.hfps.reveal.pipeline.input.NativeRawTileSource
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.input.RegionTileSource
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.input.UnsupportedCameraException
import dev.hfps.reveal.pipeline.input.withLens
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import java.io.FileNotFoundException
import java.io.IOException

/** Why a photo could not be opened; each one is a friendly state with its own strings, never a crash. */
enum class OpenError {
    /** The URI can no longer be read (permission revoked, file moved or deleted, provider gone). */
    PERMISSION_LOST,

    /** Not an image Reveal decodes (or a RAW on a device without the native decoder). */
    UNSUPPORTED_FORMAT,

    /** A RAW whose camera has no colour matrix in LibRaw. */
    UNSUPPORTED_CAMERA,

    /** The file is damaged or truncated. */
    CORRUPT,

    /** The device's GPU cannot run the develop pipeline (OpenGL ES 3 with float render targets). */
    GPU_UNSUPPORTED,

    /** Not enough memory to open this file. */
    OUT_OF_MEMORY,
}

/** A failure with its user-facing [error]. */
class OpenException(val error: OpenError, cause: Throwable? = null) : IOException(error.name, cause)

/** What happens while a photo opens, in order: its name, the instant preview (when there is one), decode steps. */
sealed interface OpenEvent {
    data class Identified(val name: String, val isRaw: Boolean) : OpenEvent

    /** An upright display bitmap: the RAW's embedded preview or the standard image itself, subsampled. */
    data class Preview(val bitmap: Bitmap) : OpenEvent

    data class Step(val step: ProxyStep) : OpenEvent
}

/**
 * A decoded photo ready for the GPU: [input] is the upright linear proxy; [fullWidth] x [fullHeight] the upright size
 * of the whole image (for 100 % zoom).
 */
class OpenedPhoto(
    val name: String,
    val input: PipelineInput,
    val fullWidth: Int,
    val fullHeight: Int,
    val stats: ProxyStats,
)

/**
 * Opens a photo by URI for the editor: preview first, then the screen proxy, mapped to a [PipelineInput]. Reads the
 * document in place through its descriptor (never copies the original). Blocking: call it off the main thread.
 */
class PhotoOpener(private val resolver: ContentResolver) {

    /** The document's display name, else the last path segment. */
    fun displayName(uri: Uri): String {
        val queried = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()
        return queried?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }

    /**
     * Opens [uri] and builds a proxy whose long edge is [proxyLongEdge] (never upscaled), reporting progress through
     * [onEvent]. Throws [OpenException] with the reason, or [CancelledException] when [cancel] fires.
     */
    @Suppress("TooGenericExceptionCaught", "ThrowsCount")
    fun open(
        uri: Uri,
        proxyLongEdge: Int,
        previewLongEdge: Int,
        cancel: CancelToken,
        onEvent: (OpenEvent) -> Unit,
    ): OpenedPhoto {
        val name = displayName(uri)
        val image = openInput(uri, name)
        return try {
            image.use {
                if (isSmartPreview(it)) {
                    openSmartPreview(it, name, onEvent)
                } else {
                    decode(it, name, proxyLongEdge, previewLongEdge, cancel, onEvent)
                }
            }
        } catch (e: CancelledException) {
            throw e
        } catch (e: OpenException) {
            throw e
        } catch (e: Throwable) {
            throw OpenException(classify(e), e)
        }
    }

    /** The document's descriptor; any failure here means the grant or the file is gone. */
    private fun openInput(uri: Uri, name: String): ImageInput = try {
        ImageInput.fromUri(resolver, uri, name)
    } catch (e: SecurityException) {
        throw OpenException(OpenError.PERMISSION_LOST, e)
    } catch (e: FileNotFoundException) {
        throw OpenException(OpenError.PERMISSION_LOST, e)
    } catch (e: IllegalArgumentException) {
        throw OpenException(OpenError.PERMISSION_LOST, e)
    }

    /**
     * The full-resolution source of [uri] for the editor's zoom (docs/adr/0012): a RAW decoded once into native
     * memory with the export's LibRaw path ([NativeRawTileSource], 6 bytes per pixel while the editor is open), a
     * standard image decoded once like the export ([BitmapTileSource], 4 bytes per pixel) or, above
     * [FULL_DECODE_MAX_PIXELS], read in regions ([RegionTileSource], the document stays open until the source is
     * closed). Upright, the same size the proxy was mapped from, carrying [lens] (the proxy's profile, docs/adr/0015).
     * Blocking, cancellable; the caller owns (closes) it.
     */
    fun openFullResolution(uri: Uri, cancel: CancelToken, lens: LensProfile? = null): TileSource =
        openFullSource(uri, cancel).withLens(lens)

    @Suppress("ThrowsCount", "ReturnCount")
    private fun openFullSource(uri: Uri, cancel: CancelToken): TileSource {
        val image = openInput(uri, displayName(uri))
        try {
            val type = image.type
            if (type.isRaw) {
                if (!RawDecoder.isAvailable) throw OpenException(OpenError.UNSUPPORTED_FORMAT)
                val decoded = image.use { RawDecoder.decodeNative(it.descriptor, cancel = cancel) }
                return try {
                    NativeRawTileSource(decoded.image, decoded.info)
                } catch (e: UnsupportedCameraException) {
                    decoded.image.close()
                    throw e
                }
            }
            if (type.kind == ImageKind.OTHER) throw OpenException(OpenError.UNSUPPORTED_FORMAT)
            val orientation = runCatching { image.inspection.metadata.orientation }.getOrNull() ?: 1
            val fd = image.descriptor.fileDescriptor
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFileDescriptor(fd, null, bounds)
            if (bounds.outWidth.toLong() * bounds.outHeight > FULL_DECODE_MAX_PIXELS) {
                // Very large: regions on demand (as the library viewer), the document stays open.
                return RegionTileSource(image.descriptor, orientation) { image.close() }
            }
            // A JPEG region decode re-reads everything above the region (about 250 ms per tile at 12 MP on the
            // emulator); one full decode (4 bytes per pixel, the export's own source) makes every tile cheap.
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                dev.hfps.image.standard.StandardDecoder.decodableSpace(bounds.outColorSpace)?.let {
                    inPreferredColorSpace =
                        it
                }
            }
            cancel.throwIfCancelled()
            val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options) ?: throw OpenException(OpenError.CORRUPT)
            image.close()
            return BitmapTileSource(bitmap, orientation)
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            image.close()
            throw e
        }
    }

    private fun isSmartPreview(image: ImageInput): Boolean = runCatching {
        image.withSource { SmartPreviewCodec.isSmartPreview(it.readRange(0, SMART_HEAD)) }
    }.getOrDefault(false)

    /**
     * A smart preview (ADR 0018): the editor's input stored at about 2560 px, used when the original is out of reach.
     * Its display JPEG is the instant preview; there is no full resolution behind it (zoom stops at the preview).
     */
    private fun openSmartPreview(image: ImageInput, name: String, onEvent: (OpenEvent) -> Unit): OpenedPhoto {
        val start = System.currentTimeMillis()
        val decoded = image.withSource { source ->
            SmartPreviewCodec.decode(SourceInputStream(source))
        }
        onEvent(OpenEvent.Identified(name, decoded.input.isRaw))
        if (decoded.jpeg.isNotEmpty()) {
            BitmapFactory.decodeByteArray(decoded.jpeg, 0, decoded.jpeg.size)?.let { onEvent(OpenEvent.Preview(it)) }
        }
        val input = decoded.input
        val millis = System.currentTimeMillis() - start
        return OpenedPhoto(
            name = name,
            input = input,
            fullWidth = input.width,
            fullHeight = input.height,
            stats = ProxyStats(ProxySource.SMART_PREVIEW, input.width, input.height, millis, 0, millis),
        )
    }

    private fun decode(
        image: ImageInput,
        name: String,
        proxyLongEdge: Int,
        previewLongEdge: Int,
        cancel: CancelToken,
        onEvent: (OpenEvent) -> Unit,
    ): OpenedPhoto {
        val type = image.type
        if (type.kind == ImageKind.OTHER) throw OpenException(OpenError.UNSUPPORTED_FORMAT)
        if (type.isRaw && !RawDecoder.isAvailable) throw OpenException(OpenError.UNSUPPORTED_FORMAT)
        onEvent(OpenEvent.Identified(name, type.isRaw))
        // The preview is best effort: a RAW without an embedded JPEG, or a format the inspector does not read, simply
        // goes straight to the developed proxy.
        runCatching {
            if (type.isRaw) {
                FirstImageLoader.load(image, null, previewLongEdge)
            } else {
                FirstImageLoader.load(null, image, previewLongEdge)
            }
        }.getOrNull()?.let { onEvent(OpenEvent.Preview(it.bitmap)) }
        cancel.throwIfCancelled()
        // Standard decoders do not report orientation: take the container's (EXIF in JPEG). RAWs carry their own.
        val orientation = if (type.isRaw) null else runCatching { image.inspection.metadata.orientation }.getOrNull()
        val proxy = ProxyBuilder.decodeProxy(image, proxyLongEdge, cancel) { onEvent(OpenEvent.Step(it)) }
        cancel.throwIfCancelled()
        val metadata = runCatching { image.inspection.metadata }.getOrNull()
        val input = PipelineInputMapper.map(proxy.image, proxy.info, orientation ?: proxy.info.orientation, metadata)
        val turned = input.width != proxy.image.width
        val stats = proxy.stats
        return OpenedPhoto(
            name = name,
            input = input,
            fullWidth = if (turned) stats.fullHeight else stats.fullWidth,
            fullHeight = if (turned) stats.fullWidth else stats.fullHeight,
            stats = stats,
        )
    }

    companion object {
        private const val SMART_HEAD = 8

        /** Standard images above this many pixels are read in regions instead of decoded whole (160 MB at ARGB). */
        const val FULL_DECODE_MAX_PIXELS: Long = 40_000_000L

        /** The user-facing reason for a failure while decoding or rendering. */
        fun classify(error: Throwable): OpenError = when (error) {
            is OpenException -> error.error
            is OutOfMemoryError -> OpenError.OUT_OF_MEMORY
            is UnsupportedCameraException -> OpenError.UNSUPPORTED_CAMERA
            is GlesRequirementException -> OpenError.GPU_UNSUPPORTED
            is SecurityException, is FileNotFoundException -> OpenError.PERMISSION_LOST
            else -> OpenError.CORRUPT
        }
    }
}
