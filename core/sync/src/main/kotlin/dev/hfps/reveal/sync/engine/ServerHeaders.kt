package dev.hfps.reveal.sync.engine

import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.index.LibraryIndexer.ServerHeader
import dev.hfps.reveal.sync.api.ItemCapture
import dev.hfps.reveal.sync.api.ServerItem
import dev.hfps.reveal.sync.api.SyncApi
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * The Server source's headers from the server's items (ADR 0018 "W3/C", Hoard ADR 0014): capture time, size and
 * orientation of every photo still `PENDING` whose original the server serves, by item lookups of up to
 * [LOOKUP_BATCH] fingerprints. No file is opened (the range reads they replace mounted an appfuse proxy each).
 */
object ServerHeaders {
    const val LOOKUP_BATCH = 1000

    /** Photos per lookup: a RAW + JPEG pair has two fingerprints, and the query stays under SQLite's 999 arguments. */
    const val PHOTO_BATCH = 400

    /** Fills up to [limit] photos of [sourceId]; returns how many were written (a missed photo waits, `PENDING`). */
    suspend fun read(
        db: RevealDatabase,
        api: SyncApi,
        sourceId: Long,
        limit: Int,
        now: Long = System.currentTimeMillis(),
    ): Int {
        val photos = db.syncDao().serverHeaderCandidates(sourceId, limit, now - LibraryIndexer.MISS_BACKOFF_MS)
        if (photos.isEmpty()) return 0
        val indexer = LibraryIndexer(db)
        var written = 0
        for (chunk in photos.chunked(PHOTO_BATCH)) {
            // A photo's files (RAW first): the lookup finds the item by any member.
            val files = db.photoDao().filesOf(chunk).filter { it.fingerprint != null }
                .sortedBy { if (it.role == dev.hfps.reveal.data.model.FileRole.RAW) 0 else 1 }
            val prints = files.mapNotNull { it.fingerprint }.distinct().take(LOOKUP_BATCH)
            val answer = api.itemsLookup(prints)
            val items = answer.items.associateBy { it.id }
            val itemOf = answer.matches.associate { it.fingerprint to it.itemId }
            val byPhoto = files.groupBy { it.photoId }
            val headers = chunk.associateWith { photoId ->
                byPhoto[photoId].orEmpty().firstNotNullOfOrNull { f -> itemOf[f.fingerprint]?.let(items::get) }
                    ?.let(::headerOf)
            }
            indexer.applyServerHeaders(headers, now)
            written += headers.values.count { it != null }
        }
        return written
    }

    /** What Reveal stores of an item's metadata. */
    fun headerOf(item: ServerItem): ServerHeader = ServerHeader(
        captureTime = captureTime(item.capture) ?: item.sortTime?.takeIf { it > 0 },
        offsetMinutes = item.capture?.offset?.let(::offsetMinutes),
        width = item.width,
        height = item.height,
        orientation = item.orientation,
    )

    /** The wall clock (`2024-05-01T10:00:00.500`) as UTC milliseconds, Reveal's capture time convention. */
    fun captureTime(capture: ItemCapture?): Long? {
        val local = capture?.local ?: return null
        return runCatching { LocalDateTime.parse(local).toInstant(ZoneOffset.UTC).toEpochMilli() }.getOrNull()
    }

    /** `-03:00` -> -180. */
    fun offsetMinutes(offset: String): Int? = runCatching { ZoneOffset.of(offset).totalSeconds / SECONDS }.getOrNull()

    private const val SECONDS = 60
}
