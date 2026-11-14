package dev.hfps.reveal.data.storage

import dev.hfps.reveal.pipeline.heal.RemoveFill
import dev.hfps.reveal.pipeline.heal.RemoveFillCodec
import dev.hfps.reveal.pipeline.heal.RemoveFillStore
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Remove's fills on disk (docs/adr/0023 "FIX/H"): what the editor made (patch or AI) for a photo, by the photo's file
 * fingerprint, the crop, the shape and the method, so the library's thumbnails and the viewer show the same fill
 * without the original and an export after a restart does not compute it again. One file a fill, named by the SHA-256
 * of its key (no fingerprint or name on disk). Counted with the AI masks by the budget (Settings -> Storage, "AI masks
 * and fills": made again when needed); [cap] is applied after each write.
 */
class HealFillCacheArea(
    private val dir: File,
    private val cap: () -> Long = { Long.MAX_VALUE },
    private val onWrite: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : CacheArea,
    RemoveFillStore {
    override val kind: StorageKind get() = StorageKind.MASKS
    private val lock = Any()

    override fun get(key: String): RemoveFill? = synchronized(lock) {
        val file = File(dir, name(key))
        if (!file.isFile) return null
        try {
            RemoveFillCodec.decode(file.readBytes()).also { file.setLastModified(clock()) }
        } catch (@Suppress("SwallowedException") e: IOException) {
            file.delete() // a damaged entry is a miss: the fill is made again
            null
        }
    }

    override fun put(key: String, fill: RemoveFill) {
        synchronized(lock) {
            dir.mkdirs()
            val file = File(dir, name(key))
            val tmp = File(dir, "${file.name}.tmp")
            tmp.writeBytes(RemoveFillCodec.encode(fill))
            if (!tmp.renameTo(file)) tmp.delete()
            file.setLastModified(clock())
            trim(cap())
        }
        onWrite()
    }

    override suspend fun items(): List<CacheItem> = withContext(Dispatchers.IO) {
        synchronized(lock) { files().map { CacheItem(it.name, it.length(), it.lastModified()) } }
    }

    override suspend fun evict(keys: Collection<String>) = withContext(Dispatchers.IO) {
        synchronized(lock) { keys.forEach { File(dir, it).delete() } }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        synchronized(lock) { dir.listFiles().orEmpty().forEach { it.delete() } }
    }

    private fun files(): List<File> = dir.listFiles { f -> f.name.endsWith(SUFFIX) }.orEmpty().toList()

    private fun trim(maxBytes: Long) {
        val all = files().sortedBy { it.lastModified() }
        var total = all.sumOf { it.length() }
        for (f in all) {
            if (total <= maxBytes) break
            total -= f.length()
            f.delete()
        }
    }

    companion object {
        /** The fills' directory in the app's cache. */
        const val DIR = "heal-fills"
        private const val SUFFIX = ".hfill"

        fun name(key: String): String = MessageDigest.getInstance("SHA-256").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) } + SUFFIX
    }
}
