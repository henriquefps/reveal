package dev.hfps.reveal.data.storage

import android.content.SharedPreferences
import androidx.core.content.edit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** What Reveal keeps in its own storage, by kind (Settings -> Storage, ADR 0018 amendment FIX/W). */
enum class StorageKind {
    /** Grid and filmstrip thumbnails (`cache/thumbs`). */
    THUMBNAILS,

    /** Parts of server originals read by range, and server smart previews, while you view them (`cache/sync-*`). */
    SERVER_FILES,

    /** AI masks and AI Remove fills (`cache/masks`, `cache/inpaint`, docs/adr/0021, 0024): made again when needed. */
    MASKS,

    /** Previews of photos other apps handed over (`no_backup/handoff-previews`): some are a photo's only copy. */
    HANDOFF_PREVIEWS,

    /** Everything else in the cache (exports being written, shared files, downloads) and previews being uploaded. */
    OTHER,

    /** The library database: edits (recipes and history), ratings, sync state. Never cleared. */
    LIBRARY,
}

/** One entry of a cache as the LRU sees it: a file, or the blocks of one remote file. */
data class CacheItem(val key: String, val bytes: Long, val lastUsed: Long)

/** A cache [CacheBudget] trims and "Clear cache" empties. Nothing in one is ever the only copy of anything. */
interface CacheArea {
    val kind: StorageKind

    /** Every entry with its size and last use. */
    suspend fun items(): List<CacheItem>

    /** Deletes the entries of [keys]. */
    suspend fun evict(keys: Collection<String>)

    /** Deletes everything. */
    suspend fun clear()
}

/**
 * The maximum size of Reveal's caches (Settings -> Storage, default [DEFAULT_MAX_BYTES]) and the least-recently-used
 * trim across every registered [CacheArea]: thumbnails and the server file caches together stay under it. Each area
 * also caps itself at [maxBytes] on its own writes and asks for a global trim ([requestTrim], at most every
 * [THROTTLE_MS]).
 */
class CacheBudget(
    private val prefs: SharedPreferences?,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val areas = CopyOnWriteArrayList<CacheArea>()
    private val max = MutableStateFlow(prefs?.getLong(KEY_MAX, DEFAULT_MAX_BYTES) ?: DEFAULT_MAX_BYTES)
    private val mutex = Mutex()

    @Volatile
    private var lastTrim = 0L

    val maxBytes: StateFlow<Long> = max.asStateFlow()

    /** The current cap (for the areas' own trims). */
    fun cap(): Long = max.value

    fun register(area: CacheArea) {
        areas += area
    }

    fun areas(): List<CacheArea> = areas.toList()

    fun setMaxBytes(bytes: Long) {
        prefs?.edit { putLong(KEY_MAX, bytes) }
        max.value = bytes
        lastTrim = 0
        requestTrim()
    }

    /** A trim soon, in the background (after a cache write); throttled. */
    fun requestTrim() {
        val now = clock()
        if (now - lastTrim < THROTTLE_MS) return
        lastTrim = now
        scope.launch(Dispatchers.IO) { runCatching { trim() } }
    }

    /** Deletes the least recently used entries of all areas until they fit the cap; returns the bytes freed. */
    suspend fun trim(): Long = mutex.withLock {
        val all = areas.flatMap { area -> area.items().map { area to it } }
        var total = all.sumOf { it.second.bytes }
        val cap = max.value
        if (total <= cap) return@withLock 0L
        val victims = LinkedHashMap<CacheArea, MutableList<String>>()
        var freed = 0L
        for ((area, item) in all.sortedBy { it.second.lastUsed }) {
            if (total <= cap) break
            victims.getOrPut(area) { ArrayList() } += item.key
            total -= item.bytes
            freed += item.bytes
        }
        for ((area, keys) in victims) area.evict(keys)
        freed
    }

    companion object {
        private const val KEY_MAX = "cache.maxBytes"

        /** Decimal, as Android shows file sizes (`Formatter`): the choices read "500 MB", "2 GB". */
        private const val MB = 1_000_000L
        const val DEFAULT_MAX_BYTES = 2_000 * MB
        const val THROTTLE_MS = 30_000L

        /** The sizes Settings offers. */
        val CHOICES: List<Long> = listOf(500 * MB, 1_000 * MB, 2_000 * MB, 5_000 * MB, 10_000 * MB)
    }
}
