// Colour constants and fixed array layouts: named inline, not as constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.color

import kotlin.math.pow

/** CIE xy chromaticity. */
data class Chromaticity(val x: Double, val y: Double)

/** Red, green and blue primaries and the white point of an RGB colour space, as xy chromaticities. */
data class Primaries(val red: Chromaticity, val green: Chromaticity, val blue: Chromaticity, val white: Chromaticity)

/** Per-channel white balance multipliers on linear camera RGB, normalised so green is 1. */
data class WhiteBalance(val red: Double, val green: Double, val blue: Double)

/** Colour maths shared by the RAW and the standard-image paths. Pure Kotlin, JVM-tested. */
object ColorMath {
    /** D65 white point: the reference white of sRGB, Display P3, Adobe RGB, Rec. 2020 and of every matrix reported. */
    val D65 = Chromaticity(0.3127, 0.3290)

    /** D50 white point (ICC profile connection space; ProPhoto). */
    val D50 = Chromaticity(0.3457, 0.3585)

    val SRGB = Primaries(Chromaticity(0.640, 0.330), Chromaticity(0.300, 0.600), Chromaticity(0.150, 0.060), D65)
    val DISPLAY_P3 = Primaries(Chromaticity(0.680, 0.320), Chromaticity(0.265, 0.690), Chromaticity(0.150, 0.060), D65)
    val ADOBE_RGB = Primaries(Chromaticity(0.640, 0.330), Chromaticity(0.210, 0.710), Chromaticity(0.150, 0.060), D65)
    val REC2020 = Primaries(Chromaticity(0.708, 0.292), Chromaticity(0.170, 0.797), Chromaticity(0.131, 0.046), D65)

    /** Bradford cone response matrix. */
    private val bradford = Matrix3(
        0.8951, 0.2664, -0.1614,
        -0.7502, 1.7135, 0.0367,
        0.0389, -0.0685, 1.0296,
    )

    /** The sRGB (D65) to XYZ matrix LibRaw multiplies its camera matrices out with (`xyz_rgb`, colorconst). */
    private val libRawSrgbToXyz = Matrix3(
        0.412453, 0.357580, 0.180423,
        0.212671, 0.715160, 0.072169,
        0.019334, 0.119193, 0.950227,
    )

    /** XYZ (Y = 1) of a chromaticity. */
    fun xyz(white: Chromaticity): Triple<Double, Double, Double> =
        Triple(white.x / white.y, 1.0, (1.0 - white.x - white.y) / white.y)

    /**
     * Matrix taking linear RGB in [primaries] (RGB (1,1,1) = the primaries' own white) to CIE XYZ relative
     * to that white (Y = 1).
     */
    fun rgbToXyz(primaries: Primaries): Matrix3 {
        val r = xyz(primaries.red)
        val g = xyz(primaries.green)
        val b = xyz(primaries.blue)
        val columns = Matrix3(r.first, g.first, b.first, r.second, g.second, b.second, r.third, g.third, b.third)
        val inverse = requireNotNull(columns.inverse()) { "Degenerate primaries $primaries" }
        val white = xyz(primaries.white)
        val (sr, sg, sb) = inverse.apply(white.first, white.second, white.third)
        return columns.scaleColumns(sr, sg, sb)
    }

    /** Bradford chromatic adaptation taking XYZ under [from] to XYZ under [to]. */
    fun adaptation(from: Chromaticity, to: Chromaticity): Matrix3 {
        val source = xyz(from)
        val target = xyz(to)
        val (fx, fy, fz) = bradford.apply(source.first, source.second, source.third)
        val (tx, ty, tz) = bradford.apply(target.first, target.second, target.third)
        val scale = Matrix3(tx / fx, 0.0, 0.0, 0.0, ty / fy, 0.0, 0.0, 0.0, tz / fz)
        return requireNotNull(bradford.inverse()) * scale * bradford
    }

    /** [rgbToXyz] adapted to D65 (a no-op for spaces whose white already is D65). */
    fun rgbToXyzD65(primaries: Primaries): Matrix3 {
        val native = rgbToXyz(primaries)
        return if (primaries.white == D65) native else adaptation(primaries.white, D65) * native
    }

    /** sRGB transfer function inverse: encoded 0..1 to linear 0..1 (IEC 61966-2-1). */
    fun srgbToLinear(encoded: Double): Double = if (encoded <=
        SRGB_KNEE
    ) {
        encoded / SRGB_SLOPE
    } else {
        ((encoded + SRGB_OFFSET) / (1 + SRGB_OFFSET)).pow(SRGB_GAMMA)
    }

    /** Pure power-law decode (Adobe RGB uses 563/256). */
    fun gammaToLinear(encoded: Double, gamma: Double): Double = encoded.pow(gamma)

    /**
     * Camera RGB to XYZ (D65) from LibRaw's tables: LibRaw stores `rgb_cam` (3 x 4: camera RGB scaled by the
     * daylight multipliers, to linear sRGB) and `pre_mul` (the daylight multipliers), so for raw camera values
     * `sRGB = rgb_cam * diag(pre_mul) * camera` and `XYZ = sRGB_to_XYZ * sRGB`. Only the first three camera
     * channels are used (4-colour sensors are not supported). [rgbCam] is row-major 3 x 4. Null when LibRaw has
     * no matrix for the camera (all zeros).
     */
    fun cameraToXyzD65(rgbCam: List<Double>, preMul: List<Double>): Matrix3? {
        require(rgbCam.size == RGB_CAM_SIZE && preMul.size >= 3) { "rgb_cam is 3x4 and pre_mul has at least 3 entries" }
        val cameraToSrgb = Matrix3(List(9) { i -> rgbCam[4 * (i / 3) + (i % 3)] * preMul[i % 3] })
        if (cameraToSrgb.values.all { it == 0.0 }) return null
        return libRawSrgbToXyz * cameraToSrgb
    }

    /** Multipliers normalised so that green is 1 (null when any is not positive). */
    fun normalizeToGreen(red: Double, green: Double, blue: Double): WhiteBalance? =
        if (green > 0.0 && red > 0.0 && blue > 0.0) WhiteBalance(red / green, 1.0, blue / green) else null

    private const val RGB_CAM_SIZE = 12
    private const val SRGB_KNEE = 0.04045
    private const val SRGB_SLOPE = 12.92
    private const val SRGB_OFFSET = 0.055
    private const val SRGB_GAMMA = 2.4
}
