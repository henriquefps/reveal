@file:Suppress("MagicNumber", "ComplexCondition")

package dev.hfps.reveal.pipeline.heal

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Remove fills kept beyond the process (docs/adr/0023 "FIX/H"): by a key that does not depend on a decode
 * ([RemoveRequest.storeKey]: the photo's file fingerprint, the crop on the original, the shape and the method), so the
 * library's thumbnails and the viewer show the fill the editor made (an AI fill included) without decoding the
 * original. Calls come from a fill worker thread, never the main or GL thread.
 */
interface RemoveFillStore {
    fun get(key: String): RemoveFill?

    fun put(key: String, fill: RemoveFill)
}

/** [RemoveFill] as bytes for a [RemoveFillStore]: exact (float bits), deflated. */
object RemoveFillCodec {
    private const val MAGIC = 0x5256484c // "RVHL"
    private const val FORMAT = 1
    private const val MATRIX = 9
    private const val MAX_SIDE = 1 shl 14

    fun encode(fill: RemoveFill): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(FORMAT)
            val s = fill.spec
            for (v in intArrayOf(s.originalWidth, s.originalHeight, s.factor, s.x0, s.y0, s.x1, s.y1)) d.writeInt(v)
            d.writeUTF(fill.fellBack.orEmpty())
            val deflater = DeflaterOutputStream(d)
            val body = DataOutputStream(deflater)
            fill.colour.toLinear.forEach(body::writeFloat)
            fill.colour.fromLinear.forEach(body::writeFloat)
            body.writeFloat(fill.colour.gain)
            fill.output.rgb.forEach(body::writeFloat)
            val offsets = fill.output.offsets
            body.writeBoolean(offsets != null)
            offsets?.forEach(body::writeFloat)
            body.flush()
            deflater.finish()
        }
        return out.toByteArray()
    }

    /** The fill of [bytes]; [IOException] when they are not one (a damaged entry is a miss). */
    fun decode(bytes: ByteArray): RemoveFill = DataInputStream(bytes.inputStream()).use { d ->
        if (d.readInt() != MAGIC || d.readInt() != FORMAT) throw IOException("not a heal fill")
        val v = IntArray(7) { d.readInt() }
        if (v.any { it < 0 || it > MAX_SIDE * MAX_SIDE } || v[2] < 1 || v[5] <= v[3] || v[6] <= v[4]) {
            throw IOException("bad crop")
        }
        val spec = RemoveSpec(v[0], v[1], v[2], v[3], v[4], v[5], v[6])
        if (spec.width > MAX_SIDE || spec.height > MAX_SIDE) throw IOException("bad crop")
        val fellBack = d.readUTF().ifEmpty { null }
        DataInputStream(InflaterInputStream(d)).use { body ->
            val toLinear = FloatArray(MATRIX) { body.readFloat() }
            val fromLinear = FloatArray(MATRIX) { body.readFloat() }
            val gain = body.readFloat()
            val n = spec.width * spec.height
            val rgb = FloatArray(n * 3) { body.readFloat() }
            val offsets = if (body.readBoolean()) FloatArray(n * 2) { body.readFloat() } else null
            RemoveFill(spec, RemoveColour(toLinear, fromLinear, gain), RemoveOutput(rgb, offsets), fellBack)
        }
    }
}
