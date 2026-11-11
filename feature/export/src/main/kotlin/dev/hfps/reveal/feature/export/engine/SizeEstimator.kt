package dev.hfps.reveal.feature.export.engine

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.graphics.createBitmap
import dev.hfps.image.ImageInput
import dev.hfps.image.proxy.ProxyBuilder
import dev.hfps.reveal.feature.export.encode.EmbeddedMetadata
import dev.hfps.reveal.feature.export.encode.HeifEncoder
import dev.hfps.reveal.feature.export.encode.JpegEncoder
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.OutputSettings
import dev.hfps.reveal.pipeline.cpu.OutputTransformOp
import dev.hfps.reveal.pipeline.export.ExportPlan
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.export.PixelSize
import dev.hfps.reveal.pipeline.input.PipelineInputMapper
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
) {
    /** Full-resolution size of the picture (the geometry operator's output: cropped, turned). */
    val pictureSize: PixelSize
        get() = ExportPlan.of(fullSize.width, fullSize.height, recipe.geometry, ExportSize.Original).output

    /** Output pixel size for [options] (crop and size option applied). */
    fun outputSize(options: ExportOptions): PixelSize =
        ExportPlan.of(fullSize.width, fullSize.height, recipe.geometry, options.size).output

    /** Estimated bytes of the exported file. */
    fun estimate(context: Context, options: ExportOptions): Long {
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
            val bytes = when (options.format) {
                ExportFormat.JPEG -> JpegEncoder.encodedSize(bitmap, options.quality)

                ExportFormat.HEIF -> {
                    val file = File(context.cacheDir, "estimate-${UUID.randomUUID()}.heic")
                    try {
                        HeifEncoder.encode(bitmap, options.quality, EmbeddedMetadata(null, null), file)
                        file.length()
                    } finally {
                        file.delete()
                    }
                }
            }
            val perPixel = bytes.toDouble() / (width.toLong() * height)
            val factor = if (outputSize(options).pixels > width.toLong() * height * 2) FULL_RES_FACTOR else 1.0
            return (perPixel * outputSize(options).pixels * factor).toLong()
        } finally {
            bitmap.recycle()
        }
    }

    companion object {
        const val PROXY_EDGE = 768

        /** Full-resolution files are this fraction of the proxy's bytes per pixel (sample RAWs, docs/adr/0009). */
        const val FULL_RES_FACTOR = 0.6

        /** Decodes and develops the proxy of [uri] with [recipe] (blocking; a background thread). */
        fun prepare(context: Context, uri: Uri, name: String, recipe: Recipe): SizeEstimator =
            ImageInput.fromUri(context.contentResolver, uri, name).use { input ->
                val orientation = if (input.type.isRaw) {
                    null
                } else {
                    runCatching { input.inspection.metadata.orientation }.getOrNull()
                }
                val proxy = ProxyBuilder.decodeProxy(input, PROXY_EDGE)
                val turn = orientation ?: proxy.info.orientation
                val pipelineInput: PipelineInput = PipelineInputMapper.map(proxy.image, proxy.info, turn)
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
                )
            }
    }
}
