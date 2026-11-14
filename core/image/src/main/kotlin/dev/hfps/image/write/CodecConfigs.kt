// H.265 (ITU-T H.265 7.3), ISO/IEC 14496-15 (hvcC), AV1 bitstream 5.5 and AV1-ISOBMFF (av1C): field widths are the
// specifications'.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import java.io.ByteArrayOutputStream

/** Reads bits MSB first; `ue(v)` Exp-Golomb for H.265. */
internal class BitReader(private val data: ByteArray, private var pos: Int = 0) {
    fun bit(): Int {
        val b = (data[pos ushr 3].toInt() ushr (7 - (pos and 7))) and 1
        pos++
        return b
    }

    fun bits(n: Int): Long {
        var v = 0L
        repeat(n) { v = (v shl 1) or bit().toLong() }
        return v
    }

    fun int(n: Int): Int = bits(n).toInt()

    fun skip(n: Int) {
        pos += n
    }

    fun ue(): Int {
        var zeros = 0
        while (bit() == 0) zeros++
        return ((1L shl zeros) - 1 + bits(zeros)).toInt()
    }
}

/** NAL units of an H.265 Annex B stream and the hvcC record (docs/adr/0009 "HEIF and AVIF"). */
object HevcConfig {
    const val NAL_VPS = 32
    const val NAL_SPS = 33
    const val NAL_PPS = 34

    /** The NAL units (without start codes) of an Annex B byte stream. */
    fun nalUnits(annexB: ByteArray): List<ByteArray> {
        val starts = ArrayList<Pair<Int, Int>>() // (start code position, payload start)
        var i = 0
        while (i + 3 <= annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0) {
                if (annexB[i + 2].toInt() == 1) {
                    starts += i to i + 3
                    i += 3
                    continue
                }
                if (i + 4 <= annexB.size && annexB[i + 2].toInt() == 0 && annexB[i + 3].toInt() == 1) {
                    starts += i to i + 4
                    i += 4
                    continue
                }
            }
            i++
        }
        return starts.mapIndexed { k, (_, from) ->
            var to = if (k + 1 < starts.size) starts[k + 1].first else annexB.size
            // A zero byte before the next start code belongs to it (trailing_zero_8bits).
            while (to > from && annexB[to - 1].toInt() == 0 && k + 1 < starts.size) to--
            annexB.copyOfRange(from, to)
        }.filter { it.size >= 2 }
    }

    fun type(nal: ByteArray): Int = (nal[0].toInt() ushr 1) and 0x3F

    /** An Annex B access unit as an ISOBMFF sample: 4-byte length + NAL, parameter sets dropped (they are in hvcC). */
    fun sample(annexB: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (nal in nalUnits(annexB)) {
            if (type(nal) in NAL_VPS..NAL_PPS) continue
            out.writeInt(nal.size)
            out.write(nal)
        }
        return out.toByteArray()
    }

    /** The RBSP of a NAL unit (emulation prevention bytes removed). */
    fun rbsp(nal: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(nal.size)
        var zeros = 0
        for (b in nal) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v == 3) {
                zeros = 0
                continue
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    /** What hvcC needs from the SPS. */
    data class SpsInfo(
        val profileSpace: Int,
        val tier: Int,
        val profileIdc: Int,
        val compatibility: Long,
        val constraints: Long,
        val levelIdc: Int,
        val chromaFormat: Int,
        val bitDepthLuma: Int,
        val bitDepthChroma: Int,
        val width: Int,
        val height: Int,
    )

    fun parseSps(sps: ByteArray): SpsInfo {
        val r = BitReader(rbsp(sps), 16) // after the 2-byte NAL header
        r.skip(4) // sps_video_parameter_set_id
        val maxSubLayersMinus1 = r.int(3)
        r.skip(1) // temporal_id_nesting
        val space = r.int(2)
        val tier = r.int(1)
        val profile = r.int(5)
        val compat = r.bits(32)
        val constraints = r.bits(48)
        val level = r.int(8)
        val subProfile = IntArray(maxSubLayersMinus1)
        val subLevel = IntArray(maxSubLayersMinus1)
        for (i in 0 until maxSubLayersMinus1) {
            subProfile[i] = r.int(1)
            subLevel[i] = r.int(1)
        }
        if (maxSubLayersMinus1 > 0) for (i in maxSubLayersMinus1 until 8) r.skip(2)
        for (i in 0 until maxSubLayersMinus1) {
            if (subProfile[i] == 1) r.skip(88)
            if (subLevel[i] == 1) r.skip(8)
        }
        r.ue() // sps_seq_parameter_set_id
        val chroma = r.ue()
        if (chroma == 3) r.skip(1)
        val width = r.ue()
        val height = r.ue()
        if (r.bit() == 1) repeat(4) { r.ue() } // conformance window
        val luma = r.ue() + 8
        val chromaDepth = r.ue() + 8
        return SpsInfo(space, tier, profile, compat, constraints, level, chroma, luma, chromaDepth, width, height)
    }

    /** The `hvcC` box payload (ISO/IEC 14496-15 8.3.3) from the encoder's parameter sets (Annex B, any order). */
    fun hvcC(parameterSets: ByteArray): ByteArray {
        val nals = nalUnits(parameterSets)
        val vps = nals.filter { type(it) == NAL_VPS }
        val sps = nals.filter { type(it) == NAL_SPS }
        val pps = nals.filter { type(it) == NAL_PPS }
        require(vps.isNotEmpty() && sps.isNotEmpty() && pps.isNotEmpty()) { "parameter sets incomplete" }
        val info = parseSps(sps.first())
        val out = ByteArrayOutputStream()
        out.write(1)
        out.write((info.profileSpace shl 6) or (info.tier shl 5) or info.profileIdc)
        out.writeInt(info.compatibility.toInt())
        for (shift in 40 downTo 0 step 8) out.write((info.constraints ushr shift).toInt())
        out.write(info.levelIdc)
        out.writeShort(0xF000) // min_spatial_segmentation_idc 0
        out.write(0xFC) // parallelismType 0
        out.write(0xFC or info.chromaFormat)
        out.write(0xF8 or (info.bitDepthLuma - 8))
        out.write(0xF8 or (info.bitDepthChroma - 8))
        out.writeShort(0) // avgFrameRate
        // constantFrameRate 0, numTemporalLayers 1, temporalIdNested 1, lengthSizeMinusOne 3.
        out.write((1 shl 3) or (1 shl 2) or 3)
        out.write(3)
        for ((type, list) in listOf(NAL_VPS to vps, NAL_SPS to sps, NAL_PPS to pps)) {
            out.write(0x80 or type) // array_completeness 1
            out.writeShort(list.size)
            for (nal in list) {
                out.writeShort(nal.size)
                out.write(nal)
            }
        }
        return out.toByteArray()
    }
}

