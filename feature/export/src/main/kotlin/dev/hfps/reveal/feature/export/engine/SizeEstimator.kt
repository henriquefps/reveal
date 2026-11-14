// Sample layouts (RGB, RGBA, 8/16 bits) are the formats' own.
@file:Suppress("MagicNumber")

package dev.hfps.reveal.feature.export.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.image.write.ImageCodec
import dev.hfps.image.write.ImageMetadata
import dev.hfps.image.write.PngWriter
import dev.hfps.image.write.RgbRows
import dev.hfps.image.write.TiffFields
import dev.hfps.image.write.TiffWriter
import dev.hfps.reveal.feature.export.encode.EmbeddedMetadata
import dev.hfps.reveal.feature.export.encode.HeifEncoder
import dev.hfps.reveal.feature.export.encode.JpegEncoder
import dev.hfps.reveal.feature.export.encode.TileCodecEncoder
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.export.PixelSize
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
import dev.hfps.reveal.pipeline.optics.LensProfile
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

/**
 * The live file-size estimate of the export sheet (docs/adr/0009, "Estimate"): a small proxy of the photo
 * ([PROXY_EDGE] px) developed once on the CPU, then encoded with the sheet's format, quality and colour whenever they
 * change; bytes per pixel times the export's pixels, times [FULL_RES_FACTOR] (fine detail compresses better at full
 * resolution than in a downscaled proxy, measured on the sample RAWs). A few hundred ms; never blocks the UI.
 */
