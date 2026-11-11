package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.color.CfaKind
import dev.hfps.image.color.RawLevels
import dev.hfps.image.raw.RawInfoMapper
import dev.hfps.image.raw.RawNativeInfo
import dev.hfps.image.raw.RawNativeLayout as L
import org.junit.Test

class RawInfoMapperTest {

    /** A ZV-E10-like snapshot (sizes and levels as measured on a public sample; matrices are a generic example). */
    private fun snapshot(edit: (DoubleArray) -> Unit = {}): RawNativeInfo {
        val n = DoubleArray(L.COUNT)
        n[L.RAW_WIDTH] = 6048.0
        n[L.RAW_HEIGHT] = 4024.0
        n[L.WIDTH] = 6024.0
        n[L.HEIGHT] = 4024.0
        n[L.COLORS] = 3.0
        n[L.FILTERS] = 0x94949494.toDouble()
        n[L.CROP_LEFT] = -1.0
        n[L.CROP_TOP] = -1.0
        n[L.CROP_WIDTH] = -1.0
        n[L.CROP_HEIGHT] = -1.0
        n[L.BLACK] = 512.0
        n[L.MAXIMUM] = 16383.0
        n[L.LINEAR_MAX0] = 15360.0
        n[L.LINEAR_MAX0 + 1] = 15360.0
        n[L.LINEAR_MAX0 + 2] = 15360.0
        n[L.LINEAR_MAX0 + 3] = 15360.0
        n[L.CAM_MUL0] = 3.125
        n[L.CAM_MUL0 + 1] = 1.0
        n[L.CAM_MUL0 + 2] = 1.7
        n[L.PRE_MUL0] = 3.1
        n[L.PRE_MUL0 + 1] = 1.0
        n[L.PRE_MUL0 + 2] = 1.55
        n[L.PRE_MUL0 + 3] = 1.0
        n[L.BASELINE_EXPOSURE] = Double.NaN
        n[L.RAW_BPS] = 14.0
        n[L.ISO] = 400.0
        n[L.LEVELS_FINAL] = 1.0
        edit(n)
        return RawNativeInfo(n, arrayOf("SONY", "ZV-E10", "Sony", "ZV-E10", "", "0.22.3-Release"))
    }

    @Test
    fun `maps sizes, levels and white balance`() {
        val info = RawInfoMapper.map(snapshot())
        assertThat(info.make).isEqualTo("SONY")
        assertThat(info.model).isEqualTo("ZV-E10")
        assertThat(info.libRawVersion).isEqualTo("0.22.3-Release")
        assertThat(info.activeArea.width).isEqualTo(6024)
        assertThat(info.sensorWidth).isEqualTo(6048)
        assertThat(info.defaultCrop).isNull()
        assertThat(info.cfa).isEqualTo(CfaKind.BAYER)
        assertThat(info.cfaPattern).isEqualTo("RGGB")
        assertThat(info.asShotWhiteBalance?.red).isEqualTo(3.125)
        assertThat(info.asShotWhiteBalance?.blue).isWithin(1e-9).of(1.7)
        assertThat(info.daylightWhiteBalance?.red).isWithin(1e-9).of(3.1)
        assertThat(info.baselineExposureEv).isNull()
        assertThat(info.iso).isEqualTo(400)
        assertThat(info.isDng).isFalse()
        assertThat(info.levels.isFinal).isTrue()
        assertThat(info.levels.black).containsExactly(512, 512, 512, 512)
    }

    @Test
    fun `black is common plus per channel`() {
        val levels = RawInfoMapper.levels(
            snapshot {
                it[L.BLACK] = 100.0
                it[L.CBLACK0] = 0.0
                it[L.CBLACK0 + 1] = 2.0
                it[L.CBLACK0 + 2] = 4.0
                it[L.CBLACK0 + 3] = 6.0
            }.numbers,
        )
        assertThat(levels.black).containsExactly(100, 102, 104, 106).inOrder()
    }

