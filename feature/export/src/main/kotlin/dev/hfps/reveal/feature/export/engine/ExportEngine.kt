package dev.hfps.reveal.feature.export.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.net.Uri
import android.util.Log
import dev.hfps.image.ImageInput
import dev.hfps.image.format.ExifSnapshot
import dev.hfps.image.format.ImageKind
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.image.raw.RawDecoder
import dev.hfps.image.source.BlockCachedSource
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.feature.export.destination.DestinationUnavailableException
import dev.hfps.reveal.feature.export.destination.Destinations
import dev.hfps.reveal.feature.export.encode.EmbeddedMetadata
import dev.hfps.reveal.feature.export.encode.ImageEncoders
import dev.hfps.reveal.feature.export.metadata.ExifWriter
import dev.hfps.reveal.feature.export.metadata.OutputFacts
import dev.hfps.reveal.feature.export.metadata.SourceMetadata
import dev.hfps.reveal.feature.export.metadata.XmpPacket
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportNaming
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.pipeline.cpu.DetailParams
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputSpace
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.gl.GlesRequirementException
import dev.hfps.reveal.pipeline.gl.RenderCancelledException
import dev.hfps.reveal.pipeline.gl.TiledRenderStats
import dev.hfps.reveal.pipeline.gl.TiledRenderer
import dev.hfps.reveal.pipeline.input.BitmapTileSource
import dev.hfps.reveal.pipeline.input.NativeRawTileSource
import dev.hfps.reveal.pipeline.input.TileSource
import dev.hfps.reveal.pipeline.input.UnsupportedCameraException
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.UUID

/** One photo to export: the file to develop, its recipe and what names and metadata need. */
data class ExportItem(
    val uri: Uri,
    val name: String,
    val recipe: Recipe,
    val rating: Int = 0,
    /** The JPEG of a RAW+JPEG pair: its EXIF fills gaps of the RAW's. */
    val renderedUri: Uri? = null,
    /** Number of this item in the "sequence" name template. */
    val sequence: Int = 1,
)

/** Why an item failed; each has a clear status in the sheet and the notification. */
enum class ExportFailure {
    SOURCE_MISSING,
    UNSUPPORTED,
    DESTINATION_UNAVAILABLE,
    LOW_STORAGE,
    OUT_OF_MEMORY,
    GPU_UNSUPPORTED,
    FAILED,
}

class ExportException(val failure: ExportFailure, cause: Throwable? = null) : IOException(failure.name, cause)

/** A finished export: where it went, its file name and size, and what it cost. */
data class ExportResult(
    val uri: Uri,
    val fileName: String,
    val width: Int,
    val height: Int,
    val bytes: Long,
    val decodeMs: Long,
    val renderMs: Long,
    val encodeMs: Long,
    val stats: TiledRenderStats,
)

/**
 * Exports one photo at full resolution (docs/adr/0009): full decode (RAW in native memory, standard formats as a
 * bitmap in their own colour space), the tiled GPU develop ([TiledRenderer]) into an output bitmap, released source,
 * encoder with metadata into the cache, then the destination. Blocking; the caller owns the renderer (one per worker).
 */
class ExportEngine(private val context: Context, private val destinations: Destinations = Destinations(context)) {

    /** Software tag of the files. */
    val software: String = "Reveal " + runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull().orEmpty()

