package dev.hfps.reveal.ml.service

import dev.hfps.reveal.ml.Mask
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.roundToInt

/**
 * The masks' disk cache (docs/adr/0021 "M4/C", "Cache"): one file per key in [dir], an 8-bit soft mask, deflated
 * (`RVMK` format 1: magic, format, width, height, then width x height bytes). The key's SHA-256 names the file, so
 * no photo name or fingerprint appears on disk. Nothing in it is the only copy of anything: an entry that cannot be
 * read is a miss and is deleted.
 *
 * Bounded twice, like Reveal's other caches (ADR 0018 amendment FIX/W): after each write it trims itself to [cap]
 * (least recently used first; a read refreshes an entry's time), then calls [onWrite] so the app's cache budget can
 * trim all caches together (`:core:data`'s `MaskCacheArea` registers it with `CacheBudget`). Thread-safe.
 */
class MaskDiskCache(
    val dir: File,
    private val cap: () -> Long = { Long.MAX_VALUE },
    private val onWrite: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One entry as an LRU sees it. */
    data class Entry(val name: String, val bytes: Long, val lastUsed: Long)

    private val lock = Any()

    /** The mask stored under [key], or null. */
    @Suppress("SwallowedException") // a damaged entry is a miss by design: it is deleted and made again
    fun get(key: String): Mask? = synchronized(lock) {
        val file = file(key)
        if (!file.isFile) return null
        try {
            val mask = decode(file.readBytes())
            file.setLastModified(clock())
            mask
        } catch (e: IOException) {
            file.delete()
            null
        } catch (e: IllegalArgumentException) {
            file.delete()
            null
        }
    }

    /** Stores [mask] (quantised to 8 bits) under [key]. */
    fun put(key: String, mask: Mask) {
        synchronized(lock) {
            dir.mkdirs()
            val file = file(key)
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(encode(mask))
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return
            }
            file.setLastModified(clock())
            trim(cap())
        }
        onWrite()
    }

    /** Every entry with its size and last use. */
    fun entries(): List<Entry> = synchronized(lock) {
        dir.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty()
            .map { Entry(it.name, it.length(), it.lastModified()) }
    }

    /** Deletes the entries named [names] (as [entries] lists them). */
    fun evict(names: Collection<String>) = synchronized(lock) {
        for (name in names) File(dir, name).takeIf { it.parentFile == dir }?.delete()
    }

    /** Deletes everything. */
    fun clear() = synchronized(lock) {
        dir.listFiles().orEmpty().forEach { it.delete() }
    }

    /** Deletes the least recently used entries until the cache holds at most [maxBytes]. */
    fun trim(maxBytes: Long) = synchronized(lock) {
        val all = entries().sortedBy { it.lastUsed }
        var total = all.sumOf { it.bytes }
        for (e in all) {
            if (total <= maxBytes) break
            File(dir, e.name).delete()
            total -= e.bytes
        }
    }

    private fun file(key: String): File = File(dir, name(key))

    companion object {
        const val SUFFIX = ".mask"
        private const val MAGIC = 0x52564d4b // "RVMK"
        private const val FORMAT = 1
        private const val MAX_SIDE = 8192

        /** The file name of [key]: its SHA-256 in hex. */
        fun name(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + SUFFIX

        /** [mask] quantised to 8 bits, as a cache hit would return it (fresh and cached results are identical). */
        fun quantised(mask: Mask): Mask =
            Mask(mask.width, mask.height, FloatArray(mask.values.size) { q8(mask.values[it]) / Q })

        private fun q8(v: Float): Int = (v.coerceIn(0f, 1f) * Q).roundToInt()

        fun encode(mask: Mask): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).use { d ->
                d.writeInt(MAGIC)
                d.writeInt(FORMAT)
                d.writeInt(mask.width)
                d.writeInt(mask.height)
                DeflaterOutputStream(d).use { z ->
                    z.write(ByteArray(mask.values.size) { q8(mask.values[it]).toByte() })
                }
            }
            return out.toByteArray()
        }

        fun decode(bytes: ByteArray): Mask = DataInputStream(bytes.inputStream()).use { d ->
            if (d.readInt() != MAGIC || d.readInt() != FORMAT) throw IOException("not a mask cache entry")
            val w = d.readInt()
            val h = d.readInt()
            if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) throw IOException("bad mask size")
            val raw = InflaterInputStream(d).use { it.readBytes() }
            if (raw.size != w * h) throw IOException("truncated mask")
            Mask(w, h, FloatArray(raw.size) { (raw[it].toInt() and BYTE) / Q })
        }

        private const val Q = 255f
        private const val BYTE = 0xff
    }
}
