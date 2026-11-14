package dev.hfps.reveal.feature.export.engine

import android.graphics.Bitmap
import android.graphics.ColorSpace
import dev.hfps.image.write.RgbRaster
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.pipeline.gl.TileSink
import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.FloatBuffer
import java.util.UUID

/**
 * How a rendered format is written (docs/adr/0009 "Formats"): from an 8-bit output bitmap (the platform encoders), or
 * from a memory-mapped raster of 8-bit dithered or 16-bit encoded samples (Reveal's writers). [high] = the tiled render
 * writes unquantised floats; [xmp] = the file carries the XMP packet; [encodedBound] and [rasterBytes] (bytes per
 * pixel) size the storage check.
 */
internal enum class Route(val high: Boolean, val xmp: Boolean, val encodedBound: Long, val rasterBytes: Long) {
    BITMAP_JPEG(high = false, xmp = true, encodedBound = 2, rasterBytes = 0),

    /** HEIF 8-bit sRGB through `HeifWriter` (it cannot carry XMP). */
    BITMAP_HEIF(high = false, xmp = false, encodedBound = 2, rasterBytes = 0),
    PNG_8(high = false, xmp = true, encodedBound = 4, rasterBytes = 3),
    PNG_16(high = true, xmp = true, encodedBound = 7, rasterBytes = 6),
    TIFF_16(high = true, xmp = true, encodedBound = 7, rasterBytes = 6),

    /** HEIF in Display P3 or 10-bit: Reveal's tile encoder and container. */
    HEIF_TILES(high = true, xmp = true, encodedBound = 2, rasterBytes = 6),
    AVIF_TILES(high = true, xmp = true, encodedBound = 2, rasterBytes = 6),
    ;

    companion object {
        fun of(options: ExportOptions): Route = when (options.format) {
            ExportFormat.JPEG -> BITMAP_JPEG
            ExportFormat.HEIF ->
                if (options.effectiveDepth == 8 && options.effectiveColour == ExportColour.SRGB) BITMAP_HEIF else HEIF_TILES
            ExportFormat.AVIF -> AVIF_TILES
            ExportFormat.PNG -> if (options.effectiveDepth == 16) PNG_16 else PNG_8
            ExportFormat.TIFF -> TIFF_16
            ExportFormat.ORIGINAL -> error("the original copy is not rendered")
        }
    }
}

/** Receives the tiled render into a [RgbRaster]: 8-bit dithered bytes or 16-bit levels of the encoded floats. */
internal class RasterSink(val raster: RgbRaster) : TileSink {
    override fun accept(x: Int, y: Int, w: Int, h: Int, rgba: ByteBuffer) = raster.putRgba8(x, y, w, h, rgba)

    override fun acceptHigh(x: Int, y: Int, w: Int, h: Int, rgba: FloatBuffer) = raster.putEncoded(x, y, w, h, rgba)
}

/** Where a render goes for its [Route]: an output bitmap or a raster; [close] frees either. */
internal class RenderOutput private constructor(
    val sink: TileSink,
    val bitmap: Bitmap?,
    val raster: RgbRaster?,
) : Closeable {
    /** Frees what only the render needed (the bitmap sink's band bitmaps). */
    fun releaseBands() {
        (sink as? BitmapSink)?.releaseBands()
    }

    override fun close() {
        bitmap?.recycle()
        raster?.close()
    }

    companion object {
        fun create(route: Route, width: Int, height: Int, space: ColorSpace, dir: File): RenderOutput = when (route) {
            Route.BITMAP_JPEG, Route.BITMAP_HEIF -> {
                val sink = BitmapSink(width, height, space)
                RenderOutput(sink, sink.bitmap, null)
            }

            else -> {
                val raster = RgbRaster(width, height, if (route.high) 16 else 8, File(dir, "${UUID.randomUUID()}.raster"))
                RenderOutput(RasterSink(raster), null, raster)
            }
        }
    }
}
