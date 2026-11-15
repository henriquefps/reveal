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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
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
) {
    /** Two batches of one resumed hand-off as one: photos in order, counts summed, the first issue and initial. */
    operator fun plus(other: ServerBatch) = ServerBatch(
        photos = photos + other.photos,
        notFound = notFound + other.notFound,
        issue = issue ?: other.issue,
        initialVersionId = initialVersionId ?: other.initialVersionId,
    )
}

/** A batch kept for later (`meta`): [reason] says what it waits for. */
@Serializable
internal data class PendingRefs(
    val serverId: String,
    val title: String? = null,
    val items: List<PendingItem>,
    val initialItem: String? = null,
    val savedAt: Long = 0,
    /** The `handoff_at` of the batch's photos, kept so a retry does not move them under another header. */
    val at: Long? = null,
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
                keepBatch(refs, title, at ?: clock())
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

        data object Gone : Fetched

        data object Unreachable : Fetched
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private suspend fun resolvePaired(refs: ServerRefs, title: String?, refresh: Boolean, at: Long?): ServerBatch {
        val now = at ?: clock()
        val local = localHits(refs.items, refresh)
        val misses = refs.items.filter { it.itemId !in local }
        // The network part: a few requests at a time, nothing written yet.
        val fetched = fetchAll(misses)
        var notFound = 0
        val unreachable = ArrayList<ServerItemRef>()
        val byItem = LinkedHashMap<String, Long>()
        db.withTransaction {
            quietly {
                for ((ref, result) in fetched) {
                    when (result) {
                        Fetched.Missing, Fetched.Gone -> notFound++

                        Fetched.Unreachable -> {
                            val held = localPhotoFor(ref.allFingerprints, refresh)
                            if (held != null) {
                                // Unverifiable now: the photo Reveal has under the sender's fingerprint stands in.
                                byItem[ref.itemId] = held
                            } else if (ref.name != null && ref.fingerprint != null) {
                                // Open from what Reveal has: a placeholder that fills in once the server answers.
                                upsert(placeholder(ref), now)?.let { byItem[ref.itemId] = it }
                            }
                            unreachable += ref
                        }

                        is Fetched.Info -> {
                            // The item's own members decide (a replacement item names the shot now).
                            val info = result.info.withNames(ref)
                            val photoId = localPhotoFor(info.fingerprints, refresh) ?: upsert(info, now)
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
        settleUnreachable(refs, title, unreachable, now)
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

    /**
     * Photos already in the library for [items], by the item's own known members (`culling_files`, from earlier syncs).
     * The sender's fingerprint alone is never trusted here: Hoard may have sent a stale one, so such an item is read
     * from the server first (its members decide, [localPhotoFor]); only when the server cannot answer does the sender's
     * fingerprint stand in (the `Unreachable` branch).
     */
    private suspend fun localHits(items: List<ServerItemRef>, refresh: Boolean): Map<String, Hit> {
        val members = items.associate { ref -> ref.itemId to db.cullingDao().filesOf(ref.itemId) }
        val prints = members.values.flatten().distinct()
        val byPrint = prints.chunked(SQL_ARGS).flatMap { photos.filesByFingerprint(it) }.groupBy { it.file.fingerprint }
        val out = LinkedHashMap<String, Hit>()
        for (ref in items) {
            val known = members.getValue(ref.itemId)
            val match = known.firstNotNullOfOrNull { fp -> byPrint[fp]?.firstOrNull { reusable(it, refresh) } }
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

    /** The photo that holds one of [fingerprints] (a hidden one comes back, [reuse]). */
    private suspend fun localPhotoFor(fingerprints: List<String>, refresh: Boolean): Long? = fingerprints
        .chunked(SQL_ARGS).flatMap { photos.filesByFingerprint(it) }.firstOrNull { reusable(it, refresh) }
        ?.let { match ->
            if (match.photoHidden) photos.setHidden(listOf(match.file.photoId), false)
            match.file.photoId
        }

    /** `GET /v1/items/{id}` following one `replacedBy`; a tombstone without a replacement is gone. */
    private suspend fun fetchById(itemId: String): Fetched {
        var result = server.item(itemId)
        if (result is ServerItemResult.Gone && result.replacedBy != null) result = server.item(result.replacedBy)
        return when (result) {
            is ServerItemResult.Found -> Fetched.Info(result.item)
            is ServerItemResult.Gone -> Fetched.Gone
            ServerItemResult.Missing -> Fetched.Missing
            ServerItemResult.Unreachable -> Fetched.Unreachable
        }
    }

    /**
     * The items behind [refs] (a few requests at a time). An unknown id (404) falls back to the sender's fingerprints,
     * for all such refs in one lookup; a tombstone without a replacement is gone.
     */
    private suspend fun fetchAll(refs: List<ServerItemRef>): List<Pair<ServerItemRef, Fetched>> {
        val gate = Semaphore(FETCH_PARALLELISM)
        val first = coroutineScope {
            refs.map { ref -> async { ref to gate.withPermit { fetchById(ref.itemId) } } }.awaitAll()
        }
        val unknown = first.map { it.first }.filter { ref ->
            ref.allFingerprints.isNotEmpty() && first.any { it.first == ref && it.second == Fetched.Missing }
        }
        if (unknown.isEmpty()) return first.map { it.first to it.second.asMissing() }
        val found = server.lookup(unknown.flatMap { it.allFingerprints }.distinct())
        val second = coroutineScope {
            unknown.map { ref ->
                async {
                    val id = found?.let { f -> ref.allFingerprints.firstNotNullOfOrNull { f[it] } }
                    ref to when {
                        found == null -> Fetched.Unreachable
                        id == null -> Fetched.Missing
                        else -> gate.withPermit { fetchById(id) }
                    }
                }
            }.awaitAll()
        }.toMap()
        return first.map { (ref, result) -> ref to (second[ref] ?: result).asMissing() }
    }

    /** A tombstone without a replacement counts as not found. */
    private fun Fetched.asMissing(): Fetched = if (this == Fetched.Gone) Fetched.Missing else this

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

    /** Guards the read-modify-write of the two kept records (two `MainActivity`s may both reach them). */
    private val keep = Mutex()

    /**
     * Adds [group] to the record [key] (one list of groups): a group of the same server and (non-null) title is the
     * same batch, so the members of a cascade add their items instead of replacing the earlier ones.
     */
    private suspend fun keepGroup(key: String, group: PendingRefs) = keep.withLock {
        val groups = groups(key).toMutableList()
        val at = groups.indexOfFirst { it.sameBatch(group) }
        if (at < 0) {
            groups += group
        } else {
            val old = groups[at]
            groups[at] = old.copy(
                items = (old.items + group.items).distinctBy { it.itemId },
                initialItem = old.initialItem ?: group.initialItem,
                at = old.at ?: group.at,
                savedAt = group.savedAt,
            )
        }
        saveGroups(key, groups.takeLast(MAX_GROUPS))
    }

    private fun PendingRefs.sameBatch(other: PendingRefs) =
        title != null && serverId == other.serverId && title == other.title

    private suspend fun keepBatch(refs: ServerRefs, title: String?, at: Long) = keepGroup(
        KEY_PENDING,
        PendingRefs(refs.serverId, title, refs.items.map(PendingItem::of), refs.initialItem, clock(), at),
    )

    private suspend fun groups(key: String): List<PendingRefs> {
        val text = meta.get(key) ?: return emptyList()
        return runCatching { json.decodeFromString(ListSerializer(PendingRefs.serializer()), text) }.getOrNull()
            ?: runCatching { listOf(json.decodeFromString(PendingRefs.serializer(), text)) }.getOrDefault(emptyList())
    }

    private suspend fun saveGroups(key: String, groups: List<PendingRefs>) {
        if (groups.isEmpty()) {
            meta.delete(key)
        } else {
            meta.put(MetaEntity(key, json.encodeToString(ListSerializer(PendingRefs.serializer()), groups)))
        }
    }

    /** The batches that wait for a pairing. */
    internal suspend fun pendingGroups(): List<PendingRefs> = groups(KEY_PENDING)

    /** Takes the batches waiting for [serverId], atomically: of two callers only one gets them. */
    internal suspend fun takePending(serverId: String): List<PendingRefs> = keep.withLock {
        val all = groups(KEY_PENDING)
        val mine = all.filter { it.serverId == serverId }
        if (mine.isNotEmpty()) saveGroups(KEY_PENDING, all - mine.toSet())
        mine
    }

    suspend fun clearPending() = keep.withLock { meta.delete(KEY_PENDING) }

    internal fun refsOf(p: PendingRefs) = ServerRefs(p.serverId, p.items.map(PendingItem::toRef), p.initialItem)

    /**
     * What waits for the server to answer after [refs] resolved with [unreachable] items: the resolved ones leave the
     * record (whatever batch they were kept under), the unreachable ones join their batch's group.
     */
    private suspend fun settleUnreachable(
        refs: ServerRefs,
        title: String?,
        unreachable: List<ServerItemRef>,
        at: Long,
    ) = keep.withLock {
        val resolved = refs.items.map { it.itemId }.toSet() - unreachable.map { it.itemId }.toSet()
        val groups = groups(KEY_RETRY).map { g ->
            if (g.serverId == refs.serverId) g.copy(items = g.items.filter { it.itemId !in resolved }) else g
        }.filter { it.items.isNotEmpty() }
        saveGroups(KEY_RETRY, groups)
        if (unreachable.isNotEmpty()) {
            val group = PendingRefs(refs.serverId, title, unreachable.map(PendingItem::of), null, clock(), at)
            val all = groups.toMutableList()
            val same = all.indexOfFirst { it.sameBatch(group) }
            if (same < 0) {
                all += group
            } else {
                all[same] = all[same].copy(items = (all[same].items + group.items).distinctBy { it.itemId })
            }
            saveGroups(KEY_RETRY, all.takeLast(MAX_GROUPS))
        }
    }

    /** The unreachable items to try again (empty = nothing waits), by batch. */
    internal suspend fun retryGroups(): List<PendingRefs> = groups(KEY_RETRY)

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
        private const val MAX_GROUPS = 50
        private const val FETCH_PARALLELISM = 4
        private const val SQL_ARGS = 900
        private const val FP_SUFFIX = 12
        private const val MAX_ORIENTATION = 8
        private val json = Json { ignoreUnknownKeys = true }
    }
}
