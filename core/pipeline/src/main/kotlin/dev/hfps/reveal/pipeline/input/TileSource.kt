package dev.hfps.reveal.pipeline.input

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Rect
import android.os.Build
import android.os.ParcelFileDescriptor
import dev.hfps.image.LinearImage
import dev.hfps.image.NativeLinearImage
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.standard.Linearize
import dev.hfps.image.standard.StandardDecoder
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.Mat3
import dev.hfps.reveal.pipeline.optics.LensProfile
import java.nio.ByteBuffer
import java.nio.ShortBuffer

/**
 * A full-resolution linear image read in pieces (docs/adr/0009, "Full-resolution input"): what the tiled export
 * renderer develops when the whole image does not fit in one texture. Pixels are upright (orientation applied) and in
 * the [PipelineInput] encoding (RAW: black 0, white 1.0, un-balanced camera RGB; standard: linear in the file's space).
 */
interface TileSource : AutoCloseable {
    /** Upright size. */
    val width: Int
    val height: Int

    /** The colour metadata (a 1 x 1 [PipelineInput] without meaningful pixels), for `DevelopParams.from`. */
    val colour: PipelineInput

    /**
     * Writes the upright pixels of rows [y] until `y + h`, columns [x] until `x + w` into [out] as RGBA floats (alpha
     * 1), row by row, `w * h * 4` values from index 0.
     */
    fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray)

    override fun close() {}
}

/** A [TileSource] over a [PipelineInput] already in memory (proxies, synthetic images, tests). */
class PipelineInputTileSource(private val input: PipelineInput) : TileSource {
    override val width: Int get() = input.width
    override val height: Int get() = input.height
    override val colour: PipelineInput = input.colourOnly()

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val rgb = input.linearRgb
        var o = 0
        for (row in y until y + h) {
            var i = (row * input.width + x) * PipelineInput.CHANNELS
            repeat(w) {
                out[o] = rgb[i]
                out[o + 1] = rgb[i + 1]
                out[o + 2] = rgb[i + 2]
                out[o + RGBA - 1] = 1f
                i += PipelineInput.CHANNELS
                o += RGBA
            }
        }
    }
}

/**
 * A decoded full-size RAW in native memory ([NativeLinearImage], ADR 0004), read upright through [orientation]
 * (EXIF 1..8). Closing the source closes the image (frees LibRaw's buffer).
 */
class NativeRawTileSource(
    private val image: NativeLinearImage,
    info: RawColorInfo,
    private val orientation: Int = info.orientation,
) : TileSource {
    private val size = PipelineInputMapper.orientedSize(image.width, image.height, orientation)
    override val width: Int = size.first
    override val height: Int = size.second
    override val colour: PipelineInput = PipelineInput(
        kind = InputKind.SCENE_REFERRED,
        width = 1,
        height = 1,
        linearRgb = FloatArray(PipelineInput.CHANNELS),
        cameraToXyz = (info.cameraToXyz ?: throw UnsupportedCameraException(info.make, info.model)).let {
            @Suppress("SpreadOperator")
            Mat3(*it.values.toDoubleArray())
        },
        asShotMultipliers = PipelineInputMapper.multipliers(info),
        saturation = PipelineInputMapper.saturation(info),
        noise = PipelineInputMapper.noise(info),
    )
    private val samples: ShortBuffer = image.samples
    private var row = ShortArray(0)

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val c = PipelineInput.CHANNELS
        if (orientation !in 2..ORIENTATIONS) {
            if (row.size < w * c) row = ShortArray(w * c)
            var o = 0
            for (r in y until y + h) {
                samples.position((r * image.width + x) * c)
                samples.get(row, 0, w * c)
                var i = 0
                repeat(w) {
                    out[o] = (row[i].toInt() and MASK) * SCALE
                    out[o + 1] = (row[i + 1].toInt() and MASK) * SCALE
                    out[o + 2] = (row[i + 2].toInt() and MASK) * SCALE
                    out[o + RGBA - 1] = 1f
                    i += c
                    o += RGBA
                }
            }
            return
        }
        var o = 0
        for (r in y until y + h) {
            for (col in x until x + w) {
                val (sx, sy) = PipelineInputMapper.storedPixel(col, r, image.width, image.height, orientation)
                val i = (sy * image.width + sx) * c
                out[o] = (samples.get(i).toInt() and MASK) * SCALE
                out[o + 1] = (samples.get(i + 1).toInt() and MASK) * SCALE
                out[o + 2] = (samples.get(i + 2).toInt() and MASK) * SCALE
                out[o + RGBA - 1] = 1f
                o += RGBA
            }
        }
    }

    override fun close() = image.close()
}