/** AV1 OBUs and the av1C record (AV1 Codec ISO Media File Format Binding, AVIF). */
object Av1Config {
    const val OBU_SEQUENCE_HEADER = 1
    const val OBU_TEMPORAL_DELIMITER = 2

    /** One OBU: its type and its whole bytes (header included). */
    class Obu(val type: Int, val bytes: ByteArray, val payloadStart: Int)

    /** The OBUs of a low-overhead bitstream (every OBU has `obu_has_size_field`). */
    fun obus(data: ByteArray): List<Obu> {
        val out = ArrayList<Obu>()
        var i = 0
        while (i < data.size) {
            val header = data[i].toInt() and 0xFF
            val type = (header ushr 3) and 0xF
            val extension = (header ushr 2) and 1
            val hasSize = (header ushr 1) and 1
            require(hasSize == 1) { "OBU without a size field" }
            var p = i + 1 + extension
            var size = 0L
            var shift = 0
            while (true) {
                val b = data[p++].toInt() and 0xFF
                size = size or ((b and 0x7F).toLong() shl shift)
                shift += 7
                if (b and 0x80 == 0) break
            }
            val end = p + size.toInt()
            out += Obu(type, data.copyOfRange(i, end), p - i)
            i = end
        }
        return out
    }

    /** A temporal unit as an AVIF sample: the OBUs without temporal delimiters. */
    fun sample(temporalUnit: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (obu in obus(temporalUnit)) if (obu.type != OBU_TEMPORAL_DELIMITER) out.write(obu.bytes)
        return out.toByteArray()
    }

