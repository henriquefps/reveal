@file:Suppress("ReturnCount")

package dev.hfps.reveal.sync.engine

import android.util.Log
import androidx.room.withTransaction
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncDeleteEntity
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.data.handoff.HandoffMerge
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.sync.ServerTree
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** What removing a folder did to the server's view of its photos (logs and tests). */
data class FolderRemoval(
    /** Photos now in this device's Server scope (their original or preview is on the server). */
    val keptOnServer: Int = 0,
    /** Photo records tombstoned (deleted from the server, or metadata only and unedited). */
    val tombstoned: Int = 0,
    /** Files queued for `DELETE /v1/originals` (every file of every photo deleted from the server). */
    val originalsQueued: Int = 0,
)

/** What the one-off clean-up of 0.5.3 did; counts only (it is logged). */
data class CleanupReport(
    val aliases: Int = 0,
    val duplicates: Int = 0,
    val metadataOnly: Int = 0,
    val merged: Int = 0,
)

/**
 * The library side of the server's view (ADR 0018 "Deleting from the server", FIX/V): what happens to a folder's photos
 * on the server when the folder is removed, "Delete from server" in the Server scope, and the one-off clean-up of the
 * records 0.5.2 left behind. Everything is local: tombstones go up with the next push, originals are deleted by
 * `BlobUploader.deleteOriginals` after it. Writes run with the change triggers quiet ([SyncTriggers.APPLYING]): every sync row they need
 * is written explicitly.
 */