/**
 * A decoded standard image (JPEG, HEIF, PNG, WebP) as a software `ARGB_8888` [bitmap] in its own colour space (native
 * memory), linearised per tile through its transfer function (the same table as `StandardDecoder`). [orientation] is
 * the container's EXIF orientation. Closing recycles the bitmap.
 */
class BitmapTileSource(private val bitmap: Bitmap, private val orientation: Int = 1) : TileSource {
    private val space = bitmap.colorSpace as? ColorSpace.Rgb ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
    private val info: ColorInfo = StandardDecoder.colorInfoOf(space)
    private val lut: ShortArray = space.eotf.let { eotf -> Linearize.lut { eotf.applyAsDouble(it) } }
    private val size = PipelineInputMapper.orientedSize(bitmap.width, bitmap.height, orientation)
    override val width: Int = size.first
    override val height: Int = size.second
    override val colour: PipelineInput = PipelineInput(
        InputKind.DISPLAY_REFERRED,
        1,
        1,
        FloatArray(PipelineInput.CHANNELS),
        @Suppress("SpreadOperator") Mat3(*info.toXyz.values.toDoubleArray()),
        null,
    )

    init {
        require(bitmap.config == Bitmap.Config.ARGB_8888) { "Expected ARGB_8888" }
    }

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val stored = storedBounds(x, y, w, h, bitmap.width, bitmap.height, orientation)
        val part = Bitmap.createBitmap(bitmap, stored.left, stored.top, stored.width, stored.height)
        try {
            linearise(part, stored.left, stored.top, x, y, w, h, out, bitmap.width, bitmap.height, orientation, lut)
        } finally {
            if (part !== bitmap) part.recycle()
        }
    }

    override fun close() = bitmap.recycle()
}

/**
 * A standard image (JPEG, HEIF, PNG, WebP) read in regions through a [BitmapRegionDecoder] on its [descriptor], as the
 * library viewer's 100 % zoom does: no full-size bitmap in memory, a region of a few hundred pixels decoded per read
 * (the editor's zoom tiles, docs/adr/0012). Regions are decoded with a [MARGIN] of stored pixels around them so the
 * decoder's chroma upsampling sees the same neighbours as a full decode. Pixels are linearised exactly like
 * [BitmapTileSource]. [onClose] runs on [close] (closes the document).
 */
class RegionTileSource(
    descriptor: ParcelFileDescriptor,
    private val orientation: Int = 1,
    private val onClose: () -> Unit = {},
) : TileSource {
    private val decoder: BitmapRegionDecoder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        BitmapRegionDecoder.newInstance(descriptor)
    } else {
        @Suppress("DEPRECATION")
        BitmapRegionDecoder.newInstance(descriptor.fileDescriptor, false)
    } ?: throw java.io.IOException("No region decoder")
    private val storedWidth = decoder.width
    private val storedHeight = decoder.height
    private val space: ColorSpace.Rgb = decoder.decodeRegion(Rect(0, 0, 1, 1), BitmapFactory.Options()).let {
        val cs = it.colorSpace as? ColorSpace.Rgb ?: ColorSpace.get(ColorSpace.Named.SRGB) as ColorSpace.Rgb
        it.recycle()
        // The regions are asked for in this space: one the platform accepts (an HDR space is not one).
        StandardDecoder.decodableSpace(cs) as ColorSpace.Rgb
    }
    private val info: ColorInfo = StandardDecoder.colorInfoOf(space)
    private val lut: ShortArray = space.eotf.let { eotf -> Linearize.lut { eotf.applyAsDouble(it) } }
    private val size = PipelineInputMapper.orientedSize(storedWidth, storedHeight, orientation)
    override val width: Int = size.first
    override val height: Int = size.second
    override val colour: PipelineInput = PipelineInput(
        InputKind.DISPLAY_REFERRED,
        1,
        1,
        FloatArray(PipelineInput.CHANNELS),
        @Suppress("SpreadOperator") Mat3(*info.toXyz.values.toDoubleArray()),
        null,
    )

    private var block: Bitmap? = null
    private var blockLeft = 0
    private var blockTop = 0

    /**
     * Readers ask for a tile in bands of a few dozen rows: the first band decodes the stored block holding the next
     * [BLOCK_ROWS] upright rows of those columns (one region decode per tile), later bands are cut from it.
     */
    @Synchronized
    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) {
        val stored = storedBounds(x, y, w, h, storedWidth, storedHeight, orientation)
        val cached = block?.takeIf {
            stored.left >= blockLeft && stored.top >= blockTop &&
                stored.left + stored.width <= blockLeft + it.width && stored.top + stored.height <= blockTop + it.height
        } ?: decodeBlock(x, y, w, h)
        linearise(cached, blockLeft, blockTop, x, y, w, h, out, storedWidth, storedHeight, orientation, lut)
    }

    private fun decodeBlock(x: Int, y: Int, w: Int, h: Int): Bitmap {
        block?.recycle()
        block = null
        val rows = minOf(height - y, maxOf(h, BLOCK_ROWS))
        val stored = storedBounds(x, y, w, rows, storedWidth, storedHeight, orientation)
        val left = maxOf(0, stored.left - MARGIN)
        val top = maxOf(0, stored.top - MARGIN)
        val right = minOf(storedWidth, stored.left + stored.width + MARGIN)
        val bottom = minOf(storedHeight, stored.top + stored.height + MARGIN)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = space
        }
        val part = decoder.decodeRegion(Rect(left, top, right, bottom), options)
            ?: throw java.io.IOException("Region decode failed")
        block = part
        blockLeft = left
        blockTop = top
        return part
    }

    @Synchronized
    override fun close() {
        block?.recycle()
        block = null
        decoder.recycle()
        onClose()
    }

    private companion object {
        /** Stored pixels decoded around a region (JPEG MCUs are at most 16 px; chroma upsampling reads 1). */
        const val MARGIN = 16

        /** Upright rows decoded at once (a zoom tile with its apron is 672). */
        const val BLOCK_ROWS = 1024
    }
}

