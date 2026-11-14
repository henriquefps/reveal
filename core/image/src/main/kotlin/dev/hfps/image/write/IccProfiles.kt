// ICC.1:2022 (v4.4) byte layout: offsets, signatures and fixed-point encodings are the specification's.
@file:Suppress("MagicNumber")

package dev.hfps.image.write

import dev.hfps.image.color.ColorMath
import dev.hfps.image.color.Primaries
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import kotlin.math.roundToInt

/** The RGB spaces an export is written in. */
enum class OutputProfile(val description: String, val primaries: Primaries) {
    SRGB("sRGB", ColorMath.SRGB),
    DISPLAY_P3("Display P3", ColorMath.DISPLAY_P3),
}

/**
 * Display ICC profiles (version 4.4, matrix/TRC) for the export's output spaces (docs/adr/0009 "Colour management"):
 * PNG `iCCP`, TIFF tag 34675 and the HEIF/AVIF `colr` box embed them (JPEG gets the platform encoder's own).
 *
 * The profile is the standard description of an RGB space with the sRGB transfer function: colorants `rXYZ gXYZ bXYZ`
 * = the space's RGB to XYZ matrix (D65) adapted to the PCS white D50 by Bradford (`chad` records that adaptation),
 * `wtpt` = D50 (v4 display profiles), the three TRCs one `para` type 3 curve (`g 2.4, a 1/1.055, b 0.055/1.055,
 * c 1/12.92, d 0.04045`), `desc` and `cprt` as `mluc`, and the MD5 profile id. Pure Kotlin, JVM-tested; read back by
 * exiftool and by the platform decoders (the P3 round trip).
 */
object IccProfiles {
    /** The ICC PCS illuminant (D50) as XYZ, exactly the header's s15Fixed16 values. */
    private val PCS_WHITE = Triple(0.9642, 1.0, 0.8249)

    private val cache = HashMap<OutputProfile, ByteArray>()

    /** The profile bytes of [profile] (built once). */
    @Synchronized
    fun of(profile: OutputProfile): ByteArray = cache.getOrPut(profile) { build(profile) }.copyOf()

    /** The colorant matrix (RGB to PCS XYZ D50, row-major) of [profile]. */
    fun colorants(profile: OutputProfile): DoubleArray {
        val d65 = ColorMath.rgbToXyzD65(profile.primaries)
        val adapted = chad() * d65
        return DoubleArray(9) { adapted[it / 3, it % 3] }
    }

    private fun chad() = ColorMath.adaptation(ColorMath.xyz(ColorMath.D65), PCS_WHITE)

    private fun build(profile: OutputProfile): ByteArray {
        val m = colorants(profile)
        val chad = chad()
        val tags = listOf(
            "desc" to mluc(profile.description),
            "cprt" to mluc("No copyright, use freely"),
            "wtpt" to xyz(PCS_WHITE.first, PCS_WHITE.second, PCS_WHITE.third),
            "chad" to sf32(DoubleArray(9) { chad[it / 3, it % 3] }),
            "rXYZ" to xyz(m[0], m[3], m[6]),
            "gXYZ" to xyz(m[1], m[4], m[7]),
            "bXYZ" to xyz(m[2], m[5], m[8]),
            "rTRC" to SRGB_CURVE,
            "gTRC" to SRGB_CURVE,
            "bTRC" to SRGB_CURVE,
        )
        // Tag data after the table, each 4-byte aligned; identical data (the three TRCs) shared.
        val tableSize = 4 + tags.size * 12
        val data = ByteArrayOutputStream()
        val placed = ArrayList<Pair<ByteArray, Int>>()
        val entries = tags.map { (sig, bytes) ->
            val offset = placed.firstOrNull { it.first.contentEquals(bytes) }?.second ?: run {
                val at = HEADER + tableSize + data.size()
                data.write(bytes)
                while (data.size() % 4 != 0) data.write(0)
                placed += bytes to at
                at
            }
            Triple(sig, offset, bytes.size)
        }
        val total = HEADER + tableSize + data.size()
        val out = ByteBuffer.allocate(total)
        out.putInt(total)
        out.putInt(0) // preferred CMM: none
        out.putInt(0x04400000) // version 4.4
        out.put("mntr".toByteArray())
        out.put("RGB ".toByteArray())
        out.put("XYZ ".toByteArray())
        for (v in intArrayOf(2026, 1, 1, 0, 0, 0)) out.putShort(v.toShort())
        out.put("acsp".toByteArray())
        out.putInt(0) // platform
        out.putInt(0) // flags
        out.putInt(0) // manufacturer
        out.putInt(0) // model
        out.putLong(0) // attributes
        out.putInt(0) // perceptual
        out.putInt(s15(PCS_WHITE.first))
        out.putInt(s15(PCS_WHITE.second))
        out.putInt(s15(PCS_WHITE.third))
        out.putInt(0) // creator
        out.position(HEADER)
        out.putInt(entries.size)
        for ((sig, offset, size) in entries) {
            out.put(sig.toByteArray())
            out.putInt(offset)
            out.putInt(size)
        }
        out.put(data.toByteArray())
        val bytes = out.array()
        // Profile ID: MD5 of the profile with the flags, rendering intent and id fields zeroed (they already are).
        val id = MessageDigest.getInstance("MD5").digest(bytes)
        System.arraycopy(id, 0, bytes, ID_OFFSET, id.size)
        return bytes
    }

    private fun s15(v: Double): Int = (v * 65536.0).roundToInt()

    private fun xyz(x: Double, y: Double, z: Double): ByteArray =
        ByteBuffer.allocate(20).put("XYZ ".toByteArray()).putInt(0).putInt(s15(x)).putInt(s15(y)).putInt(s15(z))
            .array()

    private fun sf32(values: DoubleArray): ByteArray {
        val b = ByteBuffer.allocate(8 + values.size * 4).put("sf32".toByteArray()).putInt(0)
        values.forEach { b.putInt(s15(it)) }
        return b.array()
    }

    private fun mluc(text: String): ByteArray {
        val utf16 = text.toByteArray(Charsets.UTF_16BE)
        return ByteBuffer.allocate(28 + utf16.size)
            .put("mluc".toByteArray()).putInt(0)
            .putInt(1).putInt(12)
            .put("enUS".toByteArray()).putInt(utf16.size).putInt(28)
            .put(utf16)
            .array()
    }

    private val SRGB_CURVE: ByteArray = ByteBuffer.allocate(32).put("para".toByteArray()).putInt(0)
        .putShort(3).putShort(0)
        .putInt(s15(2.4)).putInt(s15(1 / 1.055)).putInt(s15(0.055 / 1.055)).putInt(s15(1 / 12.92)).putInt(s15(0.04045))
        .array()

    private const val HEADER = 128
    private const val ID_OFFSET = 84
}