class ServerLibrary(
    private val db: RevealDatabase,
    private val store: SyncStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val sync = db.syncDao()
    private val photos = db.photoDao()
    private val meta = db.metaDao()

    /** How many photos of folder [sourceId] have an edit (the removal dialog's default). */
    suspend fun editedCount(sourceId: Long): Int = sync.editedInSource(sourceId)

    /**
     * Prepares the removal of folder [sourceId] (call before the folder itself is forgotten):
     *
     * - [deleteFromServer]: every photo's records are tombstoned and its uploaded originals queued for deletion (its
     *   preview goes with the tombstone, ADR 0017);
     * - otherwise a photo whose original (any file of the shot) or smart preview is on the server moves to this
     *   device's Server source with its id, edits and ratings (so it stays visible and editable here); one with an edit
     *   but nothing on the server moves there too, unlisted (`PhotoQuerySql.SERVER_OPENABLE_SQL`), so its records
     *   stay known here and merge when the folder is added again or the original reaches the server;
     * - in both cases a photo with no edit and nothing on the server has its records tombstoned: they only described a
     *   file that was here.
     */
    suspend fun removeFolder(sourceId: Long, deleteFromServer: Boolean): FolderRemoval = quietly {
        var kept = 0
        var tombstoned = 0
        val deletes = LinkedHashSet<String>()
        for (photo in sync.photosOfSource(sourceId)) {
            val files = photos.files(photo.id)
            val cloud = sync.cloud(photo.id)
            val onServer = cloud?.previewSha != null || serverFiles(files, cloud).isNotEmpty()
            when {
                deleteFromServer -> {
                    if (tombstone(photo)) tombstoned++
                    deletes += files.mapNotNull { it.fingerprint }
                }

                onServer || photo.hasEdit -> {
                    relink(photo, files, cloud)
                    if (onServer) kept++
                }

                !photo.hasEdit -> if (tombstone(photo)) tombstoned++
            }
        }
        queue(deletes)
        FolderRemoval(kept, tombstoned, deletes.size)
    }.also { Log.i(TAG, "folder removed: $it") }

    /**
     * "Delete from server" (the Server scope's selection): the photos' records are tombstoned (their edits go too, on
     * every device), their uploaded originals queued for deletion and the photos leave this device. Returns how many
     * were deleted.
     */
    suspend fun deleteFromServer(photoIds: List<Long>): Int = quietly {
        val server = sync.serverSource()?.id ?: return@quietly 0
        val deletes = LinkedHashSet<String>()
        val gone = ArrayList<Long>()
        for (photo in photoIds.chunked(SQL_ARGS).flatMap { sync.photosById(it) }) {
            if (photo.sourceId != server) continue
            val files = photos.files(photo.id)
            tombstone(photo)
            deletes += files.mapNotNull { it.fingerprint }
            gone += photo.id
        }
        queue(deletes)
        gone.chunked(SQL_ARGS).forEach { photos.deletePhotos(it) }
        gone.size
    }

    /**
     * The fingerprints of [files] whose original the server can serve as far as this device knows: a file a lookup or
     * the NAS feed marked, and the main file when the photo's lookup found it. (Deleting asks about every file of the
     * shot instead: another device or app may have uploaded one this device never asked about; the server answers
     * `404` for none, keeps one a live record names and never deletes a NAS file.)
     */
    private suspend fun serverFiles(files: List<PhotoFileEntity>, cloud: PhotoCloudEntity?): List<String> {
        val main = SyncStore.mainFile(files)
        return files.filter { it.onServer || (it.id == main?.id && cloud?.originalOnServer == true) }
            .mapNotNull { it.fingerprint }
    }

    private suspend fun queue(fingerprints: Collection<String>) {
        if (fingerprints.isEmpty()) return
        val now = clock()
        sync.queueDeletes(fingerprints.map { SyncDeleteEntity(it, now) })
    }

    /** Tombstones the photo's record, its aliases and its versions; true when the server knew the photo. */
    private suspend fun tombstone(photo: PhotoEntity): Boolean {
        var known = tombstone(Kinds.PHOTO, photo.uuid)
        for (alias in sync.aliasesOf(photo.id)) known = tombstone(Kinds.PHOTO, alias) || known
        for (version in sync.versionsOf(photo.id)) tombstone(Kinds.VERSION, version.uuid)
        return known
    }

    private suspend fun tombstone(kind: String, id: String): Boolean {
        val record = sync.record(kind, id) ?: return false
        if (record.rev > 0) {
            if (!record.deleted) sync.putRecord(record.copy(dirty = true, deleted = true, modifiedAt = clock()))
            return true
        }
        sync.deleteRecord(kind, id)
        return false
    }

    /**
     * Moves a folder photo to the Server source (its id, versions, edits, ratings and sync rows stay): its files are
     * served by the server from now on, by fingerprint. Files without one cannot be, and go.
     */
    private suspend fun relink(photo: PhotoEntity, files: List<PhotoFileEntity>, cloud: PhotoCloudEntity?) {
        val server = store.serverSource()
        val (keep, drop) = files.partition { it.fingerprint != null }
        if (drop.isNotEmpty()) sync.deleteFileRows(drop.map { it.id })
        val main = SyncStore.mainFile(keep)
        sync.updateFileRows(
            keep.map { file ->
                val fingerprint = checkNotNull(file.fingerprint)
                file.copy(
                    sourceId = server.id,
                    relativePath = "photo/${photo.uuid}/${fingerprint.takeLast(FP_SUFFIX)}/${file.name}",
                    documentId = ServerTree.documentId(fingerprint, file.name),
                    unreadableAt = null,
                    onServer = file.onServer || (file.id == main?.id && cloud?.originalOnServer == true),
                )
            },
        )
        sync.updatePhotoRow(
            photo.copy(
                sourceId = server.id,
                folder = "",
                folderDocumentId = "",
                stemKey = photo.uuid,
                xmpDocumentId = null,
                xmpModified = null,
            ),
        )
        // For a Server photo "original on the server" means any file of the shot (FIX/V).
        val any = keep.any { it.onServer } || cloud?.originalOnServer == true
        sync.putCloud((cloud ?: PhotoCloudEntity(photo.id)).copy(originalOnServer = any))
        sync.cancelUploads(photo.id)
    }

    // ------------------------------------------------------------------ the one-off clean-up (0.5.3)

    /**
     * Before the first pull of the clean-up: remembers the live photo records this device holds a row for but no photo
     * (0.5.2 forgot a removed folder's photos and its own records never came back past the pull cursor). The pull then
     * starts from the beginning, so they are placed again.
     */
    suspend fun prepareCleanup() = db.withTransaction {
        val orphans = sync.livePhotoRecordIds().filter { sync.photoByUuid(it) == null && sync.alias(it) == null }
        meta.put(MetaEntity(KEY_ORPHANS, SyncStoreJson.encodeToString(ListSerializer(String.serializer()), orphans)))
    }

    /**
     * The one-off clean-up after the re-pull and the lookups (idempotent; counts only in the log):
     *
     * - a Server photo with the same file as a folder photo merges into it (ADR 0011 rules, [HandoffMerge]);
     * - Server photos with the same main file: the unedited ones give way to one kept photo (an edited one if any);
     * - records remembered by [prepareCleanup] (this device's own, whose photo it lost):
     *   - placed again as another id of a photo here (the folder was added again under new ids): tombstoned, and the
     *     photo's versions are pushed again under the kept id (an edit made under the old id stays);
     *   - a Server photo with no edit and nothing on the server: tombstoned (it only described a photo this device no
     *     longer has).
     *
     * Another device's aliases are never touched: two ids for one file are legitimate across devices (ADR 0018
     * "Identity").
     */
    suspend fun cleanUp(): CleanupReport {
        val merged = mergeServerTwins()
        val report = quietly {
            val (aliases, metadataOnly) = dropOrphans()
            CleanupReport(aliases = aliases, duplicates = dropDuplicates(), metadataOnly = metadataOnly)
        }.copy(merged = merged)
        meta.delete(KEY_ORPHANS)
        Log.i(TAG, "clean-up: $report")
        return report
    }

    /** Server photos whose file a folder of this device holds become that folder's photos (requirement: no dupes). */
    suspend fun mergeServerTwins(): Int {
        val merge = HandoffMerge(db, clock)
        var merged = 0
        for (source in db.sourceDao().all()) {
            if (source.kind == SourceKind.FOLDER && source.state == SourceState.AVAILABLE) {
                merged += merge.mergeServerPhotosInto(source.id).merged
            }
        }
        return merged
    }

    /** Tombstones an own record placed again as another id of local photo [photoId]; its versions follow the kept id. */
    private suspend fun dropAlias(uuid: String, photoId: Long): Boolean {
        if (!tombstone(Kinds.PHOTO, uuid)) return false
        for (version in sync.versionsOf(photoId)) {
            val record = sync.record(Kinds.VERSION, version.uuid) ?: continue
            if (record.rev > 0 && !record.deleted && !record.dirty) {
                sync.putRecord(record.copy(dirty = true, modifiedAt = clock()))
            }
        }
        return true
    }

    private suspend fun dropDuplicates(): Int {
        val server = sync.serverSource()?.id ?: return 0
        var dropped = 0
        for (fingerprint in sync.duplicateServerFingerprints()) {
            val twins = sync.photosWithMainFile(fingerprint).filter { it.sourceId == server }
            val keep = twins.firstOrNull { it.hasEdit } ?: twins.firstOrNull() ?: continue
            for (twin in twins) {
                if (twin.id == keep.id || twin.hasEdit) continue
                tombstone(twin)
                photos.deletePhotos(listOf(twin.id))
                dropped++
            }
        }
        return dropped
    }

    /** The own records [prepareCleanup] remembered: (aliases dropped, metadata-only records dropped). */
    private suspend fun dropOrphans(): Pair<Int, Int> {
        val text = meta.get(KEY_ORPHANS) ?: return 0 to 0
        val orphans = runCatching {
            SyncStoreJson.decodeFromString(ListSerializer(String.serializer()), text)
        }.getOrDefault(emptyList())
        val server = sync.serverSource()?.id
        var aliases = 0
        var dropped = 0
        for (uuid in orphans) {
            val alias = sync.alias(uuid)
            if (alias != null) {
                if (dropAlias(uuid, alias)) aliases++
                continue
            }
            val photo = sync.photoByUuid(uuid) ?: continue
            if (photo.sourceId != server || photo.hasEdit) continue
            val cloud = sync.cloud(photo.id)
            // Not asked yet (the lookup failed): decided by a later clean-up, never guessed.
            if (cloud == null || cloud.checkedAt == 0L) continue
            if (cloud.originalOnServer || cloud.previewSha != null) continue
            tombstone(photo)
            photos.deletePhotos(listOf(photo.id))
            dropped++
        }
        return aliases to dropped
    }

    private suspend fun <T> quietly(block: suspend () -> T): T = db.withTransaction {
        val already = meta.get(SyncTriggers.APPLYING) != null
        if (!already) meta.put(MetaEntity(SyncTriggers.APPLYING, "1"))
        try {
            block()
        } finally {
            if (!already) meta.delete(SyncTriggers.APPLYING)
        }
    }

    companion object {
        private const val TAG = "RevealSync"
        private const val SQL_ARGS = 500
        private const val FP_SUFFIX = 12

        /** Live photo records without a photo here, remembered for the clean-up (a JSON list of ids). */
        const val KEY_ORPHANS = "sync.cleanup.orphans"
        private val SyncStoreJson = kotlinx.serialization.json.Json
    }
}
