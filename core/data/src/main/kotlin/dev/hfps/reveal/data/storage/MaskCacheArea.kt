package dev.hfps.reveal.data.storage

import dev.hfps.reveal.ml.service.MaskDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The AI masks' disk cache ([MaskDiskCache], docs/adr/0021 "M4/C") as one of the caches [CacheBudget] trims and
 * "Clear cache" empties (Settings -> Storage, "AI masks"). The adapter lives here because `:core:data` owns the
 * budget and `:core:ml` must not depend on it (Room, the library): `:core:data` depends on `:core:ml` instead.
 */
class MaskCacheArea(val cache: MaskDiskCache) : CacheArea {
    override val kind: StorageKind get() = StorageKind.MASKS

    override suspend fun items(): List<CacheItem> = withContext(Dispatchers.IO) {
        cache.entries().map { CacheItem(it.name, it.bytes, it.lastUsed) }
    }

    override suspend fun evict(keys: Collection<String>) = withContext(Dispatchers.IO) { cache.evict(keys) }

    override suspend fun clear() = withContext(Dispatchers.IO) { cache.clear() }

    companion object {
        /** The masks' directory in the app's cache. */
        const val DIR = "masks"
    }
}
