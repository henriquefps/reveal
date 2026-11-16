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
class FreedPhotos(
    private val db: RevealDatabase,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = db.syncDao()
    private val photos = db.photoDao()
    private val meta = db.metaDao()

    /**
     * Decides with [info], the paired server's `GET /v1/server` answered in this very run (never a stored features
     * list): a server that did not answer never gets here, so its photos keep waiting (the walk's 7-day bound). Only a
     * complete answer deletes: a refused or truncated query, the per-pass read cap, a photo backing off, all keep the
     * photo for a later pass.
     */
    suspend fun resolve(api: SyncApi, info: ServerInfo): FreedReport {
        val waiting = sync.vanishedFiles().groupBy { it.photoId }
        if (waiting.isEmpty()) return FreedReport()
        val expected = waiting.mapValues { (_, files) -> files.map { it.id }.toSet() }
        if (!info.has(SyncEngine.FEATURE_BACKUP)) return FreedReport(deleted = delete(expected))
        val roots = BackupCoverage.decode(meta.get(BackupCoverage.META_ROOTS))
        val mine = Owner(roots.map { it.id }.toSet(), meta.get(SyncEngine.KEY_PHYSICAL_DEVICE))
        val now = clock()
        // Oldest asked first (the query's order), so photos without an answer never starve the per-pass read cap.
        val all = waiting.mapNotNull { (photoId, files) -> candidate(photoId, files) }
        // Photos asked about recently without an answer to act on wait (1 h doubling to 6 h; 6 h when the mirror saw
        // them on the phone): no page walk or item read for them on every sync.
        val resting = all.filter { resting(it.files.first(), now) }
        val candidates = all - resting.toSet()
        val answers = Answers()
        byLocation(api, roots, mine, candidates, answers)
        byFingerprint(api, mine, candidates.filter { it.photo.id !in answers.found }, answers)
        var adopted = 0
        var kept = resting.size
        val deletes = HashMap<Long, Set<Long>>()
        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()
            val id = candidate.photo.id
            val retries = candidate.files.first().vanishRetries
            when (decide(candidate, answers.found[id], id in answers.answered, mine)) {
                Decision.ADOPT -> if (adopt(candidate, checkNotNull(answers.found[id]), mine)) {
                    adopted++
                } else {
                    kept++
                    sync.vanishAsked(id, now, retries + 1)
                }

                Decision.RECHECK -> {
                    kept++
                    sync.vanishAsked(id, now, MAX_RETRIES)
                }

                Decision.KEEP -> {
                    kept++
                    sync.vanishAsked(id, now, retries + 1)
                }

                Decision.DELETE -> deletes[id] = expected.getValue(id)
            }
        }
        // Photos whose source or row went meanwhile have nothing left to decide.
        val gone = waiting.keys - all.map { it.photo.id }.toSet()
        for (id in gone) deletes[id] = expected.getValue(id)
        val report = FreedReport(adopted, delete(deletes), kept)
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
        val printed: Boolean get() = files.any { it.fingerprint != null }
    }

    /** The item of a candidate, and the mirror location of its main file when the item has one. */
    private class Match(val item: ItemRecord, val location: ItemLocationRecord?)

    /** What the server said this pass: the matches, and the photos it fully answered for (no item = really none). */
    private class Answers {
        val found = HashMap<Long, Match>()
        val answered = HashSet<Long>()
    }

    /** This phone in mirror locations: its roots (any state) and its physical device (Hoard ADR 0024). */
    private class Owner(val rootIds: Set<String>, val physical: String?) {
        fun owns(location: ItemLocationRecord): Boolean =
            location.rootId in rootIds || (physical != null && location.physicalDeviceId == physical)
    }

    private enum class Decision { ADOPT, KEEP, RECHECK, DELETE }

    private suspend fun candidate(photoId: Long, files: List<PhotoFileEntity>): Candidate? {
        val photo = photos.get(photoId) ?: return null
        val source = db.sourceDao().get(photo.sourceId) ?: return null
        val base = BackupCoverage.location(source.treeUri, source.rootDocumentId)
        return Candidate(photo, files, base?.child(photo.folder), photos.isEdited(photoId))
    }

    /** Whether [file]'s photo was asked about too recently to be asked again ([vanishAsked]'s backoff). */
    private fun resting(file: PhotoFileEntity, now: Long): Boolean {
        val asked = file.vanishAskedAt ?: return false
        val wait = (FIRST_RETRY_MS shl (file.vanishRetries - 1).coerceIn(0, MAX_RETRIES)).coerceAtMost(BACKOFF_MS)
        return asked > now - wait
    }

    private fun decide(candidate: Candidate, match: Match?, answered: Boolean, mine: Owner): Decision {
        val decision = decideMatch(candidate, match, answered)
        // Before this phone's physical device is known (its first device report), whose mirror location is whose cannot
        // be judged: nothing is deleted then.
        return if (decision == Decision.DELETE && mine.physical == null) Decision.KEEP else decision
    }

    private fun decideMatch(candidate: Candidate, match: Match?, answered: Boolean): Decision {
        // No item: deleted only when the server fully answered that it has none; any gap keeps it for a later pass.
        match ?: return if (answered) Decision.DELETE else Decision.KEEP
        val location = match.location
        return when {
            // The phone still has it as far as Hoard's mirror knows: this walk missed it; asked again later.
            location?.onDevice == true -> Decision.RECHECK

            location?.gone == GONE_FREED -> Decision.ADOPT

            // Deleted (or an unknown reason, or no mirror location of this phone): kept only for its edit.
            candidate.edited -> Decision.ADOPT

            else -> Decision.DELETE
        }
    }

    /**
     * The folder queries: one paged `GET /v1/items?folder=<root>&path=<dir>` per directory that has waiting photos
     * under a root of this phone (stopped and paused ones too: their entries stay), matched by fingerprint or mirror
     * location. A complete listing answers for the photos Reveal knows only by path; a photo in no root of this phone
     * and without a fingerprint is answered too (nothing on the server can name it).
     */
    private suspend fun byLocation(
        api: SyncApi,
        roots: List<BackupRoot>,
        mine: Owner,
        candidates: List<Candidate>,
        answers: Answers,
    ) {
        val placed = candidates.map { c ->
            c to
                c.folder?.let { f -> roots.firstOrNull { BackupCoverage.contains(it, f) } }
        }
        for ((candidate, root) in placed) {
            if (root == null && !candidate.printed) answers.answered += candidate.photo.id
        }
        val groups = placed.filter { it.second != null }
            .groupBy({ (c, root) ->
                checkNotNull(root) to relative(checkNotNull(root), checkNotNull(c.folder))
            }, { it.first })
        for ((key, group) in groups) {
            val (root, dir) = key
            val listing = folderItems(api, root.id, dir) ?: continue
            for (candidate in group) {
                match(candidate, listing.items, mine)?.let { answers.found[candidate.photo.id] = it }
                if (listing.complete && !candidate.printed) answers.answered += candidate.photo.id
            }
        }
    }

    private class Listing(val items: List<ItemRecord>, val complete: Boolean)

    /** Every page of a folder query (complete = read to its end); null when the server refused it. */
    private suspend fun folderItems(api: SyncApi, rootId: String, dir: String): Listing? {
        val items = ArrayList<ItemRecord>()
        var cursor: String? = null
        var pages = 0
        var more: Boolean
        try {
            do {
                currentCoroutineContext().ensureActive()
                val page = api.items(rootId, dir, cursor, PAGE)
                items += page.items
                cursor = page.cursor
                more = page.hasMore && cursor != null
            } while (more && ++pages < MAX_PAGES)
        } catch (e: SyncHttpException) {
            if (e.transient || e.unauthorized) throw e
            log("folder query refused (${e.code})")
            return null
        }
        return Listing(items, complete = !more)
    }

    /**
     * The photos with a fingerprint the folder queries did not find: `POST /v1/items/lookup`, then each item. A photo
     * is answered when the lookup named no item for its files, or its item was read (gone or not matching).
     */
    private suspend fun byFingerprint(api: SyncApi, mine: Owner, candidates: List<Candidate>, answers: Answers) {
        val printed = candidates.filter { it.printed }
        val prints = printed.flatMap { c -> c.files.mapNotNull { it.fingerprint } }.distinct()
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
        val items = HashMap<String, ItemRecord?>()
        for (candidate in printed) {
            val id = candidate.files.firstNotNullOfOrNull { f -> f.fingerprint?.let(itemOf::get) }
            when {
                id == null -> answers.answered += candidate.photo.id

                // The read cap of a pass: the rest waits for the next one.
                id !in items && items.size >= MAX_ITEM_READS -> Unit

                else -> {
                    val item = items.getOrPut(id) { api.item(id)?.takeIf { !it.deleted } }
                    answers.answered += candidate.photo.id
                    item?.let { match(candidate, listOf(it), mine) }?.let { answers.found[candidate.photo.id] = it }
                }
            }
        }
    }

    /** The item of [candidate] among [items]: one with a member of its fingerprint, else its mirror location. */
    private fun match(candidate: Candidate, items: List<ItemRecord>, mine: Owner): Match? {
        val main = candidate.main
        val path = candidate.folder?.child(main.name)
        val byPrint = (listOf(main) + (candidate.files - main)).mapNotNull { it.fingerprint }
            .firstNotNullOfOrNull { print -> items.firstOrNull { item -> item.files.any { it.fingerprint == print } } }
        if (byPrint != null) return Match(byPrint, path?.let { mirrorOf(byPrint, it, null, mine) })
        if (path == null) return null
        return items.firstNotNullOfOrNull { item -> mirrorOf(item, path, main, mine)?.let { Match(item, it) } }
    }

    /**
     * This phone's mirror location of [path] in [item] (another phone's at the same path is never this file); with
     * [confirm] (a file Reveal knows only by its path), only when the member's size and the location's time are the
     * file's (MediaStore keeps seconds: compared to the second).
     */
    private fun mirrorOf(
        item: ItemRecord,
        path: StorageLocation,
        confirm: PhotoFileEntity?,
        mine: Owner,
    ): ItemLocationRecord? {
        for (file in item.files) {
            val location = file.locations.firstOrNull { isAt(it, path) && mine.owns(it) } ?: continue
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
    private suspend fun adopt(candidate: Candidate, match: Match, mine: Owner): Boolean {
        val files = HashMap<Long, String>()
        for (local in candidate.files) {
            val print = local.fingerprint?.takeIf { p -> match.item.files.any { it.fingerprint == p } }
                ?: candidate.folder?.child(local.name)?.let { path ->
                    match.item.files.firstOrNull { f -> f.locations.any { isAt(it, path) && mine.owns(it) } }
                        ?.fingerprint
                }
                ?: match.item.files.firstOrNull { it.name.equals(local.name, ignoreCase = true) }?.fingerprint
            if (print != null) files[local.id] = print
        }
        val stored = match.item.files.flatMap { it.locations }.map { it.source }
        val original = if (SyncStore.SOURCE_NAS in stored) SyncStore.SOURCE_NAS else SyncStore.SOURCE_BACKUP
        val folder = candidate.folder?.path ?: candidate.photo.folder
        val expected = candidate.files.map { it.id }.toSet()
        return store.adoptFreed(candidate.photo.id, files, folder, original, expected)
    }

    /**
     * Today's rule for [photos]' files (photo id -> the vanished file ids read at the start of the pass): each photo
     * only if it still has exactly those files, all still vanished (a walk may have listed it again meanwhile), then
     * every folder photo left without a file.
     */
    private suspend fun delete(expected: Map<Long, Set<Long>>): Int {
        if (expected.isEmpty()) return 0
        var removed = 0
        db.withTransaction {
            val sources = HashSet<Long>()
            for (ids in expected.keys.chunked(SQL_ARGS)) {
                for (photo in photos.getAll(ids)) {
                    val files = photos.files(photo.id)
                    if (!SyncStore.stillVanished(files, expected.getValue(photo.id))) continue
                    sources += photo.sourceId
                    photos.deleteFiles(files.map { it.id })
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

    companion object {
        private const val TAG = "RevealSync"
        private const val GONE_FREED = "freed"
        private const val SOURCE_MIRROR = "mirror"
        private const val PAGE = 500
        private const val MAX_PAGES = 200
        private const val LOOKUP = 1000

        /** Item reads per pass; photos past it are decided by a later pass, never deleted for want of an answer. */
        const val MAX_ITEM_READS = 500
        private const val SQL_ARGS = 900
        private const val SECOND = 1000L

        /**
         * The longest wait before asking about a photo again: one the mirror still sees on the phone waits this long,
         * one without an answer to act on waits [FIRST_RETRY_MS] doubling up to it.
         */
        const val BACKOFF_MS = 6L * 60 * 60 * 1000
        const val FIRST_RETRY_MS = 60L * 60 * 1000
        private const val MAX_RETRIES = 4
    }
}
