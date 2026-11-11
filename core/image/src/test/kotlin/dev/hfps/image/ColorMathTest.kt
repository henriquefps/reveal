package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.Matrix3
import org.junit.Test

class ColorMathTest {
    private val srgbD65 = Matrix3(
        0.4124564, 0.3575761, 0.1804375,
        0.2126729, 0.7151522, 0.0721750,
        0.0193339, 0.1191920, 0.9503041,
    )
    private val displayP3D65 = Matrix3(
        0.4865709, 0.2656677, 0.1982173,
        0.2289746, 0.6917385, 0.0792869,
        0.0000000, 0.0451134, 1.0439444,
    )
    private val srgbD50 = Matrix3(
        0.4360747, 0.3850649, 0.1430804,
        0.2225045, 0.7168786, 0.0606169,
        0.0139322, 0.0971045, 0.7141733,
    )

    @Test
    fun `rgbToXyz reproduces the sRGB and Display P3 matrices`() {
        assertThat(ColorMath.rgbToXyz(ColorMath.SRGB).isCloseTo(srgbD65, 3e-4)).isTrue()
        assertThat(ColorMath.rgbToXyzD65(ColorMath.DISPLAY_P3).isCloseTo(displayP3D65, 3e-4)).isTrue()
    }

    @Test
    fun `white maps to D65 white`() {
        for (space in listOf(ColorMath.SRGB, ColorMath.DISPLAY_P3, ColorMath.ADOBE_RGB, ColorMath.REC2020)) {
            val (x, y, z) = ColorMath.rgbToXyzD65(space).apply(1.0, 1.0, 1.0)
            val white = ColorMath.xyz(ColorMath.D65)
            assertThat(x).isWithin(1e-6).of(white.first)
            assertThat(y).isWithin(1e-6).of(1.0)
            assertThat(z).isWithin(1e-6).of(white.third)
        }
    }

    @Test
    fun `Bradford adaptation from D65 to D50 gives the D50 sRGB matrix`() {
        val adapted = ColorMath.adaptation(ColorMath.D65, ColorMath.D50) * ColorMath.rgbToXyz(ColorMath.SRGB)
        assertThat(adapted.isCloseTo(srgbD50, 2e-3)).isTrue()
        val back = ColorMath.adaptation(ColorMath.D50, ColorMath.D65) * adapted
        assertThat(back.isCloseTo(ColorMath.rgbToXyz(ColorMath.SRGB), 1e-9)).isTrue()
    }

    @Test
    fun `a D50 white space is adapted to D65`() {
        val prophotoLike = ColorMath.SRGB.copy(white = ColorMath.D50)
        val (x, y, z) = ColorMath.rgbToXyzD65(prophotoLike).apply(1.0, 1.0, 1.0)
        val white = ColorMath.xyz(ColorMath.D65)
        assertThat(x).isWithin(1e-6).of(white.first)
        assertThat(y).isWithin(1e-6).of(1.0)
        assertThat(z).isWithin(1e-6).of(white.third)
    }

    @Test
    fun `sRGB transfer function`() {
        assertThat(ColorMath.srgbToLinear(0.0)).isEqualTo(0.0)
        assertThat(ColorMath.srgbToLinear(1.0)).isWithin(1e-12).of(1.0)
        assertThat(ColorMath.srgbToLinear(0.5)).isWithin(1e-4).of(0.21404)
        assertThat(ColorMath.srgbToLinear(0.04)).isWithin(1e-12).of(0.04 / 12.92)
    }

    @Test
    fun `matrix inverse round trip and singular`() {
        val m = Matrix3(2.0, 0.5, 0.1, 0.3, 1.5, 0.2, 0.1, 0.4, 3.0)
        val identity = m * requireNotNull(m.inverse())
        assertThat(identity.isCloseTo(Matrix3.IDENTITY, 1e-12)).isTrue()
        assertThat(Matrix3(1.0, 2.0, 3.0, 2.0, 4.0, 6.0, 0.0, 0.0, 1.0).inverse()).isNull()
    }

    @Test
    fun `normalizeToGreen`() {
        val wb = ColorMath.normalizeToGreen(2.0, 1.0, 1.5)!!
        assertThat(wb.red).isEqualTo(2.0)
        assertThat(wb.green).isEqualTo(1.0)
        assertThat(wb.blue).isEqualTo(1.5)
        assertThat(ColorMath.normalizeToGreen(1.0, 0.0, 1.0)).isNull()
        assertThat(ColorMath.normalizeToGreen(-1.0, 1.0, 1.0)).isNull()
    }

    /** Reproduces LibRaw's `cam_xyz_coeff` (dcraw): from XYZ->camera it builds `rgb_cam` and `pre_mul`. */
    private fun libRawTables(camXyz: Matrix3): Pair<List<Double>, List<Double>> {
        val xyzRgb = Matrix3(
            0.412453, 0.357580, 0.180423,
            0.212671, 0.715160, 0.072169,
            0.019334, 0.119193, 0.950227,
        )
        val camRgb = camXyz * xyzRgb
        val preMul = (0..2).map { 1.0 / (0..2).sumOf { j -> camRgb[it, j] } }
        val normalized = Matrix3(List(9) { camRgb[it / 3, it % 3] * preMul[it / 3] })
        val inverse = requireNotNull(normalized.inverse())
        // rgb_cam is 3 x 4, the fourth column unused (zero).
        val rgbCam = (0..2).flatMap { r -> (0..2).map { c -> inverse[r, c] } + 0.0 }
        return rgbCam to (preMul + 1.0)
    }

    @Test
    fun `camera to XYZ is the inverse of the XYZ to camera matrix LibRaw started from`() {
        // XYZ (D65) -> camera, as in an Adobe ColorMatrix2 of an E-mount sensor.
        val camXyz = Matrix3(0.7657, -0.2847, -0.0607, -0.4083, 1.1966, 0.2389, -0.0684, 0.1418, 0.5844)
        val (rgbCam, preMul) = libRawTables(camXyz)
        val cameraToXyz = ColorMath.cameraToXyzD65(rgbCam, preMul)!!
        assertThat(cameraToXyz.isCloseTo(camXyz.inverse()!!, 1e-4)).isTrue()
        // The daylight white of the sensor (1 / pre_mul per channel) is D65 white.
        val (x, y, z) = cameraToXyz.apply(1 / preMul[0], 1 / preMul[1], 1 / preMul[2])
        val white = ColorMath.xyz(ColorMath.D65)
        assertThat(x / y).isWithin(2e-3).of(white.first)
        assertThat(z / y).isWithin(2e-3).of(white.third)
    }

    @Test
    fun `no camera matrix gives null`() {
        assertThat(ColorMath.cameraToXyzD65(List(12) { 0.0 }, listOf(1.0, 1.0, 1.0, 1.0))).isNull()
    }
}
