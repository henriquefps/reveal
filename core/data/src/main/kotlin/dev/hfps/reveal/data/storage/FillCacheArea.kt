package dev.hfps.reveal.data.storage

import dev.hfps.reveal.ml.inpaint.FillDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * AI Remove's fills on disk ([FillDiskCache], docs/adr/0024) as one of the caches [CacheBudget] trims and "Clear
 * cache" empties; counted with the AI masks (Settings -> Storage, "AI masks and fills": both are made again from the
 * photo when needed).
 */
class FillCacheArea(val cache: FillDiskCache) : CacheArea {
    override val kind: StorageKind get() = StorageKind.MASKS

    override suspend fun items(): List<CacheItem> = withContext(Dispatchers.IO) {
        cache.entries().map { CacheItem(it.name, it.bytes, it.lastUsed) }
    }

    override suspend fun evict(keys: Collection<String>) = withContext(Dispatchers.IO) { cache.evict(keys) }

    override suspend fun clear() = withContext(Dispatchers.IO) { cache.clear() }

    companion object {
        /** The fills' directory in the app's cache. */
        const val DIR = "inpaint"

        /** The GPU's compiled programs of the inpainting model, in the app's code cache (cleared on updates). */
        const val GPU_PROGRAMS = "litert-gpu"
    }
}