    @Test
    fun `saturation falls back to the white level when linear_max is missing or above it`() {
        val levels = RawInfoMapper.levels(
            snapshot {
                it[L.LINEAR_MAX0] = 0.0
                it[L.LINEAR_MAX0 + 1] = 20000.0
                it[L.LINEAR_MAX0 + 2] = 9000.0
            }.numbers,
        )
        assertThat(levels.saturation).containsExactly(16383, 16383, 9000, 15360).inOrder()
    }

    @Test
    fun `saturation in the output encoding`() {
        val levels = RawLevels(listOf(512, 512, 512, 512), 16383, 0, listOf(15360, 15360, 16383, 512), 14, true)
        val expected = ((15360 - 512).toDouble() / (16383 - 512) * 65535).toInt()
        assertThat(levels.saturationLinear16[0]).isIn(expected - 1..expected + 1)
        assertThat(levels.saturationLinear16[2]).isEqualTo(65535)
        assertThat(levels.saturationLinear16[3]).isEqualTo(0)
    }

    @Test
    fun `missing as-shot white balance is null`() {
        val info = RawInfoMapper.map(
            snapshot {
                it[L.CAM_MUL0] = 0.0
                it[L.CAM_MUL0 + 1] = 0.0
                it[L.CAM_MUL0 + 2] = 0.0
            },
        )
        assertThat(info.asShotWhiteBalance).isNull()
        val unset = RawInfoMapper.map(snapshot { it[L.CAM_MUL0] = -1.0 })
        assertThat(unset.asShotWhiteBalance).isNull()
    }

    @Test
    fun `crop, baseline exposure, DNG flag and X-Trans`() {
        val info = RawInfoMapper.map(
            snapshot {
                it[L.CROP_LEFT] = 12.0
                it[L.CROP_TOP] = 324.0
                it[L.CROP_WIDTH] = 6000.0
                it[L.CROP_HEIGHT] = 3376.0
                it[L.BASELINE_EXPOSURE] = 0.75
                it[L.DNG_VERSION] = 16777216.0
                it[L.FILTERS] = 9.0
            },
        )
        assertThat(info.defaultCrop?.top).isEqualTo(324)
        assertThat(info.defaultCrop?.height).isEqualTo(3376)
        assertThat(info.baselineExposureEv).isEqualTo(0.75)
        assertThat(info.isDng).isTrue()
        assertThat(info.cfa).isEqualTo(CfaKind.XTRANS)
        assertThat(info.cfaPattern).isNull()
    }

    @Test
    fun `CFA patterns`() {
        assertThat(RawInfoMapper.bayerPattern(0x94949494L)).isEqualTo("RGGB")
        assertThat(RawInfoMapper.bayerPattern(0x16161616L)).isEqualTo("BGGR")
        assertThat(RawInfoMapper.bayerPattern(0x61616161L)).isEqualTo("GRBG")
        assertThat(RawInfoMapper.bayerPattern(0x49494949L)).isEqualTo("GBRG")
        assertThat(RawInfoMapper.cfaKind(0, 1)).isEqualTo(CfaKind.MONOCHROME)
        assertThat(RawInfoMapper.cfaKind(0, 3)).isEqualTo(CfaKind.OTHER)
    }

    @Test
    fun `dcraw flip maps to EXIF orientation`() {
        assertThat((0..7).map(RawInfoMapper::orientationFromDcraw)).containsExactly(1, 2, 4, 3, 5, 8, 6, 7).inOrder()
        // A typical portrait ARW: EXIF 6 is dcraw 6, EXIF 8 is dcraw 5.
        assertThat(RawInfoMapper.orientationFromDcraw(6)).isEqualTo(6)
        assertThat(RawInfoMapper.orientationFromDcraw(5)).isEqualTo(8)
    }

    @Test
    fun `no matrix when LibRaw has none`() {
        val info = RawInfoMapper.map(snapshot())
        assertThat(info.cameraToXyz).isNull()
        assertThat(info.xyzToCamera).isNull()
        assertThat(info.toXyz.values).hasSize(9)
    }
}