    @Suppress("LongParameterList", "LongMethod", "TooGenericExceptionCaught", "ThrowsCount", "CyclomaticComplexMethod")
    fun export(
        item: ExportItem,
        options: ExportOptions,
        authorship: Authorship,
        renderer: TiledRenderer,
        cancel: () -> Boolean,
        progress: (Float) -> Unit = {},
        onPartial: (Uri) -> Unit = {},
    ): ExportResult {
        val input = try {
            ImageInput.fromUri(context.contentResolver, item.uri, item.name)
        } catch (e: FileNotFoundException) {
            throw ExportException(ExportFailure.SOURCE_MISSING, e)
        } catch (e: SecurityException) {
            throw ExportException(ExportFailure.SOURCE_MISSING, e)
        } catch (e: IllegalArgumentException) {
            throw ExportException(ExportFailure.SOURCE_MISSING, e)
        }
        val started = System.nanoTime()
        val (metadata, source) = input.use { image ->
            try {
                val meta = readMetadata(image, item)
                meta to openSource(image, meta.fallback)
            } catch (e: ExportException) {
                throw e
            } catch (e: OutOfMemoryError) {
                throw ExportException(ExportFailure.OUT_OF_MEMORY, e)
            } catch (e: UnsupportedCameraException) {
                throw ExportException(ExportFailure.UNSUPPORTED, e)
            } catch (e: IOException) {
                throw ExportException(ExportFailure.SOURCE_MISSING, e)
            }
        }
        val decoded = System.nanoTime()
        val colour = options.effectiveColour
        val plan: ExportPlan
        val sink: BitmapSink
        val stats: TiledRenderStats
        try {
            // Detail (slot 11) reads a neighbourhood: each tile's input carries its apron (docs/adr/0012).
            val reach = DetailParams.from(item.recipe.detail, 1f).reach
            plan = ExportPlan.of(
                source.width,
                source.height,
                item.recipe.geometry,
                options.size,
                renderer.tileSize,
                reach,
            )
            checkStorage(plan, options)
            sink = try {
                BitmapSink(plan.output.width, plan.output.height, colourSpace(colour.space))
            } catch (e: OutOfMemoryError) {
                throw ExportException(ExportFailure.OUT_OF_MEMORY, e)
            }
            stats = try {
                renderer.render(source, item.recipe, plan, OutputSettings(colour.space), sink, cancel = cancel) {
                    progress(it * RENDER_SHARE)
                }
            } catch (e: RenderCancelledException) {
                sink.bitmap.recycle()
                throw e
            } catch (e: GlesRequirementException) {
                sink.bitmap.recycle()
                throw ExportException(ExportFailure.GPU_UNSUPPORTED, e)
            } finally {
                sink.releaseBands()
            }
        } finally {
            // The full-size linear image (about 150-350 MB native) goes as soon as the render is done.
            source.close()
        }
        val rendered = System.nanoTime()
        val bitmap = sink.bitmap
        val fileName = ExportNaming.fileName(
            item.name,
            metadata.snapshot?.field(DATE_TIME_ORIGINAL)?.text ?: metadata.fallback?.captureTime,
            item.sequence,
            options.naming,
            options.format,
        )
        val work = File(context.cacheDir, "export").apply { mkdirs() }
        val encoded = File(work, "${UUID.randomUUID()}.${options.format.extension}")
        try {
            val facts = OutputFacts(bitmap.width, bitmap.height, colour, software)
            val embedded = EmbeddedMetadata(
                ExifWriter.build(metadata, facts, options.metadata, authorship),
                if (options.format == ExportFormat.JPEG) {
                    XmpPacket.build(options.metadata, authorship, metadata.rating, software)
                } else {
                    null
                },
            )
            try {
                ImageEncoders.of(options.format).encode(bitmap, options.quality, embedded, encoded)
            } catch (e: IOException) {
                throw ExportException(ExportFailure.LOW_STORAGE.takeIf { lowSpace() } ?: ExportFailure.FAILED, e)
            } finally {
                bitmap.recycle()
            }
            progress(ENCODE_SHARE)
            val encodedAt = System.nanoTime()
            val bytes = encoded.length()
            val uri = try {
                destinations.deliver(encoded, fileName, options.format.mimeType, options.destination, onPartial)
            } catch (e: DestinationUnavailableException) {
                throw ExportException(ExportFailure.DESTINATION_UNAVAILABLE, e)
            }
            progress(1f)
            val result = ExportResult(
                uri = uri,
                fileName = fileName,
                width = plan.output.width,
                height = plan.output.height,
                bytes = bytes,
                decodeMs = (decoded - started) / NANOS_PER_MS,
                renderMs = (rendered - decoded) / NANOS_PER_MS,
                encodeMs = (encodedAt - rendered) / NANOS_PER_MS,
                stats = stats,
            )
            Log.i(TAG, "exported ${plan.output.width}x${plan.output.height} ${options.format} $result")
            return result
        } finally {
            encoded.delete()
        }
    }

