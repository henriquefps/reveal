@file:Suppress("MagicNumber")

package dev.hfps.reveal.ml.inpaint

import dev.hfps.reveal.pipeline.cpu.Half
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Inpainting fills on disk (docs/adr/0024 "Cache"), so a photo reopened after the app restarts paints its AI Remove
 * spots without compiling the model (≈ 4 s on the S24's GPU, ≈ 7 s a fill on a CPU). One file a fill, named by the
 * SHA-256 of its key (no photo name or fingerprint on disk): the hole's pixels only, rounded to float16 ([Half]) and
 * deflated. A fresh fill is rounded the same way ([quantised]) before it is used, so a hit and a miss give identical
 * pixels. Least recently used entries go first ([trim]); [cap] is called after each write (the app's cache budget).
 */
class FillDiskCache(
    private val dir: File,
    private val cap: () -> Long = { Long.MAX_VALUE },
    private val onWrite: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One stored fill: its file [name], size and last use (for the budget). */
    data class Entry(val name: String, val bytes: Long, val lastUsed: Long)

    private val lock = Any()

    /** The hole's values of [key] (3 floats a hole pixel, in the crop's order), or null. */
    fun get(key: String, holePixels: Int): FloatArray? = synchronized(lock) {
        val file = File(dir, name(key))
        if (!file.isFile) return null
        try {
            val values = decode(file.readBytes())
            if (values.size != holePixels * 3) return null
            file.setLastModified(clock())
            values
        } catch (@Suppress("SwallowedException") e: IOException) {
            file.delete() // a damaged entry is a miss: the fill is made again
            null
        }
    }

    /** Stores [values] (already [quantised]) under [key]. */
    fun put(key: String, values: FloatArray) {
        synchronized(lock) {
            dir.mkdirs()
            val file = File(dir, name(key))
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(encode(values))
            if (!tmp.renameTo(file)) tmp.delete()
            file.setLastModified(clock())
            trim(cap())
        }
        onWrite()
    }

    fun entries(): List<Entry> = synchronized(lock) {
        dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().map { Entry(it.name, it.length(), it.lastModified()) }
    }

    fun evict(names: Collection<String>) = synchronized(lock) {
        names.forEach { File(dir, it).delete() }
    }

    fun clear() = synchronized(lock) {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    fun trim(maxBytes: Long) = synchronized(lock) {
        val all = entries().sortedBy { it.lastUsed }
        var total = all.sumOf { it.bytes }
        for (e in all) {
            if (total <= maxBytes) break
            File(dir, e.name).delete()
            total -= e.bytes
        }
    }

    companion object {
        const val SUFFIX = ".fill"
        private const val MAGIC = 0x5256464c // "RVFL"
        private const val FORMAT = 1
        private const val MAX_VALUES = 3 * 4096 * 4096

        fun name(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + SUFFIX

        /** [values] rounded as the cache stores them. */
        fun quantised(values: FloatArray): FloatArray = FloatArray(values.size) { Half.round(values[it]) }

        fun encode(values: FloatArray): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(FORMAT)
                d.writeInt(values.size)
                val deflater = DeflaterOutputStream(d)
                val body = DataOutputStream(deflater)
                for (v in values) body.writeInt(Half.round(v).toRawBits())
                body.flush()
                deflater.finish()
            }
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): FloatArray = DataInputStream(bytes.inputStream()).use { d ->
            if (d.readInt() != MAGIC || d.readInt() != FORMAT) throw IOException("not a fill")
            val n = d.readInt()
            if (n !in 0..MAX_VALUES) throw IOException("bad size")
            DataInputStream(InflaterInputStream(d)).use { body -> FloatArray(n) { Float.fromBits(body.readInt()) } }
        }
    }
}
