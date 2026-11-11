package dev.hfps.reveal.pipeline.input

import android.graphics.Bitmap
import android.graphics.ColorSpace
import dev.hfps.image.LinearImage
import dev.hfps.image.NativeLinearImage
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.standard.Linearize
import dev.hfps.image.standard.StandardDecoder
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.Mat3
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
        // The stored rectangle that holds the upright one, copied without colour conversion (same space, no scaling).
        val corners = listOf(x to y, x + w - 1 to y + h - 1).map { (cx, cy) ->
            PipelineInputMapper.storedPixel(cx, cy, bitmap.width, bitmap.height, orientation)
        }
        val sx0 = minOf(corners[0].first, corners[1].first)
        val sy0 = minOf(corners[0].second, corners[1].second)
        val sw = maxOf(corners[0].first, corners[1].first) - sx0 + 1
        val sh = maxOf(corners[0].second, corners[1].second) - sy0 + 1
        val part = Bitmap.createBitmap(bitmap, sx0, sy0, sw, sh)
        val bytes = ByteArray(part.rowBytes * sh)
        part.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
        val stride = part.rowBytes
        if (part !== bitmap) part.recycle()
        var o = 0
        for (r in y until y + h) {
            for (col in x until x + w) {
                val (px, py) = PipelineInputMapper.storedPixel(col, r, bitmap.width, bitmap.height, orientation)
                val i = (py - sy0) * stride + (px - sx0) * RGBA
                out[o] = (lut[bytes[i].toInt() and BYTE].toInt() and MASK) * SCALE
                out[o + 1] = (lut[bytes[i + 1].toInt() and BYTE].toInt() and MASK) * SCALE
                out[o + 2] = (lut[bytes[i + 2].toInt() and BYTE].toInt() and MASK) * SCALE
                out[o + RGBA - 1] = 1f
                o += RGBA
            }
        }
    }

    override fun close() = bitmap.recycle()
}

/** The same input without its pixels (one black pixel): enough for `DevelopParams.from`. */
fun PipelineInput.colourOnly(): PipelineInput =
    PipelineInput(kind, 1, 1, FloatArray(PipelineInput.CHANNELS), cameraToXyz, asShotMultipliers?.copyOf(), saturation)

private const val RGBA = 4
private const val MASK = 0xFFFF
private const val BYTE = 0xFF
private const val ORIENTATIONS = 8
private const val SCALE = 1f / LinearImage.MAX_VALUE
