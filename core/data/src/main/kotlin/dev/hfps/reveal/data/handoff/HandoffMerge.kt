package dev.hfps.reveal.data.handoff

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.HandoffTwin
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncAliasEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.xmp.XmpFields

/** What one merge pass did: the folder photos whose rating, flag or label changed (their sidecars are written next). */
data class MergeReport(val merged: Int, val cullingChanged: List<Long>, val fingerprints: List<String>)

/**
 * One photo across sources (ADR 0011 "One photo across sources", ADR 0006 "Identity"): a hand-off photo whose file is
 * also a file of an added folder becomes that folder's photo. The folder is where the file lives, so its photo stays;
 * the hand-off photo goes away after giving it what it held:
 *
 * - **culling** ([resolve]): a field the folder photo does not have takes the hand-off's value, and so does a field the
 *   hand-off set after the folder's sidecar was last written (the hand-off is newer); the caller then writes the
 *   folder photo's sidecar as for any rating change;
 * - **edits**: the hand-off photo's versions move to the folder photo with their uuids, recipes and histories. When
 *   the folder photo was never touched (no edit, no history, unknown to the server) its empty version gives way;
 *   otherwise the hand-off's versions follow it (both edits are kept as versions, nothing is lost);
 * - **sync identity** (ADR 0018 "Identity"): a hand-off photo the server knows gives its uuid to a folder photo the
 *   server does not know; when the server knows both, the hand-off's uuid becomes an alias of the folder photo here and
 *   is pushed as a tombstone (other devices drop their Server copy of it and find the folder photo by fingerprint);
 * - "Opened from other apps" keeps showing the photo: the folder photo takes the hand-off time and title.
 *
 * Another folder or a Server-source photo with the same fingerprint is never merged into a folder: two folders are two
 * copies (ADR 0006).
 */
class HandoffMerge(private val db: RevealDatabase, private val clock: () -> Long = System::currentTimeMillis) {
    private val photos = db.photoDao()
    private val edits = db.editDao()
    private val sync = db.syncDao()

    /** Merges every hand-off photo that has a file of folder [folderSourceId] (by fingerprint) into that photo. */
    suspend fun mergeInto(folderSourceId: Long): MergeReport {
        val twins = photos.handoffTwins(folderSourceId)
        if (twins.isEmpty()) return MergeReport(0, emptyList(), emptyList())
        val changed = LinkedHashSet<Long>()
        val prints = ArrayList<String>()
        var merged = 0
        for ((handoffId, pairs) in twins.groupBy { it.handoffPhotoId }) {
            val folderId = chooseFolderPhoto(pairs)
            db.withTransaction {
                val handoff = photos.get(handoffId) ?: return@withTransaction
                val folder = photos.get(folderId) ?: return@withTransaction
                prints += photos.files(handoffId).mapNotNull { it.fingerprint }
                if (merge(handoff, folder)) changed += folderId
                merged++
            }
        }
        return MergeReport(merged, changed.toList(), prints.distinct())
    }

    /** The folder photo that holds the hand-off's main file (a RAW over its rendering), else the first match. */
    private fun chooseFolderPhoto(pairs: List<HandoffTwin>): Long = (
        pairs.firstOrNull { it.handoffRole == FileRole.RAW } ?: pairs.firstOrNull {
            it.handoffRole == FileRole.RENDERED
        } ?: pairs.first()
        ).folderPhotoId

    /** Moves [handoff] into [folder]; true when the folder photo's culling changed. */
    private suspend fun merge(handoff: PhotoEntity, folder: PhotoEntity): Boolean {
        val culling = resolve(
            folder = fieldsOf(folder),
            folderSidecarTime = folder.xmpModified.takeIf { folder.xmpDocumentId != null },
            handoff = fieldsOf(handoff),
            handoffTime = handoff.handoffAt,
        )
        val cullingChanged = culling != fieldsOf(folder)
        if (cullingChanged) photos.setCulling(folder.id, culling.rating, culling.label, culling.flag)
        moveVersions(handoff.id, folder.id)
        handoff.handoffAt?.let { photos.setHandoffAt(folder.id, it, handoff.handoffTitle) }
        val identity = moveIdentity(handoff, folder)
        // The hand-off row goes (its files, cloud state and leftover versions with it); its aliases now name the
        // folder photo.
        sync.moveAliases(handoff.id, folder.id)
        photos.deletePhotos(listOf(handoff.id))
        identity?.invoke()
        photos.setHasEdit(folder.id, edits.photoHasEdit(folder.id))
        return cullingChanged
    }

