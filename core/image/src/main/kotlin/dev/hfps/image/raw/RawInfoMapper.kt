// Colour constants and fixed array layouts: named inline, not as constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.raw

import dev.hfps.image.color.CfaKind
import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.PixelRect
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.color.RawLevels
import dev.hfps.image.color.SonyLensCorrection
import dev.hfps.image.color.WhiteBalance
import dev.hfps.image.raw.RawNativeLayout as L

/** Turns the native snapshot into [RawColorInfo]. Pure (no Android, no JNI): unit tested with hand-made arrays. */
internal object RawInfoMapper {
    private const val CHANNELS = 4
    private const val BAYER_FILTERS_MIN = 1000L
    private const val XTRANS_FILTERS = 9L
    private const val MONO_COLORS = 1

    fun map(info: RawNativeInfo, lensCorrection: SonyLensCorrection? = null): RawColorInfo {
        val n = info.numbers
        require(n.size >= L.COUNT) { "Native info has ${n.size} numbers, expected ${L.COUNT}" }
        val rgbCam = List(12) { n[L.RGB_CAM + it] }
        val preMul = List(CHANNELS) { n[L.PRE_MUL0 + it] }
        val camMul = List(CHANNELS) { n[L.CAM_MUL0 + it] }
        val filters = n[L.FILTERS].toLong()
        val colors = n[L.COLORS].toInt()
        val cfa = cfaKind(filters, colors)
        val baseline = n[L.BASELINE_EXPOSURE].takeIf { !it.isNaN() }
        val cropWidth = n[L.CROP_WIDTH].toInt()
        val iso = n[L.ISO].toInt().takeIf { it > 0 }
        return RawColorInfo(
            make = info.strings[L.STR_MAKE]?.takeIf { it.isNotBlank() },
            model = info.strings[L.STR_MODEL]?.takeIf { it.isNotBlank() },
            normalizedMake = info.strings[L.STR_NORMALIZED_MAKE]?.takeIf { it.isNotBlank() },
            normalizedModel = info.strings[L.STR_NORMALIZED_MODEL]?.takeIf { it.isNotBlank() },
            libRawVersion = info.strings[L.STR_LIBRAW].orEmpty(),
            cameraToXyz = if (colors >= 3) ColorMath.cameraToXyzD65(rgbCam, preMul) else null,
            asShotWhiteBalance = asShot(camMul),
            daylightWhiteBalance = ColorMath.normalizeToGreen(preMul[0], preMul[1], preMul[2])
                ?.takeIf { preMul.take(3).any { it != 1.0 } },
            levels = levels(n),
            baselineExposureEv = baseline,
            orientation = orientationFromDcraw(n[L.FLIP].toInt()),
            sensorWidth = n[L.RAW_WIDTH].toInt(),
            sensorHeight = n[L.RAW_HEIGHT].toInt(),
            activeArea = PixelRect(
                n[L.LEFT_MARGIN].toInt(),
                n[L.TOP_MARGIN].toInt(),
                n[L.WIDTH].toInt(),
                n[L.HEIGHT].toInt(),
            ),
            defaultCrop = if (cropWidth > 0) {
                PixelRect(n[L.CROP_LEFT].toInt(), n[L.CROP_TOP].toInt(), cropWidth, n[L.CROP_HEIGHT].toInt())
            } else {
                null
            },
            cfa = cfa,
            cfaPattern = if (cfa == CfaKind.BAYER) bayerPattern(filters) else null,
            isDng = n[L.DNG_VERSION] > 0,
            iso = iso,
            lensCorrection = lensCorrection,
        )
    }

    /** As-shot multipliers from LibRaw's `cam_mul` (R, G, B, G2), normalised to G = 1; null when absent (0 or -1). */
    fun asShot(camMul: List<Double>): WhiteBalance? = ColorMath.normalizeToGreen(camMul[0], camMul[1], camMul[2])

    fun levels(n: DoubleArray): RawLevels {
        val common = n[L.BLACK].toInt()
        val black = List(CHANNELS) { common + n[L.CBLACK0 + it].toInt() }
        val white = n[L.MAXIMUM].toInt()
        val saturation = List(CHANNELS) { c ->
            val linearMax = n[L.LINEAR_MAX0 + c].toInt()
            if (linearMax in 1..white) linearMax else white
        }
        return RawLevels(black, white, n[L.DATA_MAXIMUM].toInt(), saturation, n[L.RAW_BPS].toInt())
    }

    fun cfaKind(filters: Long, colors: Int): CfaKind = when {
        filters == XTRANS_FILTERS -> CfaKind.XTRANS
        colors == MONO_COLORS -> CfaKind.MONOCHROME
        filters >= BAYER_FILTERS_MIN -> CfaKind.BAYER
        else -> CfaKind.OTHER
    }

    /** The 2x2 CFA at the top left (`RGGB`, `GBRG`, ...), read like LibRaw's `FC(row, col)` macro. */
    fun bayerPattern(filters: Long): String = buildString {
        for (row in 0..1) {
            for (col in 0..1) {
                val shift = (((row shl 1) and 14) or (col and 1)) shl 1
                append("RGBG"[((filters shr shift) and 3).toInt()])
            }
        }
    }

    /** dcraw/LibRaw `flip` (bit 1 = mirror columns, 2 = mirror rows, 4 = transpose) to EXIF orientation 1..8. */
    fun orientationFromDcraw(flip: Int): Int = when (flip and 7) {
        1 -> 2
        2 -> 4
        3 -> 3
        4 -> 5
        5 -> 8
        6 -> 6
        7 -> 7
        else -> 1
    }
}
