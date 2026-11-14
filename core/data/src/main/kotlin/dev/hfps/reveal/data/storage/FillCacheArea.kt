package dev.hfps.reveal.data.storage

import dev.hfps.reveal.ml.inpaint.FillDiskCache
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Remove's files (docs/adr/0024) as one of the caches [CacheBudget] trims and "Clear cache" empties, counted with
 * the AI masks (Settings -> Storage, "AI masks and fills": all made again when needed): the fills ([FillDiskCache])
 * and the GPU's compiled programs of the model in [programs] (≈ 107 MB for big-LaMa on the S24; they make a load
 * ≈ 1.8 s shorter, and are compiled again when deleted).
 */
class FillCacheArea(val cache: FillDiskCache, private val programs: File? = null) : CacheArea {
    override val kind: StorageKind get() = StorageKind.MASKS

    override suspend fun items(): List<CacheItem> = withContext(Dispatchers.IO) {
        cache.entries().map { CacheItem(it.name, it.bytes, it.lastUsed) } +
            programFiles().map { CacheItem(PROGRAMS_PREFIX + it.name, it.length(), it.lastModified()) }
    }

    override suspend fun evict(keys: Collection<String>) = withContext(Dispatchers.IO) {
        val (gpu, fills) = keys.partition { it.startsWith(PROGRAMS_PREFIX) }
        cache.evict(fills)
        val dir = programs ?: return@withContext
        gpu.forEach { File(dir, it.removePrefix(PROGRAMS_PREFIX)).delete() }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        cache.clear()
        programFiles().forEach { it.delete() }
    }

    private fun programFiles(): List<File> = programs?.listFiles()?.filter { it.isFile }.orEmpty()

    companion object {
        /** The fills' directory in the app's cache. */
        const val DIR = "inpaint"

        /** The GPU's compiled programs of the inpainting model, in the app's code cache (cleared on updates). */
        const val GPU_PROGRAMS = "litert-gpu"

        private const val PROGRAMS_PREFIX = "gpu/"
    }
}
