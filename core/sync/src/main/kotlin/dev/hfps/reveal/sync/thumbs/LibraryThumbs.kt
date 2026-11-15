@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.thumbs

import android.graphics.Bitmap
import android.util.Log
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.ServerThumbEntity
import dev.hfps.reveal.data.db.ServerThumbState
import dev.hfps.reveal.data.storage.CacheArea
import dev.hfps.reveal.data.storage.CacheItem
import dev.hfps.reveal.data.storage.CacheTier
import dev.hfps.reveal.data.storage.LibraryThumbsPreferences
import dev.hfps.reveal.data.storage.StorageKind
import dev.hfps.reveal.data.thumb.ServerThumb
import dev.hfps.reveal.data.thumb.ServerThumbs
import dev.hfps.reveal.sync.api.PREVIEW_THUMB
import dev.hfps.reveal.sync.api.PreviewAnswer
import dev.hfps.reveal.sync.api.SyncApi
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The library thumbnails (ADR 0018 "W3/C"): hoard-server's grid thumbnail (Hoard ADR 0015: 512 px WebP) of every photo
 * it holds, re-encoded at [STORED_EDGE] px and kept in [dir] (`no_backup/library-thumbs`, indexed by `server_thumbs`),
 * so the grid of a 20,000-photo library scrolls offline and never range-reads an original or opens a content provider
 * per cell.
 *
 * - The grid asks [thumbnail] per cell: a stored one is decoded from its file; a missing one joins a batch (the cells
 *   asked within [BATCH_WINDOW_MS]): one item lookup (fingerprint -> item, ≤ 1000), one warm request
 *   (`POST /v1/items/previews`, ≤ 500, which returns each ready preview's `v`), then the immutable `?v=` GETs,
 *   [FETCH_PARALLELISM] at a time.
 * - [prefetch] does the same for the whole library, newest shot first, in batches of [PREFETCH_BATCH] (the background
 *   job: unmetered network, charging or idle, resumable since every result is a row).
 * - As a [CacheArea] it is evicted last ([CacheTier.LIBRARY]); [prefetch] stops at [FULL_SHARE] of the cache budget.
 */
@Suppress("TooManyFunctions", "LongParameterList")
class LibraryThumbs(
    private val db: RevealDatabase,
    private val api: () -> SyncApi?,
    private val dir: File,
    private val preferences: LibraryThumbsPreferences,
    private val scope: CoroutineScope,
    private val codec: ThumbCodec = AndroidThumbCodec,
    private val cap: () -> Long = { Long.MAX_VALUE },
    private val onWrite: () -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
) : ServerThumbs,
    CacheArea {
    private val dao = db.serverThumbDao()
    private val touched = ConcurrentHashMap.newKeySet<String>()
    private val fetches = Semaphore(FETCH_PARALLELISM)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val prefetchLock = Mutex()

    /** Per server address: whether it serves previews (`items` + `previews`), and when that was asked. */
    private val support = ConcurrentHashMap<String, Pair<Boolean, Long>>()

    private class Request(val fingerprint: String, val answer: CompletableDeferred<ServerThumb>)

    init {
        scope.launch(Dispatchers.IO) { serveRequests() }
    }

    // ------------------------------------------------------------------ the grid

    override suspend fun thumbnail(fingerprint: String): ServerThumb = withContext(Dispatchers.IO) {
        stored(fingerprint)?.let { return@withContext it }
        val row = dao.get(fingerprint)
        if (row?.state == ServerThumbState.UNAVAILABLE && row.updatedAt > clock() - UNAVAILABLE_RETRY_MS) {
            return@withContext ServerThumb.NotServed
        }
        val client = api() ?: return@withContext ServerThumb.NotServed
        if (!supported(client)) return@withContext ServerThumb.NotServed
        val answer = CompletableDeferred<ServerThumb>()
        requests.send(Request(fingerprint, answer))
        answer.await()
    }

    /** The stored thumbnail of [fingerprint], or null. */
    private suspend fun stored(fingerprint: String): ServerThumb? {
        val row = dao.get(fingerprint) ?: return null
        val name = row.file
        if (row.state != ServerThumbState.READY || name == null) return null
        val bitmap = codec.decode(File(dir, name))
        if (bitmap == null) {
            dao.delete(listOf(fingerprint))
            return null
        }
        touch(fingerprint)
        return ServerThumb.Image(bitmap)
    }

    /** Collects the cells' requests into batches and answers them. */
    @Suppress("LoopWithTooManyJumpStatements")
    private suspend fun serveRequests() {
        while (true) {
            val first = requests.receive()
            val batch = linkedMapOf(first.fingerprint to mutableListOf(first.answer))
            withTimeoutOrNull(BATCH_WINDOW_MS) {
                while (batch.size < GRID_BATCH) {
                    val next = requests.receive()
                    batch.getOrPut(next.fingerprint) { mutableListOf() } += next.answer
                }
            }
            scope.launch(Dispatchers.IO) {
                val results = try {
                    val client = api()
                    if (client == null) emptyMap() else fetch(client, batch.keys.toList(), pendingRetries = 2)
                } catch (e: CancellationException) {
                    throw e
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    Log.i(TAG, "grid batch failed: ${e.javaClass.simpleName}")
                    emptyMap()
                }
                for ((print, answers) in batch) {
                    val result = results[print] ?: ServerThumb.Later
                    answers.forEach { it.complete(result) }
                }
            }
        }
    }

    override suspend fun editedThumbnail(fingerprint: String, versionUuid: String): Bitmap? =
        withContext(Dispatchers.IO) {
            val client = api() ?: return@withContext null
            if (!supported(client)) return@withContext null
            val item = dao.get(fingerprint)?.itemId ?: lookup(client, listOf(fingerprint))[fingerprint]
                ?: return@withContext null
            when (val answer = fetches.withPermit { client.preview(item, PREVIEW_THUMB, edit = versionUuid) }) {
                is PreviewAnswer.Ready -> codec.decode(answer.bytes)
                else -> null
            }
        }

    // ------------------------------------------------------------------ fetching

    /**
     * Fetches the thumbnails of [fingerprints] and stores them; returns what each one is now. A pending preview is
     * asked again up to [pendingRetries] times ([PENDING_WAIT_MS] apart). Throws [IOException] when the server cannot
     * be reached (nothing is recorded then).
     */
    internal suspend fun fetch(
        client: SyncApi,
        fingerprints: List<String>,
        pendingRetries: Int = 0,
    ): Map<String, ServerThumb> {
        val now = clock()
        val items = itemsOf(client, fingerprints)
        val results = HashMap<String, ServerThumb>()
        val unavailable = fingerprints.filter { it !in items }
        unavailable.forEach { results[it] = ServerThumb.NotServed }
        if (unavailable.isNotEmpty()) {
            dao.upsert(unavailable.map { ServerThumbEntity(it, state = ServerThumbState.UNAVAILABLE, updatedAt = now) })
        }
        var waiting = items.entries.groupBy({ it.value }, { it.key })
        var attempt = 0
        while (waiting.isNotEmpty()) {
            val pending = HashMap<String, List<String>>()
            for (chunk in waiting.keys.chunked(MAX_WARM)) {
                val ready = warm(client, chunk, waiting, now, results, pending)
                results += download(client, ready, waiting)
            }
            if (pending.isEmpty() || attempt++ >= pendingRetries) break
            delay(PENDING_WAIT_MS)
            waiting = pending
        }
        return results
    }

    /** fingerprint -> item id of [fingerprints] the server holds, in the order asked (newest shot first). */
    private suspend fun itemsOf(client: SyncApi, fingerprints: List<String>): LinkedHashMap<String, String> {
        val rows = dao.getAll(fingerprints).associateBy { it.fingerprint }
        val known = HashMap<String, String>()
        for (print in fingerprints) rows[print]?.itemId?.let { known[print] = it }
        val unknown = fingerprints.filter { it !in known }
        if (unknown.isNotEmpty()) known += lookup(client, unknown)
        val items = LinkedHashMap<String, String>()
        for (print in fingerprints) known[print]?.let { items[print] = it }
        return items
    }

    /**
     * One warm request for the items of [chunk]: records the pending and unavailable ones ([results], [pending]) and
     * returns the ready ones with their `v`.
     */
    @Suppress("LongParameterList")
    private suspend fun warm(
        client: SyncApi,
        chunk: List<String>,
        waiting: Map<String, List<String>>,
        now: Long,
        results: MutableMap<String, ServerThumb>,
        pending: MutableMap<String, List<String>>,
    ): List<Pair<String, String>> {
        val answer = client.warmPreviews(chunk)
        val ready = ArrayList<Pair<String, String>>()
        val rows = ArrayList<ServerThumbEntity>()
        fun mark(prints: List<String>, item: String?, state: ServerThumbState, result: ServerThumb) = prints.forEach {
            rows += ServerThumbEntity(it, item, state = state, updatedAt = now)
            results[it] = result
        }
        for (item in answer.items) {
            val prints = waiting[item.id].orEmpty()
            val thumb = item.thumb
            val v = thumb?.v
            when {
                thumb?.status == STATUS_READY && v != null -> ready += item.id to v

                thumb?.status == STATUS_PENDING -> {
                    pending[item.id] = prints
                    mark(prints, item.id, ServerThumbState.PENDING, ServerThumb.Later)
                }

                else -> mark(prints, item.id, ServerThumbState.UNAVAILABLE, ServerThumb.NotServed)
            }
        }
        for (id in answer.missing) {
            mark(waiting[id].orEmpty(), null, ServerThumbState.UNAVAILABLE, ServerThumb.NotServed)
        }
        if (rows.isNotEmpty()) dao.upsert(rows)
        return ready
    }

    /** fingerprint -> item id for [fingerprints] (the server's lookup, ≤ [MAX_LOOKUP] per request). */
    private fun lookup(client: SyncApi, fingerprints: List<String>): Map<String, String> =
        fingerprints.chunked(MAX_LOOKUP).flatMap { chunk ->
            client.itemsLookup(chunk).matches.map { it.fingerprint to it.itemId }
        }.toMap()

    /** GETs the ready previews ([items]: id and `v`) and stores them for their fingerprints. */
    private suspend fun download(
        client: SyncApi,
        items: List<Pair<String, String>>,
        prints: Map<String, List<String>>,
    ): Map<String, ServerThumb> = coroutineScope {
        items.map { (id, v) ->
            async(Dispatchers.IO) {
                val answer = fetches.withPermit { client.preview(id, PREVIEW_THUMB, v) }
                val targets = prints[id].orEmpty()
                when (answer) {
                    is PreviewAnswer.Ready -> store(id, answer.v ?: v, answer.bytes, targets)

                    PreviewAnswer.Pending -> targets.associateWith { ServerThumb.Later }

                    PreviewAnswer.Unavailable -> {
                        dao.upsert(
                            targets.map {
                                ServerThumbEntity(it, id, state = ServerThumbState.UNAVAILABLE, updatedAt = clock())
                            },
                        )
                        targets.associateWith { ServerThumb.NotServed }
                    }
                }
            }
        }.awaitAll().fold(HashMap()) { all, one -> all.apply { putAll(one) } }
    }

    /** Re-encodes [bytes] small and stores the file once per fingerprint of the item. */
    private suspend fun store(
        item: String,
        v: String,
        bytes: ByteArray,
        prints: List<String>,
    ): Map<String, ServerThumb> {
        val small = codec.shrink(bytes, STORED_EDGE)
        if (small == null) {
            dao.upsert(
                prints.map {
                    ServerThumbEntity(it, item, state = ServerThumbState.UNAVAILABLE, updatedAt = clock())
                },
            )
            return prints.associateWith { ServerThumb.NotServed }
        }
        dir.mkdirs()
        val now = clock()
        val rows = prints.map { print ->
            val name = fileName(print)
            val temp = File(dir, "$name.tmp")
            temp.writeBytes(small)
            if (!temp.renameTo(File(dir, name))) throw IOException("could not store a thumbnail")
            ServerThumbEntity(print, item, v, ServerThumbState.READY, name, small.size.toLong(), now, now)
        }
        dao.upsert(rows)
        onWrite()
        val bitmap = codec.decode(small)
        return prints.associateWith { if (bitmap != null) ServerThumb.Image(bitmap) else ServerThumb.Later }
    }

    /** Whether [client]'s server serves previews (asked once per [SUPPORT_TTL_MS]; unknown while offline = no). */
    private fun supported(client: SyncApi): Boolean {
        val key = client.baseUrl
        support[key]?.let { (yes, at) -> if (clock() - at < SUPPORT_TTL_MS) return yes }
        val yes = try {
            client.server().let { it.has(FEATURE_ITEMS) && it.has(FEATURE_PREVIEWS) }
        } catch (e: IOException) {
            Log.i(TAG, "server features unknown: ${e.javaClass.simpleName}")
            return support[key]?.first ?: false
        }
        support[key] = yes to clock()
        return yes
    }

    // ------------------------------------------------------------------ the whole library

    /** What one [prefetch] run did. */
    data class PrefetchReport(val stored: Int, val pending: Int, val unavailable: Int, val left: Boolean, val ms: Long)

    /**
     * Fetches the missing thumbnails of the whole library, newest shot first, [PREFETCH_BATCH] per round, until none
     * is missing, the switch is off, the store reached [FULL_SHARE] of the budget, [maxItems] were asked for or the
     * server cannot be reached ([IOException]). One run at a time; cancellable between rounds.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    suspend fun prefetch(maxItems: Int = Int.MAX_VALUE): PrefetchReport = withContext(Dispatchers.IO) {
        prefetchLock.withLock {
            val started = clock()
            var stored = 0
            var pending = 0
            var unavailable = 0
            var asked = 0
            var left = false
            val client = api()
            if (client != null && supported(client)) {
                while (preferences.keepAll.value && asked < maxItems) {
                    if (dao.storedBytes() >= cap() * FULL_SHARE) {
                        Log.i(TAG, "prefetch stops: the library thumbnails fill the cache budget")
                        break
                    }
                    val now = clock()
                    val batch = dao.missing(PREFETCH_BATCH, now - PENDING_RETRY_MS, now - UNAVAILABLE_RETRY_MS)
                        .map { it.fingerprint }.distinct()
                    if (batch.isEmpty()) break
                    asked += batch.size
                    val results = fetch(client, batch)
                    val ready = results.values.count { it is ServerThumb.Image }
                    stored += ready
                    pending += results.values.count { it == ServerThumb.Later }
                    unavailable += results.values.count { it == ServerThumb.NotServed }
                    left = true
                    if (ready == 0) break
                }
                left = left && dao.missing(1, clock() - PENDING_RETRY_MS, clock() - UNAVAILABLE_RETRY_MS).isNotEmpty()
            }
            PrefetchReport(stored, pending, unavailable, left, clock() - started).also {
                if (it.stored + it.pending + it.unavailable > 0) Log.i(TAG, "prefetch: $it")
            }
        }
    }

    /** Settings -> Storage: thumbnails stored, their bytes, and the photos the server holds that can have one. */
    data class Stats(val stored: Int, val bytes: Long, val photos: Int)

    suspend fun stats(): Stats = withContext(Dispatchers.IO) {
        Stats(dao.storedCount(), dao.storedBytes(), dao.wantedUpperBound())
    }

    // ------------------------------------------------------------------ cache area

    override val kind: StorageKind get() = StorageKind.LIBRARY_THUMBNAILS

    override suspend fun items(): List<CacheItem> = withContext(Dispatchers.IO) {
        flushTouches()
        dao.stored().map { CacheItem(it.fingerprint, it.bytes, it.lastUsed, CacheTier.LIBRARY) }
    }

    override suspend fun evict(keys: Collection<String>): Unit = withContext(Dispatchers.IO) {
        keys.chunked(SQL_ARGS).forEach { chunk ->
            chunk.forEach { File(dir, fileName(it)).delete() }
            dao.delete(chunk)
        }
    }

    override suspend fun clear(): Unit = withContext(Dispatchers.IO) {
        dao.clear()
        dir.listFiles()?.forEach { it.delete() }
    }

    private suspend fun touch(fingerprint: String) {
        touched += fingerprint
        if (touched.size >= TOUCH_BATCH) flushTouches()
    }

    private suspend fun flushTouches() {
        val keys = touched.toList()
        touched.removeAll(keys.toSet())
        keys.chunked(SQL_ARGS).forEach { dao.touch(it, clock()) }
    }

    companion object {
        private const val TAG = "RevealLibraryThumbs"

        /**
         * Long edge of a stored thumbnail (ADR 0018 "W3/C", measured): grid cells are square and centre-cropped, so a
         * 3:2 photo keeps a 256 px short edge for the phone's 360 px cells; WebP q70 averages 9.3 KB on the CC0 camera
         * samples (16 KB scaled to Hoard's bench photos), about 200-330 MB for 21,000 photos.
         */
        const val STORED_EDGE = 384

        /** WebP quality of a stored thumbnail. */
        const val QUALITY = 70
        const val DIR = "library-thumbs"
        const val FETCH_PARALLELISM = 4
        const val PREFETCH_BATCH = 500
        const val MAX_WARM = 500
        const val MAX_LOOKUP = 1000
        const val GRID_BATCH = 100
        const val BATCH_WINDOW_MS = 40L
        const val PENDING_WAIT_MS = 2_000L
        const val PENDING_RETRY_MS = 30 * 60 * 1000L
        const val UNAVAILABLE_RETRY_MS = 7 * 24 * 60 * 60 * 1000L
        const val SUPPORT_TTL_MS = 10 * 60 * 1000L

        /** The prefetch stops when the library thumbnails reach this share of the cache budget. */
        const val FULL_SHARE = 0.9
        private const val TOUCH_BATCH = 64
        private const val SQL_ARGS = 900
        private const val STATUS_READY = "ready"
        private const val STATUS_PENDING = "pending"
        private const val FEATURE_ITEMS = "items"
        private const val FEATURE_PREVIEWS = "previews"

        /** The stored file of [fingerprint]. */
        fun fileName(fingerprint: String) = fingerprint.removePrefix("v1:") + ".webp"
    }
}