    /**
     * The hand-off's touched versions join the folder photo. The folder photo's own versions that nobody touched give
     * way when the hand-off brings an edit; a touched hand-off version is never dropped.
     */
    private suspend fun moveVersions(handoffId: Long, folderId: Long) {
        val incoming = photos.versions(handoffId).filterNot { untouched(it) }
        val leftover = photos.versions(handoffId).filter { untouched(it) }
        // An untouched hand-off version the server knows (pushed once) is gone with its photo: tell the server.
        for (version in leftover) tombstone(SyncRecordEntity.KIND_VERSION, version.uuid)
        if (incoming.isEmpty()) return
        val own = photos.versions(folderId)
        val kept = if (own.all { untouched(it) }) {
            own.forEach { version ->
                sync.deleteRecord(SyncRecordEntity.KIND_VERSION, version.uuid)
                photos.deleteVersion(version.id)
            }
            emptyList()
        } else {
            own
        }
        // After the folder photo's own versions (positions are unique per photo).
        var position = (kept.maxOfOrNull { it.position } ?: -1) + 1
        for (version in incoming) photos.moveVersion(version.id, folderId, position++)
    }

    /** A version nobody edited, that is no conflict copy and that the server never saw. */
    @Suppress("ReturnCount")
    private suspend fun untouched(version: VersionEntity): Boolean {
        if (version.copyOf != null) return false
        val record = sync.record(SyncRecordEntity.KIND_VERSION, version.uuid)
        if (record != null && record.rev > 0) return false
        val recipe = edits.recipe(version.id)
        return (recipe == null || recipe.isDefault) && edits.history(version.id).isEmpty()
    }

    /**
     * The sync identity after the merge; returns what must run once the hand-off row is gone (a uuid is unique).
     * Null when nothing is left to do then.
     */
    private suspend fun moveIdentity(handoff: PhotoEntity, folder: PhotoEntity): (suspend () -> Unit)? {
        val theirs = sync.record(SyncRecordEntity.KIND_PHOTO, handoff.uuid)
        val ours = sync.record(SyncRecordEntity.KIND_PHOTO, folder.uuid)
        return when {
            theirs == null || theirs.rev == 0L -> {
                // The server never heard of the hand-off photo: nothing to tell it.
                sync.deleteRecord(SyncRecordEntity.KIND_PHOTO, handoff.uuid)
                null
            }

            ours == null || ours.rev == 0L -> {
                // The server knows the photo under the hand-off's id only: the folder photo takes that id.
                sync.deleteRecord(SyncRecordEntity.KIND_PHOTO, folder.uuid)
                suspend {
                    sync.setPhotoUuid(folder.id, handoff.uuid)
                    sync.putRecord(theirs.copy(dirty = true, deleted = false, modifiedAt = clock()))
                }
            }

            else -> {
                // Both are on the server: one tombstone, and the old id still finds the photo here.
                sync.putRecord(theirs.copy(dirty = true, deleted = true, modifiedAt = clock()))
                suspend { sync.putAlias(SyncAliasEntity(handoff.uuid, folder.id)) }
            }
        }
    }

    private suspend fun tombstone(kind: String, id: String) {
        val record = sync.record(kind, id) ?: return
        if (record.rev > 0) {
            sync.putRecord(record.copy(dirty = true, deleted = true, modifiedAt = clock()))
        } else {
            sync.deleteRecord(kind, id)
        }
    }

    companion object {
        private fun fieldsOf(photo: PhotoEntity) =
            XmpFields(photo.rating.coerceIn(0, XmpFields.MAX_RATING), photo.label, photo.flag)

        /**
         * The culling of a folder photo that a hand-off photo merges into, field by field: the folder photo's value
         * when the hand-off has none; the hand-off's when the folder photo has none; otherwise the hand-off's only
         * when it was handed over after the folder's sidecar was written ([handoffTime] after [folderSidecarTime], or
         * the folder photo has no sidecar), else the folder's (someone rated it there since).
         */
        fun resolve(folder: XmpFields, folderSidecarTime: Long?, handoff: XmpFields, handoffTime: Long?): XmpFields {
            val newer = handoffTime != null && (folderSidecarTime == null || handoffTime > folderSidecarTime)
            fun <T> pick(theirs: T, ours: T, empty: T): T = when {
                theirs == empty -> ours
                ours == empty -> theirs
                newer -> theirs
                else -> ours
            }
            return XmpFields(
                rating = pick(handoff.rating, folder.rating, 0),
                label = pick(handoff.label, folder.label, null),
                flag = pick(handoff.flag, folder.flag, PickFlag.NONE),
            )
        }
    }
}
