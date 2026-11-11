package dev.hfps.reveal.pipeline.input

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.LinearImage
import dev.hfps.image.color.CfaKind
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.Matrix3
import dev.hfps.image.color.PixelRect
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.color.RawLevels
import dev.hfps.image.color.SourceColorSpace
import dev.hfps.image.color.StandardColorInfo
import dev.hfps.image.color.WhiteBalance
import dev.hfps.image.decode.IntRect
import dev.hfps.image.decode.Orientation
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.cpu.ColorSpaces
import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.abs
import org.junit.Assert.assertThrows
import org.junit.Test

class PipelineInputMapperTest {

    @Test
    fun `a RAW maps to scene-referred input with the un-normalised inverse of the camera matrix`() {
        val input = PipelineInputMapper.map(image(2, 1, 0, 32768, 65535, 65535, 1, 2), raw())
        assertThat(input.kind).isEqualTo(InputKind.SCENE_REFERRED)
        assertThat(input.isRaw).isTrue()
        // cameraToXyz * XYZ_to_camera = identity: the mapper passes the inverse of cam_xyz, not a normalised copy.
        val product = input.cameraToXyz * ZV_E10_XYZ_TO_CAMERA.toMat3()
        for (r in 0..2) for (c in 0..2) assertThat(product[r, c]).isWithin(1e-9).of(if (r == c) 1.0 else 0.0)
    }

    @Test
    fun `samples are normalised so the white level is 1`() {
        val input = PipelineInputMapper.map(image(2, 1, 0, 32768, 65535, 65535, 1, 2), raw())
        assertThat(input.linearRgb[0]).isEqualTo(0f)
        assertThat(input.linearRgb[1]).isWithin(1e-6f).of(32768f / 65535f)
        assertThat(input.linearRgb[2]).isEqualTo(1f)
        assertThat(input.linearRgb[5]).isWithin(1e-9f).of(2f / 65535f)
    }

    @Test
    fun `a RAW carries its saturation point, a standard image none`() {
        assertThat(PipelineInputMapper.map(image(1, 1), raw()).saturation).isEqualTo(1f)
        val info = StandardColorInfo(ColorMath.rgbToXyzD65(ColorMath.SRGB), SourceColorSpace.SRGB)
        assertThat(PipelineInputMapper.map(image(1, 1), info).saturation).isNull()
    }

    @Test
    fun `clipped highlights develop to neutral white, not magenta`() {
        val input = PipelineInputMapper.map(image(1, 1, 65535, 65535, 65535), raw(asShot = WhiteBalance(2.2, 1.0, 1.6)))
        val developed = CpuDevelop.renderLinear(input, Recipe.DEFAULT)
        assertThat(abs(developed[0] - developed[1])).isLessThan(1e-3f)
        assertThat(abs(developed[2] - developed[1])).isLessThan(1e-3f)
    }

    @Test
    fun `as-shot multipliers are normalised to green 1`() {
        val input = PipelineInputMapper.map(image(1, 1), raw(asShot = WhiteBalance(2.4, 1.2, 1.8)))
        assertThat(input.asShotMultipliers!!.toList()).containsExactly(2.0f, 1.0f, 1.5f).inOrder()
    }

    @Test
    fun `daylight multipliers stand in when the file has no as-shot white balance, then neutral`() {
        val daylight = PipelineInputMapper.map(image(1, 1), raw(asShot = null, daylight = WhiteBalance(2.0, 1.0, 1.6)))
        assertThat(daylight.asShotMultipliers!!.toList()).containsExactly(2.0f, 1.0f, 1.6f).inOrder()
        val unusable = WhiteBalance(0.0, 1.0, 1.0)
        val neutral = PipelineInputMapper.map(image(1, 1), raw(asShot = unusable, daylight = null))
        assertThat(neutral.asShotMultipliers!!.toList()).containsExactly(1f, 1f, 1f).inOrder()
    }

    @Test
    fun `a RAW without a colour matrix is rejected as an unsupported camera`() {
        val error = assertThrows(UnsupportedCameraException::class.java) {
            PipelineInputMapper.map(image(1, 1), raw(matrix = null))
        }
        assertThat(error.model).isEqualTo("ZV-E10")
    }

