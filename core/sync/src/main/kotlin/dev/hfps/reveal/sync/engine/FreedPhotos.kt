@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.engine

import android.util.Log
import androidx.room.withTransaction
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.sync.BackupCoverage
import dev.hfps.reveal.data.sync.BackupRoot
import dev.hfps.reveal.data.sync.StorageLocation
import dev.hfps.reveal.sync.api.ItemFileRecord
import dev.hfps.reveal.sync.api.ItemLocationRecord
import dev.hfps.reveal.sync.api.ItemRecord
import dev.hfps.reveal.sync.api.ServerInfo
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** What one pass over the vanished folder photos did (logs and tests). */
data class FreedReport(val adopted: Int = 0, val deleted: Int = 0, val kept: Int = 0)

/**
 * Folder photos whose files vanished from the phone (ADR 0018 "R1", Hoard ADR 0024 item 6, "freed-adopt"): a walk
 * keeps them, unlisted (`photo_files.vanished_at`), and this decides with the server's word:
 *
 * - its item is found by fingerprint when Reveal has one, else by its mirror location (`GET /v1/items?folder=<root>&
 *   path=<dir>`: the location whose volume and path are the file's, confirmed by size and `modifiedAt`);
 * - adopted by the Server source in place ([SyncStore.adoptFreed]) when the location says `gone: "freed"`, or the
 *   photo carries an edit and the file left the phone otherwise (`deleted`, an unknown reason, or no location);
 * - kept waiting when the server's mirror still sees it on the phone (a walk that missed it); the walk's bound (7 days)
 *   applies;
 * - otherwise deleted, as before R1.
 *
 * A server without `backup` cannot have freed anything: every waiting photo gets the old rule at once.
 */
class FreedPhotos(private val db: RevealDatabase, private val store: SyncStore) {
    private val sync = db.syncDao()
    private val photos = db.photoDao()

