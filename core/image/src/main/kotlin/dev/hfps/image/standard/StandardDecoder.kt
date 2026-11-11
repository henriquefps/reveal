// Colour space primaries are read from fixed positions of the platform arrays.
@file:Suppress("MagicNumber")

package dev.hfps.image.standard

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.ParcelFileDescriptor
import dev.hfps.image.LinearImage
import dev.hfps.image.color.Chromaticity
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.PixelRect
import dev.hfps.image.color.Primaries
import dev.hfps.image.color.SourceColorSpace
import dev.hfps.image.color.StandardColorInfo
import dev.hfps.image.decode.Sampling
import java.io.IOException
import java.nio.ByteBuffer

/**
 * What to decode: the whole image, or [region] (in stored pixels), subsampled (powers of two) to keep about
 * [minLongEdge] on the long edge.
 */
data class StandardDecodeOptions(val region: PixelRect? = null, val minLongEdge: Int = 0)

/** A decoded standard image: [image] is linear 16-bit in the file's own primaries, [info] describes them. */
data class StandardDecodeResult(
    val image: LinearImage,
    val info: ColorInfo,
    /** Stored size of the whole file. */
    val sourceWidth: Int,
    val sourceHeight: Int,
    /** Subsampling applied by the platform decoder (1 = full resolution). */
    val sampleSize: Int,
    val decodeMs: Long,
)

/**
 * JPEG, HEIF/HEIC, PNG and WebP through the platform decoders (`BitmapFactory`, `BitmapRegionDecoder`) into the
 * same linear working encoding as a RAW (see [LinearImage]).
 *
 * Working encoding: the pixels are decoded in the file's **own colour space** (sRGB, Display P3, Adobe RGB, an
 * embedded ICC profile the platform understands) with no gamut conversion, then linearised with that space's transfer
 * function through a 256-entry table (8-bit sources lose nothing). [ColorInfo.toXyz] carries the primaries, adapted
 * to D65. Alpha is ignored. Known limits: 10-bit/HDR sources are decoded at 8 bits (the platform returns
 * `ARGB_8888`), and EXIF orientation is not applied (it is reported where the container carries it).
 */
object StandardDecoder {

    fun decode(
        descriptor: ParcelFileDescriptor,
        options: StandardDecodeOptions = StandardDecodeOptions(),
    ): StandardDecodeResult {
        val started = System.nanoTime()
        val fd = descriptor.fileDescriptor
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFileDescriptor(fd, null, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not a decodable image")
        val area = options.region ?: PixelRect(0, 0, bounds.outWidth, bounds.outHeight)
        val sample = if (options.minLongEdge >
            0
        ) {
            Sampling.sampleSize(area.width, area.height, options.minLongEdge)
        } else {
            1
        }
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPremultiplied = false
            bounds.outColorSpace?.let { inPreferredColorSpace = it }
        }
        val bitmap = if (options.region == null) {
            BitmapFactory.decodeFileDescriptor(fd, null, decodeOptions)
        } else {
            val regionDecoder = openRegionDecoder(descriptor)
            try {
                regionDecoder.decodeRegion(
                    Rect(area.left, area.top, area.left + area.width, area.top + area.height),
                    decodeOptions,
                )
            } finally {
                regionDecoder.recycle()
            }
        } ?: throw IOException("Decode failed")
        try {
            val (image, info) = linearize(bitmap)
            return StandardDecodeResult(
                image,
                info,
                bounds.outWidth,
                bounds.outHeight,
                sample,
                (System.nanoTime() - started) / NANOS_PER_MILLI,
            )
        } finally {
            bitmap.recycle()
        }
    }

    /** Linearises a software `ARGB_8888` [bitmap] in its own colour space. */
    fun linearize(bitmap: Bitmap): Pair<LinearImage, ColorInfo> {
        check(bitmap.config == Bitmap.Config.ARGB_8888) { "Expected ARGB_8888, got ${bitmap.config}" }
        val space = bitmap.colorSpace as? ColorSpace.Rgb ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        val bytes = ByteArray(bitmap.rowBytes * bitmap.height)
        bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val eotf = space.eotf
        val lut = Linearize.lut { eotf.applyAsDouble(it) }
        val image = Linearize.rgbaToLinear(bytes, bitmap.width, bitmap.height, bitmap.rowBytes, lut)
        return image to colorInfoOf(space)
    }

    /** The [ColorInfo] of an Android RGB colour space: named space where it matches, else its primaries. */
    fun colorInfoOf(space: ColorSpace.Rgb): StandardColorInfo {
        val named = when (space) {
            ColorSpace.get(ColorSpace.Named.SRGB) -> SourceColorSpace.SRGB to ColorMath.SRGB
            ColorSpace.get(ColorSpace.Named.DISPLAY_P3) -> SourceColorSpace.DISPLAY_P3 to ColorMath.DISPLAY_P3
            ColorSpace.get(ColorSpace.Named.ADOBE_RGB) -> SourceColorSpace.ADOBE_RGB to ColorMath.ADOBE_RGB
            ColorSpace.get(ColorSpace.Named.BT2020) -> SourceColorSpace.REC2020 to ColorMath.REC2020
            else -> null
        }
        val (kind, primaries) = named ?: (SourceColorSpace.OTHER_RGB to primariesOf(space))
        return StandardColorInfo(ColorMath.rgbToXyzD65(primaries), kind)
    }

    private fun primariesOf(space: ColorSpace.Rgb): Primaries {
        val p = space.primaries
        val w = space.whitePoint
        return Primaries(
            Chromaticity(p[0].toDouble(), p[1].toDouble()),
            Chromaticity(p[2].toDouble(), p[3].toDouble()),
            Chromaticity(p[4].toDouble(), p[5].toDouble()),
            Chromaticity(w[0].toDouble(), w[1].toDouble()),
        )
    }

    // The FileDescriptor overload is deprecated without a replacement that takes a descriptor; it is the only way to
    // region-decode a SAF document in place (no copy).
    @Suppress("DEPRECATION")
    private fun openRegionDecoder(descriptor: ParcelFileDescriptor): BitmapRegionDecoder =
        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)

    private const val NANOS_PER_MILLI = 1_000_000L
}
