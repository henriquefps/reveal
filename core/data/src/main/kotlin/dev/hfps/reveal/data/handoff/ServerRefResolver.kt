package dev.hfps.reveal.data.handoff

import android.util.Log
import androidx.room.withTransaction
import dev.hfps.image.format.ImageFormats
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.Shot
import dev.hfps.reveal.data.db.FingerprintMatch
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.sync.ServerTree
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** A photo a server reference resolved to. */
class ServedPhoto(val itemId: String, val photoId: Long, val versionId: Long)

/** What resolving the server references of one request came to (ADR 0011 "Amendment W3/H"). */
class ServerBatch(
    val photos: List<ServedPhoto> = emptyList(),
    /** Items the server does not have (404, or a tombstone without a replacement), skipped. */
    val notFound: Int = 0,
    val issue: ServerIssue? = null,
    /** The editor's photo: the `INITIAL_ITEM`'s version. */
    val initialVersionId: Long? = null,
)

/** A batch kept for later (`meta`): [reason] says what it waits for. */
@Serializable
internal data class PendingRefs(
    val serverId: String,
    val title: String? = null,
    val items: List<PendingItem>,
    val initialItem: String? = null,
    val savedAt: Long = 0,
)

@Serializable
internal data class PendingItem(
    val itemId: String,
    val fingerprint: String? = null,
    val fingerprints: List<String> = emptyList(),
    val name: String? = null,
) {
    fun toRef() = ServerItemRef(itemId, fingerprint, fingerprints, name)

    companion object {
        fun of(ref: ServerItemRef) = PendingItem(ref.itemId, ref.fingerprint, ref.fingerprints, ref.name)
    }
}

/**
 * Resolves the server references of a hand-off (ADR 0011 "Amendment W3/H") to photos, through the paired server's own
 * API and never through the sender: a photo already in the library under any of the item's fingerprints is that photo
 * (ADR 0006 "Identity"), else the item becomes a Server photo (`sources.kind = SERVER`). Culling is the server's: it is
 * pulled once the photos are in. A batch that cannot be resolved yet is kept in `meta` (not paired: until the pairing;
 * server unreachable: until it answers).
 */
