package dev.hfps.image.color

/** An integer pixel rectangle: [left], [top] and the [width] and [height] of the area. */
data class PixelRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** What the pixels of a [dev.hfps.image.LinearImage] mean, for any source (RAW or standard image). */
interface ColorInfo {
    /**
     * Matrix taking the image's linear RGB to CIE XYZ under D65, Y = 1 for RGB (1, 1, 1). For a RAW this is
     * camera RGB (no white balance applied) to XYZ; for a standard image it is the file's colour space (sRGB,
     * Display P3, ...) to XYZ.
     */
    val toXyz: Matrix3

    /** Where the primaries come from. */
    val colorSpace: SourceColorSpace

    /** As-shot white balance multipliers for the camera RGB (green = 1); null for standard images or when unknown. */
    val asShotWhiteBalance: WhiteBalance?

    /** EXIF orientation 1..8 of the stored pixels (never applied to the pixels). */
    val orientation: Int

    /** Camera make as written in the file, null when unknown. */
    val make: String?

    /** Camera model as written in the file, null when unknown. */
    val model: String?
}

/** The colour space an image's pixels are expressed in. */
enum class SourceColorSpace {
    /** Raw camera RGB (a RAW decoded without white balance or colour conversion). */
    CAMERA,
    SRGB,
    DISPLAY_P3,
    ADOBE_RGB,
    REC2020,

    /** Another RGB space (an embedded ICC profile); [ColorInfo.toXyz] holds its primaries. */
    OTHER_RGB,
}

/** Colour info of a decoded JPEG/HEIF/PNG/WebP: [toXyz] is the file's colour space, linearised. */
data class StandardColorInfo(
    override val toXyz: Matrix3,
    override val colorSpace: SourceColorSpace,
    override val orientation: Int = 1,
    override val make: String? = null,
    override val model: String? = null,
) : ColorInfo {
    override val asShotWhiteBalance: WhiteBalance? get() = null
}

/** Levels of one sensor channel, in raw sensor units. Channel order follows LibRaw: R, G, B, G2. */
data class RawLevels(
    /** Black level per channel (common black + per-channel offset), as subtracted before scaling. */
    val black: List<Int>,
    /**
     * White level: raw value that maps to 65535 after black subtraction (`maximum` in LibRaw: the table or file value,
     * not adjusted to the data).
     */
    val white: Int,
    /** Largest raw value found in the file (including black): below [white] when nothing clips. */
    val dataMaximum: Int,
    /**
     * Saturation point per channel in raw units (`linear_max` when the camera/file defines it, else [white]).
     * Highlight reconstruction treats a channel at or above this as clipped.
     */
    val saturation: List<Int>,
    /** Bits per raw sample as stored in the file (12, 14, ...), 0 when unknown. */
    val bitsPerSample: Int,
) {
    /**
     * [saturation] expressed in the output encoding (0..65535, after black subtraction and scaling by
     * `65535 / (white - black)`), clamped to 65535.
     */
    val saturationLinear16: List<Int>
        get() = saturation.mapIndexed { c, s ->
            val black = black[c]
            val span = (white - black).coerceAtLeast(1)
            ((s - black).coerceAtLeast(0).toLong() * MAX / span).coerceIn(0L, MAX.toLong()).toInt()
        }

    private companion object {
        const val MAX = 65535
    }
}

/**
 * Sony lens correction parameters as stored by the camera (maker notes / SR2 private data), raw and
 * uninterpreted: the camera-specific polynomial scaling is applied by the lens-correction work (M2).
 * A list is null when the file does not carry that block.
 */
data class SonyLensCorrection(
    /** Distortion correction parameters (16 signed values in ARW), null when absent. */
    val distortion: List<Int>?,
    /** Vignetting (shading) correction parameters (16 signed values), null when absent. */
    val vignetting: List<Int>?,
    /** Chromatic aberration correction parameters (32 signed values: red then blue), null when absent. */
    val chromaticAberration: List<Int>?,
)

/** CFA layout of a sensor. */
enum class CfaKind { BAYER, XTRANS, MONOCHROME, OTHER }

/**
 * Everything the pipeline needs to know about a RAW decoded to linear camera RGB. See `docs/adr/0004-image-core.md`
 * for how each field is derived from LibRaw.
 */
data class RawColorInfo(
    override val make: String?,
    override val model: String?,
    /** LibRaw's normalised make/model (stable names for lookup tables). */
    val normalizedMake: String?,
    val normalizedModel: String?,
    /** LibRaw version that decoded the file. */
    val libRawVersion: String,
    /**
     * Camera RGB (raw, unbalanced) to CIE XYZ under D65. Derived from LibRaw's colour tables as
     * `XYZ_from_sRGB * rgb_cam * diag(pre_mul)`; null when LibRaw has no matrix for this camera.
     */
    val cameraToXyz: Matrix3?,
    /** As-shot multipliers (R, 1, B) from the file; null when the file has none. */
    override val asShotWhiteBalance: WhiteBalance?,
    /** Multipliers that make D65 daylight neutral (from the colour matrix); null when unavailable. */
    val daylightWhiteBalance: WhiteBalance?,
    val levels: RawLevels,
    /** DNG BaselineExposure in EV (stops), null when the file has none. */
    val baselineExposureEv: Double?,
    override val orientation: Int,
    /** Size of the full sensor readout, including masked margins. */
    val sensorWidth: Int,
    val sensorHeight: Int,
    /** The active (image) area inside the sensor readout: what [dev.hfps.image.LinearImage] contains at full size. */
    val activeArea: PixelRect,
    /** The camera's recommended crop relative to [activeArea] (ARW `DefaultCrop`, DNG `DefaultCropOrigin`), or null. */
    val defaultCrop: PixelRect?,
    val cfa: CfaKind,
    /** CFA pattern of the top-left 2x2 for [CfaKind.BAYER] (e.g. `RGGB`), else null. */
    val cfaPattern: String?,
    val isDng: Boolean,
    val iso: Int?,
    /** Sony lens correction data; null for other makers or when absent. */
    val lensCorrection: SonyLensCorrection? = null,
) : ColorInfo {
    override val colorSpace: SourceColorSpace get() = SourceColorSpace.CAMERA

    /** [cameraToXyz], or the sRGB matrix as a fallback so that callers always get one. */
    override val toXyz: Matrix3 get() = cameraToXyz ?: ColorMath.rgbToXyz(ColorMath.SRGB)

    /** XYZ (D65) to camera RGB, null when [cameraToXyz] is missing or singular. */
    val xyzToCamera: Matrix3? get() = cameraToXyz?.inverse()
}
