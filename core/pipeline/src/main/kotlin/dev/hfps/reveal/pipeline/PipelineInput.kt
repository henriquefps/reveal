package dev.hfps.reveal.pipeline

import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.Mat3

/** What the pixel values of a [PipelineInput] mean; it decides whether the base look is applied. */
enum class InputKind {
    /** Scene-referred linear light (a demosaiced RAW): the Standard base look maps it to a display range. */
    SCENE_REFERRED,

    /** Display-referred (JPEG/HEIF/PNG, already rendered by a camera or editor): the base look is identity. */
    DISPLAY_REFERRED,
}

/**
 * What the develop pipeline consumes: a linear image plus what is needed to bring it into the working space.
 * Plain Kotlin on purpose; `:core:image` produces a `LinearImage` + `RawColorInfo` and a later integration step
 * maps them onto this type (this type does not depend on `:core:image`).
 *
 * @property kind whether [linearRgb] is scene-referred (RAW) or display-referred (already tone-mapped, linearised
 *   only for processing); display-referred input renders as itself under the default recipe.
 * @property width image width in pixels.
 * @property height image height in pixels.
 * @property linearRgb interleaved R,G,B floats, row-major from the top-left, `width * height * 3` values. For a
 *   RAW this is demosaiced linear camera RGB with black level subtracted and the white level normalised to 1.0,
 *   with **no** white balance, matrix, curve or gamma applied. For JPEG/HEIF it is linear RGB in the file's own
 *   colour space (the one [cameraToXyz] describes). Values may exceed 1.0 or go below 0.0 (headroom, noise,
 *   out-of-gamut); the pipeline does not clamp.
 * @property cameraToXyz matrix taking **un-balanced** linear camera RGB to CIE XYZ (D65-referred, Y of white = 1).
 *   For a RAW this is the inverse of the camera's XYZ-to-camera calibration matrix (`cam_xyz` in LibRaw,
 *   ColorMatrix2 in DNG) for the D65 calibration illuminant, *not* normalised so that white maps to (1,1,1). For
 *   non-RAW use the matrix of the file's colour space ([linearSrgb], [linearDisplayP3]).
 * @property asShotMultipliers camera white-balance multipliers (R, G, B) with G = 1, i.e. the camera's idea of
 *   neutral as `1/multiplier`; `null` for non-RAW images (already neutral, treated as multipliers 1,1,1).
 * @property saturation value of [linearRgb] at which the sensor saturated (a decoded RAW: the lowest per-channel
 *   saturation point, at most 1.0); the raw white balance then clips highlights to neutral instead of magenta
 *   (`RawWhiteBalanceOp`). `null` = no known saturation (synthetic or merged data with real headroom): no clip.
 * @property noise what the file says about its noise (ISO, camera) for the Detail operator's noise model
 *   (`cpu/NoiseModel`); `null` = unknown (synthetic images, tests): the model's documented defaults apply.
 */
class PipelineInput(
    val kind: InputKind,
    val width: Int,
    val height: Int,
    val linearRgb: FloatArray,
    val cameraToXyz: Mat3,
    val asShotMultipliers: FloatArray?,
    val saturation: Float? = null,
    val noise: NoiseInfo? = null,
) {
    init {
        require(width > 0 && height > 0) { "image must not be empty" }
        require(linearRgb.size == width * height * CHANNELS) { "linearRgb must have width*height*3 values" }
        require(asShotMultipliers == null || asShotMultipliers.size == CHANNELS) { "need 3 as-shot multipliers" }
        require(asShotMultipliers == null || asShotMultipliers.all { it.isFinite() && it > 0f }) {
            "as-shot multipliers must be positive"
        }
        require(saturation == null || (saturation.isFinite() && saturation > 0f)) { "saturation must be positive" }
    }

    /** True for RAW input (has camera multipliers), false for already-balanced JPEG/HEIF input. */
    val isRaw: Boolean get() = asShotMultipliers != null

    companion object {
        /** Interleaved channels per pixel. */
        const val CHANNELS: Int = 3

        /** Wraps linear sRGB pixels (a JPEG/HEIF decoded and linearised by the image core). */
        fun linearSrgb(width: Int, height: Int, linearRgb: FloatArray): PipelineInput =
            PipelineInput(InputKind.DISPLAY_REFERRED, width, height, linearRgb, ColorSpaces.SRGB.toXyz, null)

        /** Wraps linear Display P3 pixels (a P3 HEIF decoded and linearised by the image core). */
        fun linearDisplayP3(width: Int, height: Int, linearRgb: FloatArray): PipelineInput =
            PipelineInput(InputKind.DISPLAY_REFERRED, width, height, linearRgb, ColorSpaces.DISPLAY_P3.toXyz, null)
    }
}

/**
 * The capture facts the noise model needs (docs/adr/0012, "Noise model"): the [iso] speed from EXIF and the camera's
 * [make] and [model] as written in the file; any of them may be unknown.
 */
data class NoiseInfo(val iso: Int? = null, val make: String? = null, val model: String? = null)