class SizeEstimator private constructor(
    /** Upright full-resolution size. */
    val fullSize: PixelSize,
    private val linear: FloatArray,
    private val width: Int,
    private val height: Int,
    private val recipe: Recipe,
    private val lens: LensProfile?,
    /** Bytes of the original file (the original copy's size), or 0 when unknown. */
    private val originalBytes: Long = 0,
) {
    /** Full-resolution size of the picture (the geometry operator's output: cropped, turned). */
    val pictureSize: PixelSize
        get() = ExportPlan.of(
            fullSize.width,
            fullSize.height,
            recipe.geometry,
            ExportSize.Original,
            lens = lens,
            optics = recipe.optics,
        ).output

    /** Output pixel size for [options] (crop and size option applied). */
    fun outputSize(options: ExportOptions): PixelSize = ExportPlan.of(
        fullSize.width,
        fullSize.height,
        recipe.geometry,
        options.size,
        lens = lens,
        optics = recipe.optics,
    ).output

    /** Estimated bytes of the exported file. */
    @Suppress("ReturnCount")
    fun estimate(context: Context, options: ExportOptions): Long {
        if (!options.format.rendered) return originalBytes
        val route = Route.of(options)
        if (route != Route.BITMAP_JPEG && route != Route.BITMAP_HEIF) return estimateRows(context, options, route)
        val colour = options.effectiveColour
        val rgba = OutputTransformOp.apply(linear, width, height, OutputSettings(colour.space))
        val bitmap = createBitmap(
            width,
            height,
            Bitmap.Config.ARGB_8888,
            false,
            ExportEngine.colourSpace(colour),
        )
        try {
            bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(rgba))
            val bytes = if (route == Route.BITMAP_JPEG) {
                JpegEncoder.encodedSize(bitmap, options.quality)
            } else {
                val file = File(context.cacheDir, "estimate-${UUID.randomUUID()}.heic")
                try {
                    HeifEncoder.encode(bitmap, options.quality, EmbeddedMetadata(null, null), file)
                    file.length()
                } finally {
                    file.delete()
                }
            }
            val perPixel = bytes.toDouble() / (width.toLong() * height)
            val factor = if (outputSize(options).pixels > width.toLong() * height * 2) FULL_RES_FACTOR else 1.0
            return (perPixel * outputSize(options).pixels * factor).toLong()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * PNG, TIFF, HEIF/AVIF tiles: the proxy as the encoders' rows (16-bit encoded values, or 8-bit dithered for PNG 8),
     * through the real writer. Lossless sizes do not shrink at full resolution (no [FULL_RES_FACTOR]).
     */
    private fun estimateRows(context: Context, options: ExportOptions, route: Route): Long {
        val rows = proxyRows(options, route)
        val file = File(context.cacheDir, "estimate-${UUID.randomUUID()}")
        val bytes = try {
            when (route) {
                Route.PNG_8, Route.PNG_16 -> {
                    file.outputStream().buffered().use { PngWriter.write(rows, ImageMetadata(), it) }
                }

                Route.TIFF_16 -> TiffWriter.write(rows, TiffFields(), ImageMetadata(), file)

                else -> {
                    val codec = if (route == Route.HEIF_TILES) ImageCodec.HEVC else ImageCodec.AV1
                    val coded = TileCodecEncoder(codec, options.effectiveDepth).encode(rows, options.quality)
                    // Only the tiles' share: the edge tiles' padding is not in the estimate's pixels.
                    file.writeBytes(coded.tiles.fold(ByteArray(0)) { a, b -> a + b })
                }
            }
            file.length()
        } finally {
            file.delete()
        }
        val pixels = if (route == Route.HEIF_TILES || route == Route.AVIF_TILES) {
            val t = TileCodecEncoder.TILE.toLong()
            ((width + t - 1) / t) * ((height + t - 1) / t) * t * t
        } else {
            width.toLong() * height
        }
        val lossy = route == Route.HEIF_TILES || route == Route.AVIF_TILES
        val factor = if (lossy && outputSize(options).pixels > width.toLong() * height * 2) FULL_RES_FACTOR else 1.0
        return (bytes.toDouble() / pixels * outputSize(options).pixels * factor).toLong()
    }

    private fun proxyRows(options: ExportOptions, route: Route): RgbRows {
        val space = options.effectiveColour.space
        val samples = IntArray(width * height * 3)
        if (route.high) {
            val m = OutputTransformOp.workingToOutput(space).toFloatArray()
            for (i in 0 until width * height) {
                for (c in 0 until 3) {
                    val lin = (
                        m[c * 3] * linear[i * 3] + m[c * 3 + 1] * linear[i * 3 + 1] +
                            m[c * 3 + 2] * linear[i * 3 + 2]
                        )
                        .coerceIn(0f, 1f)
                    samples[i * 3 + c] = (OutputTransformOp.oetf(lin) * MAX16 + 0.5f).toInt()
                }
            }
        } else {
            val rgba = OutputTransformOp.apply(linear, width, height, OutputSettings(space))
            for (i in 0 until width * height) for (c in 0 until 3) samples[i * 3 + c] = rgba[i * 4 + c].toInt() and 0xFF
        }
        return ArrayRows(width, height, if (route.high) 16 else 8, samples)
    }

    private class ArrayRows(
        override val width: Int,
        override val height: Int,
        override val bits: Int,
        val samples: IntArray,
    ) : RgbRows {
        override fun row(y: Int, out: IntArray) {
            samples.copyInto(out, 0, y * width * 3, (y + 1) * width * 3)
        }
    }

    companion object {
        const val PROXY_EDGE = 768
        private const val MAX16 = 65535f

        /** Full-resolution files are this fraction of the proxy's bytes per pixel (sample RAWs, docs/adr/0009). */
        const val FULL_RES_FACTOR = 0.6

        /** Decodes and develops the proxy of [uri] with [recipe] (blocking; a background thread). */
        fun prepare(context: Context, uri: Uri, name: String, recipe: Recipe): SizeEstimator {
            val originalBytes = runCatching {
                context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: 0L
            }.getOrDefault(0L)
            return ImageInput.fromUri(context.contentResolver, uri, name).use { input ->
                val orientation = if (input.type.isRaw) {
                    null
                } else {
                    runCatching { input.inspection.metadata.orientation }.getOrNull()
                }
                val proxy = ProxyBuilder.decodeProxy(input, PROXY_EDGE)
                val turn = orientation ?: proxy.info.orientation
                val metadata = runCatching { input.inspection.metadata }.getOrNull()
                val pipelineInput: PipelineInput = PipelineInputMapper.map(proxy.image, proxy.info, turn, metadata)
                val (fw, fh) = PipelineInputMapper.orientedSize(proxy.stats.fullWidth, proxy.stats.fullHeight, turn)
                // The proxy goes through the geometry operator too: the estimate is of the cropped picture.
                val mapping = CpuDevelop.mapping(pipelineInput, recipe)
                // Detail at the proxy's scale (docs/adr/0012), as the editor's fit view shows it.
                val scale = maxOf(fw, fh).toFloat() / maxOf(pipelineInput.width, pipelineInput.height)
                SizeEstimator(
                    PixelSize(fw, fh),
                    CpuDevelop.renderGeometry(pipelineInput, recipe, mapping, scale.coerceAtLeast(1f)),
                    mapping.outputWidth,
                    mapping.outputHeight,
                    recipe,
                    pipelineInput.lens,
                    originalBytes.coerceAtLeast(0),
                )
            }
        }
    }
}
