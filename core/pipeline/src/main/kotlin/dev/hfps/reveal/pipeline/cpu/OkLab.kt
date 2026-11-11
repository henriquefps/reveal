@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

/**
 * OkLab (Björn Ottosson, "A perceptual color space for image processing", 2020) on the working space: linear
 * Rec.2020 to LMS with one matrix (Ottosson's `M1`, defined on XYZ D65, times Rec.2020-to-XYZ), a signed cube
 * root, then `M2` to `L, a, b`. Chosen for the colour operators (ADR 0008) because its hue lines are close to
 * constant perceived hue (better than CIELAB in the blues, which drift to purple there), it is cheap (two 3x3 and
 * three cube roots) and its inverse is exact, so a chroma scale is a pure saturation change with no hue shift.
 *
 * The matrices are computed once in double and rounded to float; the shaders get the same float constants
 * (`gl/ColourShaders.kt` prints them), so CPU and GPU start from identical numbers.
 */
object OkLab {
    private val m1Xyz = Mat3(
        0.8189330101, 0.3618667424, -0.1288597137,
        0.0329845436, 0.9293118715, 0.0361456387,
        0.0482003018, 0.2643662691, 0.6338517070,
    )
    private val m2 = Mat3(
        0.2104542553, 0.7936177850, -0.0040720468,
        1.9779984951, -2.4285922050, 0.4505937099,
        0.0259040371, 0.7827717662, -0.8086757660,
    )

    /**
     * `M1 * Rec2020toXYZ` with each row scaled so that the working space's white (1, 1, 1) is LMS (1, 1, 1) exactly.
     * Ottosson's M1 is normalised to D65 as XYZ (0.95047, 1, 1.08883); our D65 comes from xy (0.3127, 0.3290)
     * (ADR 0002) and differs in the 5th digit, which would leave greys with a chroma of about 1e-4. The row scales are
     * within 1e-4 of 1.
     */
    private val rgbToLms: Mat3 = (m1Xyz * ColorSpaces.REC2020.toXyz).let { m ->
        val w = m.times(1.0, 1.0, 1.0)
        Mat3.diagonal(1.0 / w[0], 1.0 / w[1], 1.0 / w[2]) * m
    }

    /** Linear Rec.2020 to LMS (row-major float). */
    val RGB_TO_LMS: FloatArray = rgbToLms.toFloatArray()

    /** Cube-rooted LMS to Lab. */
    val LMS_TO_LAB: FloatArray = m2.toFloatArray()

    /** Lab to cube-rooted LMS. */
    val LAB_TO_LMS: FloatArray = m2.inverse().toFloatArray()

    /** LMS to linear Rec.2020. */
    val LMS_TO_RGB: FloatArray = rgbToLms.inverse().toFloatArray()

    /** Signed cube root (negative LMS from out-of-gamut values stays negative and invertible). */
    fun cbrt(v: Float): Float = StrictMath.cbrt(v.toDouble()).toFloat()

    /** Linear Rec.2020 ([r], [g], [b]) to Lab, written into [out]. */
    fun fromRgb(r: Float, g: Float, b: Float, out: FloatArray) {
        val m = RGB_TO_LMS
        val l = cbrt(m[0] * r + m[1] * g + m[2] * b)
        val mm = cbrt(m[3] * r + m[4] * g + m[5] * b)
        val s = cbrt(m[6] * r + m[7] * g + m[8] * b)
        val n = LMS_TO_LAB
        out[0] = n[0] * l + n[1] * mm + n[2] * s
        out[1] = n[3] * l + n[4] * mm + n[5] * s
        out[2] = n[6] * l + n[7] * mm + n[8] * s
    }

    /** Lab ([lightness], [a], [b]) to linear Rec.2020, written into [out]. */
    fun toRgb(lightness: Float, a: Float, b: Float, out: FloatArray) {
        val n = LAB_TO_LMS
        val l = n[0] * lightness + n[1] * a + n[2] * b
        val mm = n[3] * lightness + n[4] * a + n[5] * b
        val s = n[6] * lightness + n[7] * a + n[8] * b
        val l3 = l * l * l
        val m3 = mm * mm * mm
        val s3 = s * s * s
        val m = LMS_TO_RGB
        out[0] = m[0] * l3 + m[1] * m3 + m[2] * s3
        out[1] = m[3] * l3 + m[4] * m3 + m[5] * s3
        out[2] = m[6] * l3 + m[7] * m3 + m[8] * s3
    }
}