    suspend fun resolve(api: SyncApi, info: ServerInfo): FreedReport {
        val waiting = sync.vanishedFiles().groupBy { it.photoId }
        if (waiting.isEmpty()) return FreedReport()
        if (!info.has(SyncEngine.FEATURE_BACKUP)) return FreedReport(deleted = delete(waiting.keys))
        val roots = BackupCoverage.decode(db.metaDao().get(BackupCoverage.META_ROOTS))
        val candidates = waiting.mapNotNull { (photoId, files) -> candidate(photoId, files) }
        val found = HashMap<Long, Match>()
        byLocation(api, roots, candidates, found)
        byFingerprint(api, candidates.filter { it.photo.id !in found }, found)
        var adopted = 0
        val deletes = ArrayList<Long>()
        var kept = 0
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            when (decide(candidate, found[candidate.photo.id])) {
                Decision.ADOPT -> if (adopt(candidate, checkNotNull(found[candidate.photo.id]))) {
                    adopted++
                } else {
                    deletes += candidate.photo.id
                }

                Decision.KEEP -> kept++

                Decision.DELETE -> deletes += candidate.photo.id
            }
        }
        // Photos whose source or row went meanwhile have nothing left to decide.
        val gone = waiting.keys - candidates.map { it.photo.id }.toSet()
        val report = FreedReport(adopted, delete(deletes + gone), kept)
        if (report != FreedReport()) log("vanished folder photos: $report")
        return report
    }

    /** A waiting photo with where its files were on the phone (null: not an `ExternalStorageProvider` tree). */
    private class Candidate(
        val photo: PhotoEntity,
        val files: List<PhotoFileEntity>,
        val folder: StorageLocation?,
        val edited: Boolean,
    ) {
        val main: PhotoFileEntity get() = SyncStore.mainFile(files) ?: files.first()
    }

    /** The item of a candidate, and the mirror location of its main file when the item has one. */
    private class Match(val item: ItemRecord, val location: ItemLocationRecord?)

    private enum class Decision { ADOPT, KEEP, DELETE }

    private suspend fun candidate(photoId: Long, files: List<PhotoFileEntity>): Candidate? {
        val photo = photos.get(photoId) ?: return null
        val source = db.sourceDao().get(photo.sourceId) ?: return null
        val base = BackupCoverage.location(source.treeUri, source.rootDocumentId)
        return Candidate(photo, files, base?.child(photo.folder), photos.isEdited(photoId))
    }

    private fun decide(candidate: Candidate, match: Match?): Decision {
        match ?: return Decision.DELETE
        val location = match.location
        return when {
            // The phone still has it as far as Hoard's mirror knows: this walk missed it; ask again later.
            location?.onDevice == true -> Decision.KEEP

            location?.gone == GONE_FREED -> Decision.ADOPT

            // Deleted (or an unknown reason, or no mirror location): kept only for its edit.
            candidate.edited -> Decision.ADOPT

            else -> Decision.DELETE
        }
    }

    /**
     * The folder queries: one paged `GET /v1/items?folder=<root>&path=<dir>` per directory of waiting photos under any
     * root of this phone (stopped and paused ones too: their entries stay), matched by fingerprint or mirror location.
     */
    private suspend fun byLocation(
        api: SyncApi,
        roots: List<BackupRoot>,
        candidates: List<Candidate>,
        found: MutableMap<Long, Match>,
    ) {
        val groups = candidates.mapNotNull { c ->
            val folder = c.folder ?: return@mapNotNull null
            val root = roots.firstOrNull { BackupCoverage.contains(it, folder) } ?: return@mapNotNull null
            Triple(root, relative(root, folder), c)
        }.groupBy({ it.first to it.second }, { it.third })
        for ((key, group) in groups) {
            val (root, dir) = key
            val items = folderItems(api, root.id, dir) ?: continue
            for (candidate in group) match(candidate, items)?.let { found[candidate.photo.id] = it }
        }
    }

    /** Every page of a folder query; null when the server refused it (the fingerprint lookup still runs). */
    private suspend fun folderItems(api: SyncApi, rootId: String, dir: String): List<ItemRecord>? {
        val items = ArrayList<ItemRecord>()
        var cursor: String? = null
        var pages = 0
        try {
            do {
                currentCoroutineContext().ensureActive()
                val page = api.items(rootId, dir, cursor, PAGE)
                items += page.items
                cursor = page.cursor
            } while (page.hasMore && cursor != null && ++pages < MAX_PAGES)
        } catch (e: SyncHttpException) {
            if (e.transient || e.unauthorized) throw e
            log("folder query refused (${e.code})")
            return null
        }
        return items
    }

    /** The photos with a fingerprint the folder queries did not find: `POST /v1/items/lookup`, then each item. */
    private suspend fun byFingerprint(api: SyncApi, candidates: List<Candidate>, found: MutableMap<Long, Match>) {
        val prints = candidates.flatMap { c -> c.files.mapNotNull { it.fingerprint } }.distinct()
        if (prints.isEmpty()) return
        val itemOf = HashMap<String, String>()
        try {
            for (part in prints.chunked(LOOKUP)) {
                for (hit in api.itemsLookup(part).matches) itemOf[hit.fingerprint] = hit.itemId
            }
        } catch (e: SyncHttpException) {
            if (e.transient || e.unauthorized) throw e
            log("item lookup refused (${e.code})")
            return
        }
        readItems(api, candidates, itemOf, found)
    }

    /** Reads each candidate's item (at most [MAX_ITEM_READS] per pass) and matches it. */
    private suspend fun readItems(
        api: SyncApi,
        candidates: List<Candidate>,
        itemOf: Map<String, String>,
        found: MutableMap<Long, Match>,
    ) {
        val items = HashMap<String, ItemRecord?>()
        for (candidate in candidates) {
            val id = candidate.files.firstNotNullOfOrNull { f -> f.fingerprint?.let(itemOf::get) }
            val readable = id != null && (id in items || items.size < MAX_ITEM_READS)
            val item = if (readable) items.getOrPut(id) { api.item(id)?.takeIf { !it.deleted } } else null
            item?.let { match(candidate, listOf(it)) }?.let { found[candidate.photo.id] = it }
        }
    }

    /** The item of [candidate] among [items]: one with a member of its fingerprint, else its mirror location. */
    private fun match(candidate: Candidate, items: List<ItemRecord>): Match? {
        val main = candidate.main
        val path = candidate.folder?.child(main.name)
        val byPrint = (listOf(main) + (candidate.files - main)).mapNotNull { it.fingerprint }
            .firstNotNullOfOrNull { print -> items.firstOrNull { item -> item.files.any { it.fingerprint == print } } }
        if (byPrint != null) return Match(byPrint, path?.let { mirrorOf(byPrint, it, null) })
        if (path == null) return null
        return items.firstNotNullOfOrNull { item -> mirrorOf(item, path, main)?.let { Match(item, it) } }
    }

    /**
     * The mirror location of [path] in [item]; with [confirm] (a file Reveal knows only by its path), only when the
     * member's size and the location's time are the file's (MediaStore keeps seconds: compared to the second).
     */
    private fun mirrorOf(item: ItemRecord, path: StorageLocation, confirm: PhotoFileEntity?): ItemLocationRecord? {
        for (file in item.files) {
            val location = file.locations.firstOrNull { isAt(it, path) } ?: continue
            if (confirm == null || confirms(file, location, confirm)) return location
        }
        return null
    }

    private fun isAt(location: ItemLocationRecord, path: StorageLocation): Boolean =
        location.source == SOURCE_MIRROR && location.volume.equals(path.volume, ignoreCase = true) &&
            location.path.trim('/') == path.path

    private fun confirms(file: ItemFileRecord, location: ItemLocationRecord, local: PhotoFileEntity): Boolean {
        val time = location.modifiedAt ?: return false
        return file.size == local.size && time / SECOND == local.modified / SECOND
    }

    /** Adopts [candidate] with its item's fingerprints for the files it can name (by fingerprint, else by path). */
    private suspend fun adopt(candidate: Candidate, match: Match): Boolean {
        val files = HashMap<Long, String>()
        for (local in candidate.files) {
            val print = local.fingerprint?.takeIf { p -> match.item.files.any { it.fingerprint == p } }
                ?: candidate.folder?.child(local.name)?.let { path ->
                    match.item.files.firstOrNull { f -> f.locations.any { isAt(it, path) } }?.fingerprint
                }
                ?: match.item.files.firstOrNull { it.name.equals(local.name, ignoreCase = true) }?.fingerprint
            if (print != null) files[local.id] = print
        }
        val stored = match.item.files.flatMap { it.locations }.map { it.source }
        val original = if (SyncStore.SOURCE_NAS in stored) SyncStore.SOURCE_NAS else SyncStore.SOURCE_BACKUP
        val folder = candidate.folder?.path ?: candidate.photo.folder
        return store.adoptFreed(candidate.photo.id, files, folder, original)
    }

    /** Today's rule for [photoIds]: their files go, then every folder photo left without one. */
    private suspend fun delete(photoIds: Collection<Long>): Int {
        if (photoIds.isEmpty()) return 0
        var removed = 0
        db.withTransaction {
            val sources = HashSet<Long>()
            for (ids in photoIds.chunked(SQL_ARGS)) {
                for (photo in photos.getAll(ids)) {
                    sources += photo.sourceId
                    photos.deleteFiles(photos.files(photo.id).map { it.id })
                }
            }
            for (source in sources) removed += photos.deleteEmptyPhotos(source)
        }
        return removed
    }

    private fun relative(root: BackupRoot, folder: StorageLocation): String {
        val base = root.path.trim('/')
        val path = folder.path.trim('/')
        return if (base.isEmpty()) path else path.removePrefix(base).trimStart('/')
    }

    private fun log(message: String) {
        runCatching { Log.i(TAG, message) }
    }

    private companion object {
        const val TAG = "RevealSync"
        const val GONE_FREED = "freed"
        const val SOURCE_MIRROR = "mirror"
        const val PAGE = 500
        const val MAX_PAGES = 200
        const val LOOKUP = 1000
        const val MAX_ITEM_READS = 500
        const val SQL_ARGS = 900
        const val SECOND = 1000L
    }
}