    /** What av1C needs from the sequence header. */
    data class SequenceInfo(
        val profile: Int,
        val level: Int,
        val tier: Int,
        val highBitDepth: Int,
        val twelveBit: Int,
        val monochrome: Int,
        val subsamplingX: Int,
        val subsamplingY: Int,
        val chromaSamplePosition: Int,
    ) {
        val bitDepth: Int get() = if (highBitDepth == 0) 8 else if (twelveBit == 1) 12 else 10
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun parseSequenceHeader(obu: Obu): SequenceInfo {
        val r = BitReader(obu.bytes, obu.payloadStart * 8)
        val profile = r.int(3)
        r.skip(1) // still_picture
        val reduced = r.int(1)
        var level = 0
        var tier = 0
        if (reduced == 1) {
            level = r.int(5)
        } else {
            val timing = r.int(1)
            var decoderModel = 0
            var bufferDelayLength = 0
            if (timing == 1) {
                r.skip(64) // num_units_in_display_tick, time_scale
                if (r.int(1) == 1) uvlc(r) // equal_picture_interval
                decoderModel = r.int(1)
                if (decoderModel == 1) {
                    bufferDelayLength = r.int(5) + 1
                    r.skip(32 + 5 + 5)
                }
            }
            val initialDelay = r.int(1)
            val points = r.int(5) + 1
            for (i in 0 until points) {
                r.skip(12) // operating_point_idc
                val l = r.int(5)
                var t = 0
                if (l > 7) t = r.int(1)
                if (i == 0) {
                    level = l
                    tier = t
                }
                if (decoderModel == 1 && r.int(1) == 1) r.skip(2 * bufferDelayLength + 1)
                if (initialDelay == 1 && r.int(1) == 1) r.skip(4)
            }
        }
        val widthBits = r.int(4) + 1
        val heightBits = r.int(4) + 1
        r.skip(widthBits + heightBits)
        if (reduced == 0 && r.int(1) == 1) r.skip(4 + 3) // frame ids
        r.skip(3) // 128x128, filter intra, intra edge
        var orderHint = 0
        if (reduced == 0) {
            r.skip(4) // interintra, masked, warped, dual filter
            orderHint = r.int(1)
            if (orderHint == 1) r.skip(2) // jnt_comp, ref_frame_mvs
            val chooseScreen = r.int(1)
            val forceScreen = if (chooseScreen == 1) 2 else r.int(1)
            if (forceScreen > 0 && r.int(1) == 0) r.skip(1) // seq_choose_integer_mv, seq_force_integer_mv
            if (orderHint == 1) r.skip(3)
        }
        r.skip(3) // superres, cdef, restoration
        val high = r.int(1)
        val twelve = if (profile == 2 && high == 1) r.int(1) else 0
        val mono = if (profile == 1) 0 else r.int(1)
        var cp = 2
        var tc = 2
        var mc = 2
        if (r.int(1) == 1) {
            cp = r.int(8)
            tc = r.int(8)
            mc = r.int(8)
        }
        var ssx = 1
        var ssy = 1
        var position = 0
        if (mono == 1) {
            r.skip(1)
        } else if (cp == 1 && tc == 13 && mc == 0) {
            ssx = 0
            ssy = 0
        } else {
            r.skip(1) // color_range
            when (profile) {
                0 -> {
                    ssx = 1
                    ssy = 1
                }
                1 -> {
                    ssx = 0
                    ssy = 0
                }
                else -> if (twelve == 1) {
                    ssx = r.int(1)
                    ssy = if (ssx == 1) r.int(1) else 0
                } else {
                    ssx = 1
                    ssy = 0
                }
            }
            if (ssx == 1 && ssy == 1) position = r.int(2)
        }
        return SequenceInfo(profile, level, tier, high, twelve, mono, ssx, ssy, position)
    }

    private fun uvlc(r: BitReader) {
        var zeros = 0
        while (r.bit() == 0) zeros++
        if (zeros < 32) r.skip(zeros)
    }

    /**
     * The `av1C` payload from the encoder's codec-specific data: already an av1C record (marker bit set, version 1)
     * is returned as is; otherwise the OBUs' sequence header makes one (with that header as the config OBU).
     */
    fun av1C(csd: ByteArray): ByteArray {
        if (csd.size >= 4 && (csd[0].toInt() and 0xFF) == 0x81) return csd
        val header = obus(csd).first { it.type == OBU_SEQUENCE_HEADER }
        return record(parseSequenceHeader(header), header.bytes)
    }

    /** The av1C record of [info] with [configObus]. */
    fun record(info: SequenceInfo, configObus: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0x81)
        out.write((info.profile shl 5) or info.level)
        out.write(
            (info.tier shl 7) or (info.highBitDepth shl 6) or (info.twelveBit shl 5) or (info.monochrome shl 4) or
                (info.subsamplingX shl 3) or (info.subsamplingY shl 2) or info.chromaSamplePosition,
        )
        out.write(0) // no initial_presentation_delay
        out.write(configObus)
        return out.toByteArray()
    }
}

internal fun ByteArrayOutputStream.writeShort(v: Int) {
    write(v ushr 8)
    write(v)
}