    @Test
    fun `the camera's response to D65 develops to a neutral grey with daylight multipliers`() {
        // Raw response of the camera to D65 white (XYZ, Y = 1): XYZ_to_camera * white, scaled under the white level.
        val response = ZV_E10_XYZ_TO_CAMERA.apply(D65_X, 1.0, D65_Z).toList()
        val peak = response.max()
        val samples = response.map { (it / peak * HALF).toInt() }
        val daylight = WhiteBalance(1.0 / response[0], 1.0 / response[1], 1.0 / response[2])
        val input = PipelineInputMapper.map(
            image(1, 1, samples[0], samples[1], samples[2]),
            raw(asShot = null, daylight = daylight),
        )
        val developed = CpuDevelop.renderLinear(input, Recipe.DEFAULT)
        assertThat(abs(developed[0] - developed[1]) / developed[1]).isLessThan(1e-3f)
        assertThat(abs(developed[2] - developed[1]) / developed[1]).isLessThan(1e-3f)
    }

    @Test
    fun `standard images map to display-referred input in their own colour space`() {
        val info = StandardColorInfo(ColorMath.rgbToXyzD65(ColorMath.DISPLAY_P3), SourceColorSpace.DISPLAY_P3)
        val input = PipelineInputMapper.map(image(1, 1, 65535, 0, 0), info)
        assertThat(input.kind).isEqualTo(InputKind.DISPLAY_REFERRED)
        assertThat(input.asShotMultipliers).isNull()
        val p3 = ColorSpaces.DISPLAY_P3.toXyz
        for (r in 0..2) for (c in 0..2) assertThat(input.cameraToXyz[r, c]).isWithin(1e-6).of(p3[r, c])
    }

    @Test
    fun `every EXIF orientation turns the pixels upright like the image core's geometry`() {
        val width = 3
        val height = 2
        // Each pixel stores its own stored index in all three channels.
        val stored = image(width, height, *IntArray(width * height * 3) { it / 3 })
        for (orientation in 1..8) {
            val input = PipelineInputMapper.map(stored, raw(), orientation)
            val (w, h) = Orientation.displayedSize(width, height, orientation)
            assertThat(input.width).isEqualTo(w)
            assertThat(input.height).isEqualTo(h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val rect = Orientation.toStored(IntRect(x, y, x + 1, y + 1), width, height, orientation)
                    val expected = (rect.top * width + rect.left) / 65535f
                    assertThat(input.linearRgb[(y * w + x) * 3]).isWithin(1e-9f).of(expected)
                }
            }
        }
    }

    @Test
    fun `an explicit orientation overrides the colour info's`() {
        val input = PipelineInputMapper.map(image(3, 2), raw(orientation = 6), orientation = 1)
        assertThat(input.width).isEqualTo(3)
        val turned = PipelineInputMapper.map(image(3, 2), raw(orientation = 6))
        assertThat(turned.width).isEqualTo(2)
    }

    private fun image(width: Int, height: Int, vararg values: Int): LinearImage {
        val data = ShortArray(width * height * 3) { if (it < values.size) values[it].toShort() else 0 }
        return LinearImage(width, height, data)
    }

    private fun raw(
        matrix: Matrix3? = ZV_E10_XYZ_TO_CAMERA.inverse(),
        asShot: WhiteBalance? = WhiteBalance(2.2, 1.0, 1.5),
        daylight: WhiteBalance? = WhiteBalance(2.0, 1.0, 1.4),
        orientation: Int = 1,
    ) = RawColorInfo(
        make = "SONY",
        model = "ZV-E10",
        normalizedMake = "Sony",
        normalizedModel = "ZV-E10",
        libRawVersion = "0.22.3",
        cameraToXyz = matrix,
        asShotWhiteBalance = asShot,
        daylightWhiteBalance = daylight,
        levels = RawLevels(listOf(512, 512, 512, 512), 16383, 0, listOf(16383, 16383, 16383, 16383), 14, true),
        baselineExposureEv = null,
        orientation = orientation,
        sensorWidth = 6048,
        sensorHeight = 4024,
        activeArea = PixelRect(0, 0, 6024, 4024),
        defaultCrop = null,
        cfa = CfaKind.BAYER,
        cfaPattern = "RGGB",
        isDng = false,
        iso = 100,
    )

    private fun Matrix3.toMat3() = dev.hfps.reveal.pipeline.cpu.Mat3(*values.toDoubleArray())

    private companion object {
        const val HALF = 30000.0
        const val D65_X = 0.3127 / 0.3290
        const val D65_Z = (1.0 - 0.3127 - 0.3290) / 0.3290

        /** Adobe ColorMatrix2 (XYZ D65 to camera) of the Sony ZV-E10, as in LibRaw's `adobe_coeff` table. */
        val ZV_E10_XYZ_TO_CAMERA = Matrix3(
            0.6355, -0.2067, -0.0490,
            -0.3653, 1.1542, 0.2400,
            -0.0406, 0.1258, 0.5506,
        )
    }
}