/** A stored-orientation rectangle. */
private class StoredBox(val left: Int, val top: Int, val width: Int, val height: Int)

/** The stored rectangle of a [sw] x [sh] image that holds the upright region ([x], [y], [w] x [h]). */
@Suppress("LongParameterList")
private fun storedBounds(x: Int, y: Int, w: Int, h: Int, sw: Int, sh: Int, orientation: Int): StoredBox {
    val corners = listOf(x to y, x + w - 1 to y + h - 1).map { (cx, cy) ->
        PipelineInputMapper.storedPixel(cx, cy, sw, sh, orientation)
    }
    val sx0 = minOf(corners[0].first, corners[1].first)
    val sy0 = minOf(corners[0].second, corners[1].second)
    val sx1 = maxOf(corners[0].first, corners[1].first)
    val sy1 = maxOf(corners[0].second, corners[1].second)
    return StoredBox(sx0, sy0, sx1 - sx0 + 1, sy1 - sy0 + 1)
}

/**
 * Writes the upright region ([x], [y], [w] x [h]) of an image of stored size [sw] x [sh] as linear RGBA floats into
 * [out], reading [part] (an `ARGB_8888` bitmap whose pixel (0, 0) is stored pixel ([px0], [py0])) through [lut].
 */
@Suppress("LongParameterList")
private fun linearise(
    part: Bitmap,
    px0: Int,
    py0: Int,
    x: Int,
    y: Int,
    w: Int,
    h: Int,
    out: FloatArray,
    sw: Int,
    sh: Int,
    orientation: Int,
    lut: ShortArray,
) {
    val bytes = ByteArray(part.rowBytes * part.height)
    part.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
    val stride = part.rowBytes
    var o = 0
    for (r in y until y + h) {
        for (col in x until x + w) {
            val (px, py) = PipelineInputMapper.storedPixel(col, r, sw, sh, orientation)
            val i = (py - py0) * stride + (px - px0) * RGBA
            out[o] = (lut[bytes[i].toInt() and BYTE].toInt() and MASK) * SCALE
            out[o + 1] = (lut[bytes[i + 1].toInt() and BYTE].toInt() and MASK) * SCALE
            out[o + 2] = (lut[bytes[i + 2].toInt() and BYTE].toInt() and MASK) * SCALE
            out[o + RGBA - 1] = 1f
            o += RGBA
        }
    }
}

/** The same input without its pixels (one black pixel): enough for `DevelopParams.from`. */
fun PipelineInput.colourOnly(): PipelineInput = PipelineInput(
    kind,
    1,
    1,
    FloatArray(PipelineInput.CHANNELS),
    cameraToXyz,
    asShotMultipliers?.copyOf(),
    saturation,
    noise,
    lens,
)

private const val RGBA = 4
private const val MASK = 0xFFFF
private const val BYTE = 0xFF
private const val ORIENTATIONS = 8
private const val SCALE = 1f / LinearImage.MAX_VALUE

/** This source with [lens] as its lens profile (the colour metadata carries it; the pixels are unchanged). */
fun TileSource.withLens(lens: LensProfile?): TileSource = if (lens == null) this else LensTileSource(this, lens)

private class LensTileSource(private val delegate: TileSource, lens: LensProfile) : TileSource {
    override val width: Int get() = delegate.width
    override val height: Int get() = delegate.height
    override val colour: PipelineInput = delegate.colour.withLens(lens)

    override fun read(x: Int, y: Int, w: Int, h: Int, out: FloatArray) = delegate.read(x, y, w, h, out)

    override fun close() = delegate.close()
}