    /** EXIF of the file (and of its pair's JPEG for gaps) and the photo's rating. */
    private fun readMetadata(image: ImageInput, item: ExportItem): SourceMetadata {
        val snapshot = runCatching { image.withSource { ExifSnapshot.read(BlockCachedSource(it), image.type) } }
            .getOrNull()
        var inspected: PhotoMetadata? = runCatching { image.inspection.metadata }.getOrNull()
        val paired = item.renderedUri?.let { uri ->
            runCatching {
                ImageInput.fromUri(context.contentResolver, uri, "pair.jpg").use { pair ->
                    pair.withSource { ExifSnapshot.read(BlockCachedSource(it), pair.type) } to pair.inspection.metadata
                }
            }.getOrNull()
        }
        if (paired != null) inspected = inspected?.orElse(paired.second) ?: paired.second
        return SourceMetadata(
            snapshot = snapshot ?: paired?.first,
            fallback = inspected,
            rating = item.rating.takeIf { it > 0 } ?: inspected?.rating ?: 0,
        )
    }

    /** The full-resolution source: RAW decoded to native memory, standard formats as a bitmap in their own space. */
    @Suppress("ThrowsCount")
    private fun openSource(image: ImageInput, inspected: PhotoMetadata?): TileSource {
        val type = image.type
        if (type.isRaw) {
            if (!RawDecoder.isAvailable) throw ExportException(ExportFailure.UNSUPPORTED)
            val decoded = RawDecoder.decodeNative(image.descriptor)
            return try {
                NativeRawTileSource(decoded.image, decoded.info)
            } catch (e: UnsupportedCameraException) {
                decoded.image.close()
                throw e
            }
        }
        if (type.kind == ImageKind.OTHER) throw ExportException(ExportFailure.UNSUPPORTED)
        val fd = image.descriptor.fileDescriptor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, bounds)
        if (bounds.outWidth <= 0) throw ExportException(ExportFailure.UNSUPPORTED)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            bounds.outColorSpace?.let { inPreferredColorSpace = it }
        }
        val bitmap = BitmapFactory.decodeFileDescriptor(fd, null, options) ?: throw IOException("decode failed")
        return BitmapTileSource(bitmap, inspected?.orientation ?: 1)
    }

    private fun checkStorage(plan: ExportPlan, options: ExportOptions) {
        // Upper bound of a high-quality JPEG/HEIF, twice (the encoded cache file and its copy) plus a margin.
        val needed = plan.output.pixels * BYTES_PER_PIXEL_BOUND * 2 + MARGIN_BYTES
        if (destinations.freeBytes(options.destination) < needed) throw ExportException(ExportFailure.LOW_STORAGE)
    }

    private fun lowSpace(): Boolean = destinations.freeBytes(ExportDestination.Share) <
        MARGIN_BYTES

    companion object {
        private const val TAG = "RevealExport"
        private const val DATE_TIME_ORIGINAL = 0x9003
        private const val NANOS_PER_MS = 1_000_000L
        private const val RENDER_SHARE = 0.85f
        private const val ENCODE_SHARE = 0.95f
        private const val BYTES_PER_PIXEL_BOUND = 2L
        private const val MARGIN_BYTES = 32L * 1024 * 1024

        fun colourSpace(space: OutputSpace): ColorSpace = when (space) {
            OutputSpace.SRGB -> ColorSpace.get(ColorSpace.Named.SRGB)
            OutputSpace.DISPLAY_P3 -> ColorSpace.get(ColorSpace.Named.DISPLAY_P3)
        }

        /** For callers that only know the user-facing colour choice. */
        fun colourSpace(colour: ExportColour): ColorSpace = colourSpace(colour.space)
    }
}