internal class ServerRefResolver(
    private val db: RevealDatabase,
    private val server: ServerItems,
    private val clock: () -> Long,
) {
    private val photos = db.photoDao()
    private val meta = db.metaDao()

    /** Which of [refs]' photos open, or why none does. */
    suspend fun resolve(refs: ServerRefs, title: String?, refresh: Boolean = false, at: Long? = null): ServerBatch {
        val pairing = server.pairing()
        return when {
            pairing == null -> {
                keepBatch(refs, title)
                ServerBatch(issue = ServerIssue.NotPaired(refs.items.size))
            }

            pairing.serverId != refs.serverId ->
                ServerBatch(issue = ServerIssue.OtherServer(pairing.serverName, refs.items.size))

            else -> resolvePaired(refs, title, refresh, at)
        }
    }

    private class Hit(val photoId: Long, val hidden: Boolean)

    private sealed interface Fetched {
        class Info(val info: ServerItemInfo) : Fetched

        data object Missing : Fetched

        data object Unreachable : Fetched
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun resolvePaired(refs: ServerRefs, title: String?, refresh: Boolean, at: Long?): ServerBatch {
        val now = at ?: clock()
        val local = localHits(refs.items, refresh)
        val misses = refs.items.filter { it.itemId !in local }
        // The network part: a few requests at a time, nothing written yet.
        val gate = Semaphore(FETCH_PARALLELISM)
        val fetched = coroutineScope {
            misses.map { ref -> async { ref to gate.withPermit { fetch(ref) } } }.awaitAll()
        }
        var notFound = 0
        val unreachable = ArrayList<ServerItemRef>()
        val byItem = LinkedHashMap<String, Long>()
        db.withTransaction {
            quietly {
                for ((ref, result) in fetched) {
                    when (result) {
                        Fetched.Missing -> notFound++

                        Fetched.Unreachable -> {
                            if (ref.name != null && ref.fingerprint != null) {
                                // Open from what Reveal has: a placeholder that fills in once the server answers.
                                upsert(placeholder(ref), now)?.let { byItem[ref.itemId] = it }
                            }
                            unreachable += ref
                        }

                        is Fetched.Info -> {
                            // The item's own members decide (a replacement item names the shot now).
                            val photoId = localPhotoFor(result.info.fingerprints, refresh) ?: upsert(result.info, now)
                            if (photoId != null) byItem[ref.itemId] = photoId else notFound++
                        }
                    }
                }
            }
            for ((itemId, hit) in local) {
                byItem.putIfAbsent(itemId, hit.photoId)
                if (hit.hidden) photos.setHidden(listOf(hit.photoId), false)
            }
        }
        val served = ArrayList<ServedPhoto>()
        for (ref in refs.items) {
            val photoId = byItem[ref.itemId] ?: continue
            photos.setHandoffAt(photoId, now, title)
            photos.firstVersion(photoId)?.let { served += ServedPhoto(ref.itemId, photoId, it) }
        }
        if (unreachable.isNotEmpty()) keepUnreachable(refs.serverId, title, unreachable) else clearUnreachable()
        // The server's decisions for these shots (Hoard has pushed its own before sending): best effort.
        if (served.isNotEmpty()) server.pullCulling()
        val initial = refs.initialItem?.let { id -> served.firstOrNull { it.itemId == id }?.versionId }
        return ServerBatch(
            photos = served,
            notFound = notFound,
            issue = unreachable.takeIf { it.isNotEmpty() }?.let { ServerIssue.Unreachable(it.size) },
            initialVersionId = initial,
        )
    }

    /** Photos already in the library for [items] (by the item's known members, else the sender's fingerprints). */
    private suspend fun localHits(items: List<ServerItemRef>, refresh: Boolean): Map<String, Hit> {
        val members = items.associate { ref -> ref.itemId to db.cullingDao().filesOf(ref.itemId) }
        val prints = items.flatMap { ref ->
            val known = members.getValue(ref.itemId)
            if (known.isNotEmpty() && ref.allFingerprints.none { it in known }) {
                // The item's own members win over a fingerprint that is not one of them (Hoard sent a stale one).
                if (ref.allFingerprints.isNotEmpty()) {
                    Log.w(TAG, "item ${ref.itemId}: fingerprint does not belong to it; trusting the item id")
                }
                known
            } else {
                (known + ref.allFingerprints).distinct()
            }
        }.distinct()
        val byPrint = prints.chunked(SQL_ARGS).flatMap { photos.filesByFingerprint(it) }.groupBy { it.file.fingerprint }
        val out = LinkedHashMap<String, Hit>()
        for (ref in items) {
            val known = members.getValue(ref.itemId)
            val wanted = if (known.isNotEmpty() && ref.allFingerprints.none { it in known }) {
                known
            } else {
                (ref.allFingerprints + known).distinct()
            }
            val match = wanted.firstNotNullOfOrNull { fp -> byPrint[fp]?.firstOrNull { reusable(it, refresh) } }
            if (match != null) out[ref.itemId] = Hit(match.file.photoId, match.photoHidden)
        }
        return out
    }

    /**
     * A photo of any source that may stand for the shot: a hidden folder photo is not reopened, others are. When
     * [refresh] (a retry), Server photos are refilled from the item instead (a placeholder waiting for the server).
     */
    private fun reusable(match: FingerprintMatch, refresh: Boolean): Boolean = when (match.sourceKind) {
        SourceKind.FOLDER -> match.sourceState == SourceState.AVAILABLE && !match.photoHidden
        SourceKind.SERVER -> !refresh
        else -> true
    }

    private suspend fun localPhotoFor(fingerprints: List<String>, refresh: Boolean): Long? = fingerprints
        .chunked(SQL_ARGS).flatMap { photos.filesByFingerprint(it) }.firstOrNull { reusable(it, refresh) }
        ?.file?.photoId

    /**
     * The item behind [ref]: `GET /v1/items/{id}` following one `replacedBy`; an unknown id falls back to a fingerprint
     * lookup (the sender's); a tombstone without a replacement is gone.
     */
    @Suppress("ReturnCount")
    private suspend fun fetch(ref: ServerItemRef): Fetched {
        var result = server.item(ref.itemId)
        if (result is ServerItemResult.Gone && result.replacedBy != null) result = server.item(result.replacedBy)
        if (result is ServerItemResult.Missing && ref.allFingerprints.isNotEmpty()) {
            val found = server.lookup(ref.allFingerprints)
                ?: return Fetched.Unreachable
            val id = ref.allFingerprints.firstNotNullOfOrNull { found[it] } ?: return Fetched.Missing
            result = server.item(id)
            if (result is ServerItemResult.Gone && result.replacedBy != null) result = server.item(result.replacedBy)
        }
        return when (result) {
            is ServerItemResult.Found -> Fetched.Info(result.item.withNames(ref))
            is ServerItemResult.Gone, ServerItemResult.Missing -> Fetched.Missing
            ServerItemResult.Unreachable -> Fetched.Unreachable
        }
    }

    /** Files the server gave no name get the sender's name (the main file) or one made of their fingerprint. */
    private fun ServerItemInfo.withNames(ref: ServerItemRef) = copy(
        files = files.map { it.named(ref.name.takeIf { _ -> it.fingerprint == mainFingerprint }) },
    )

    private fun placeholder(ref: ServerItemRef): ServerItemInfo {
        val name = checkNotNull(ref.name)
        val print = checkNotNull(ref.fingerprint)
        val raw = ImageFormats.classify(name).isRaw
        return ServerItemInfo(
            itemId = ref.itemId,
            mainFingerprint = print,
            files = listOf(ServerFileInfo(print, if (raw) "raw" else "rendered", name)),
        )
    }

    // ------------------------------------------------------------------ the Server photo

    private suspend fun serverSource(): SourceEntity = db.syncDao().serverSource() ?: run {
        val entity = SourceEntity(
            treeUri = ServerTree.SOURCE_TREE,
            rootDocumentId = "",
            displayName = "",
            volumeId = null,
            volumeName = null,
            state = SourceState.AVAILABLE,
            writable = false,
            addedAt = clock(),
            kind = SourceKind.SERVER,
            backup = false,
        )
        entity.copy(id = db.sourceDao().insert(entity))
    }

    /**
     * Files [info] as a Server photo, or updates the photo that holds its main file (the same shot filed earlier, a
     * placeholder, a NAS photo): its files become the item's, readable by range from the server.
     */
    @Suppress("LongMethod", "ReturnCount")
    private suspend fun upsert(info: ServerItemInfo, now: Long): Long? {
        val source = serverSource()
        val files = info.files.filter { ImageFormats.classify(it.name).isDisplayable }
        val main = files.firstOrNull { it.role == ROLE_RAW } ?: files.firstOrNull { it.role == ROLE_RENDERED }
            ?: files.firstOrNull() ?: return null.also { Log.w(TAG, "item ${info.itemId} has no displayable file") }
        val rendered = files.firstOrNull { it.role == ROLE_RENDERED }
        val raw = files.firstOrNull { ImageFormats.classify(it.name).isRaw }
        val stem = ImageFormats.stem(main.name).lowercase()
        val folder = info.folder.ifEmpty { "item/${info.itemId}" }
        val format = LibraryIndexer.format(
            Shot(stem, raw?.let { ListedFile(it.name, it.size) }, rendered?.let { ListedFile(it.name, it.size) }),
        )
        val existing = files.map { it.fingerprint }.chunked(SQL_ARGS).flatMap { photos.filesByFingerprint(it) }
            .firstOrNull { it.file.sourceId == source.id }?.file?.photoId ?: photos.byStem(source.id, folder, stem)?.id
        val photoId = if (existing != null) {
            photos.deleteFilesOf(existing)
            photos.updateShot(existing, main.name, format, raw != null, "")
            existing
        } else {
            val id = photos.insertPhoto(
                PhotoEntity(
                    sourceId = source.id,
                    folder = folder,
                    folderDocumentId = "",
                    stemKey = stem,
                    name = main.name,
                    format = format,
                    hasRaw = raw != null,
                    sortTime = info.sortTime.takeIf { it > 0 } ?: now,
                    width = info.width,
                    height = info.height,
                    orientation = info.orientation.coerceIn(1, MAX_ORIENTATION),
                    metadataState = MetadataState.PENDING,
                    addedAt = now,
                ),
            )
            photos.insertVersion(VersionEntity(photoId = id, createdAt = now))
            id
        }
        photos.insertFiles(
            files.map { file ->
                PhotoFileEntity(
                    photoId = photoId,
                    sourceId = source.id,
                    relativePath = "item/${info.itemId}/${file.fingerprint.takeLast(FP_SUFFIX)}/${file.name}",
                    documentId = ServerTree.documentId(file.fingerprint, file.name),
                    name = file.name,
                    size = file.size,
                    modified = 0,
                    role = if (ImageFormats.classify(file.name).isRaw) FileRole.RAW else FileRole.RENDERED,
                    fingerprint = file.fingerprint,
                    onServer = true,
                )
            },
        )
        db.syncDao().putCloud(
            (db.syncDao().cloud(photoId) ?: PhotoCloudEntity(photoId)).copy(
                originalOnServer = true,
                originalSource = info.originalSource,
                checkedAt = now,
            ),
        )
        return photoId
    }

    // ------------------------------------------------------------------ batches kept for later

    private suspend fun keepBatch(refs: ServerRefs, title: String?) {
        val pending = PendingRefs(refs.serverId, title, refs.items.map(PendingItem::of), refs.initialItem, clock())
        meta.put(MetaEntity(KEY_PENDING, json.encodeToString(PendingRefs.serializer(), pending)))
    }

    /** The batch that waited for a pairing, if any. */
    private suspend fun pending(key: String): PendingRefs? =
        meta.get(key)?.let { runCatching { json.decodeFromString(PendingRefs.serializer(), it) }.getOrNull() }

    suspend fun pendingBatch(): ServerRefs? = pending(KEY_PENDING)?.let(::refsOf)

    suspend fun pendingTitle(): String? = pending(KEY_PENDING)?.title

    suspend fun clearPending() = meta.delete(KEY_PENDING)

    private fun refsOf(p: PendingRefs) = ServerRefs(p.serverId, p.items.map(PendingItem::toRef), p.initialItem)

    private suspend fun keepUnreachable(serverId: String, title: String?, items: List<ServerItemRef>) {
        val pending = PendingRefs(serverId, title, items.map(PendingItem::of), null, clock())
        meta.put(MetaEntity(KEY_RETRY, json.encodeToString(PendingRefs.serializer(), pending)))
    }

    private suspend fun clearUnreachable() = meta.delete(KEY_RETRY)

    /** The unreachable items to try again (`null` = nothing waits), with the title of their batch. */
    suspend fun retryBatch(): Pair<ServerRefs, String?>? = pending(KEY_RETRY)?.let { refsOf(it) to it.title }

    private suspend fun <T> quietly(block: suspend () -> T): T {
        val already = meta.get(SyncTriggers.APPLYING) != null
        if (!already) meta.put(MetaEntity(SyncTriggers.APPLYING, "1"))
        return try {
            block()
        } finally {
            if (!already) meta.delete(SyncTriggers.APPLYING)
        }
    }

    companion object {
        private const val TAG = "RevealHandoff"
        const val KEY_PENDING = "handoff.pendingServerRefs"
        const val KEY_RETRY = "handoff.retryServerRefs"
        private const val ROLE_RAW = "raw"
        private const val ROLE_RENDERED = "rendered"
        private const val FETCH_PARALLELISM = 4
        private const val SQL_ARGS = 900
        private const val FP_SUFFIX = 12
        private const val MAX_ORIENTATION = 8
        private val json = Json { ignoreUnknownKeys = true }
    }
}
