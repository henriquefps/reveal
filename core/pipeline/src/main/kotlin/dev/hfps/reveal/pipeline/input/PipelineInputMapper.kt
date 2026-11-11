package dev.hfps.reveal.pipeline.input

import dev.hfps.image.LinearImage
import dev.hfps.image.color.ColorInfo
import dev.hfps.image.color.Matrix3
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.color.WhiteBalance
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.cpu.Mat3

/** The RAW decoded fine, but LibRaw has no colour matrix for its camera, so it cannot be developed correctly. */
class UnsupportedCameraException(val make: String?, val model: String?) :
    IllegalArgumentException("No colour matrix for camera ${make.orEmpty()} ${model.orEmpty()}".trim())

/**
 * Maps what `:core:image` decodes (a [LinearImage] plus its [ColorInfo]) onto the pipeline's [PipelineInput]
 * (ADR 0004, "Mapping onto PipelineInput"; ADR 0002 for the meaning of each field). Plain Kotlin, JVM-tested.
 *
 * - `kind`: a [RawColorInfo] is scene-referred; every other source (JPEG/HEIF/PNG/WebP) is display-referred.
 * - `linearRgb`: `sample / 65535` (white level = 1.0, no white balance, matrix or curve), **rotated upright** by the
 *   EXIF orientation, so the pipeline, the canvas and later exports all work in display orientation.
 * - `cameraToXyz`: [ColorInfo.toXyz] for standard images; for a RAW the un-normalised camera matrix
 *   ([RawColorInfo.cameraToXyz]); a RAW without one throws [UnsupportedCameraException] (developing it with a
 *   made-up matrix would show wrong colours as if they were right).
 * - `saturation`: where the RAW's channels clip (so highlights clip to neutral, not magenta); none for standard images.
 * - `asShotMultipliers`: the file's as-shot multipliers, else the daylight ones derived from the matrix, else
 *   neutral (1, 1, 1); always normalised to green = 1. `null` for standard images (already balanced).
 */
object PipelineInputMapper {

    /**
     * @param orientation EXIF orientation 1..8 to apply; defaults to the colour info's. Standard decoders do not
     *   report it, so callers pass the container's (from `ImageInput.inspection`).
     */
    fun map(image: LinearImage, info: ColorInfo, orientation: Int = info.orientation): PipelineInput {
        val raw = info as? RawColorInfo
        val (width, height) = orientedSize(image.width, image.height, orientation)
        val pixels = orientedLinear(image, orientation)
        return if (raw != null) {
            val matrix = raw.cameraToXyz ?: throw UnsupportedCameraException(raw.make, raw.model)
            PipelineInput(
                kind = InputKind.SCENE_REFERRED,
                width = width,
                height = height,
                linearRgb = pixels,
                cameraToXyz = matrix.toMat3(),
                asShotMultipliers = multipliers(raw),
                saturation = saturation(raw),
            )
        } else {
            PipelineInput(
                kind = InputKind.DISPLAY_REFERRED,
                width = width,
                height = height,
                linearRgb = pixels,
                cameraToXyz = info.toXyz.toMat3(),
                asShotMultipliers = null,
            )
        }
    }

    /** As-shot multipliers (R, G, B) with G = 1: the file's, else daylight, else neutral. */
    fun multipliers(info: RawColorInfo): FloatArray {
        val wb = listOfNotNull(info.asShotWhiteBalance, info.daylightWhiteBalance).firstOrNull { it.isUsable() }
            ?: return floatArrayOf(1f, 1f, 1f)
        return floatArrayOf((wb.red / wb.green).toFloat(), 1f, (wb.blue / wb.green).toFloat())
    }

    /**
     * Where the decoded samples clip, as a fraction of the white level: the lowest per-channel saturation point
     * (`levels.saturationLinear16`, R G B; the decoder also clips everything at the white level, so at most 1.0).
     */
    fun saturation(info: RawColorInfo): Float {
        val lowest = info.levels.saturationLinear16.take(PipelineInput.CHANNELS).minOrNull() ?: LinearImage.MAX_VALUE
        return lowest.coerceIn(1, LinearImage.MAX_VALUE).toFloat() / LinearImage.MAX_VALUE
    }

    /** Size of an image of [width] x [height] stored pixels once [orientation] is applied. */
    fun orientedSize(width: Int, height: Int, orientation: Int): Pair<Int, Int> =
        if (transposes(orientation)) height to width else width to height

    /**
     * The stored pixel (x, y) shown at upright position ([x], [y]) for an image stored as [storedWidth] x
     * [storedHeight] with EXIF [orientation] (the pixel-index form of `dev.hfps.image.decode.Orientation.toStored`).
     */
    @Suppress("CyclomaticComplexMethod", "MagicNumber")
    fun storedPixel(x: Int, y: Int, storedWidth: Int, storedHeight: Int, orientation: Int): Pair<Int, Int> {
        val maxX = storedWidth - 1
        val maxY = storedHeight - 1
        return when (orientation) {
            2 -> (maxX - x) to y
            3 -> (maxX - x) to (maxY - y)
            4 -> x to (maxY - y)
            5 -> y to x
            6 -> y to (maxY - x)
            7 -> (maxX - y) to (maxY - x)
            8 -> (maxX - y) to x
            else -> x to y
        }
    }

    /** Converts to floats (`/ 65535`) and applies [orientation] in the same pass (one allocation). */
    private fun orientedLinear(image: LinearImage, orientation: Int): FloatArray {
        val channels = PipelineInput.CHANNELS
        val src = image.data
        val out = FloatArray(src.size)
        if (orientation !in ORIENTATIONS || orientation == 1) {
            for (i in src.indices) out[i] = (src[i].toInt() and MASK) * SCALE
            return out
        }
        val (width, height) = orientedSize(image.width, image.height, orientation)
        var o = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val (sx, sy) = storedPixel(x, y, image.width, image.height, orientation)
                val s = (sy * image.width + sx) * channels
                out[o] = (src[s].toInt() and MASK) * SCALE
                out[o + 1] = (src[s + 1].toInt() and MASK) * SCALE
                out[o + 2] = (src[s + 2].toInt() and MASK) * SCALE
                o += channels
            }
        }
        return out
    }

    @Suppress("MagicNumber")
    private fun transposes(orientation: Int) = orientation in 5..8

    private fun WhiteBalance.isUsable() = listOf(red, green, blue).all { it.isFinite() && it > 0.0 }

    @Suppress("SpreadOperator")
    private fun Matrix3.toMat3(): Mat3 = Mat3(*values.toDoubleArray())

    private const val MASK = 0xFFFF
    private const val SCALE = 1f / LinearImage.MAX_VALUE
    private val ORIENTATIONS = 1..8
}
