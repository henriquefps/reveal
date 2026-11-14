package dev.hfps.reveal.data.edit

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SnapshotEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.RecipeGroup
import dev.hfps.reveal.pipeline.recipe.StepLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * One version of a photo as the version list shows it (docs/adr/0025): the first version ([position] 0, the photo's
 * own), a virtual copy ([name] "Copy 1", renamable), or a sync conflict copy ([conflict], ADR 0018).
 */
data class VersionInfo(
    val id: Long,
    val uuid: String,
    val photoId: Long,
    val position: Int,
    val name: String?,
    val conflict: Boolean,
) {
    /** A virtual copy the user made (not the photo's first version, not a conflict copy): renamable, deletable. */
    val isCopy: Boolean get() = position > 0 && !conflict
}

/** A snapshot as the list shows it. */
data class SnapshotInfo(val id: Long, val uuid: String, val versionId: Long, val name: String, val createdAt: Long)

/** What a new virtual copy starts from (docs/adr/0025 "Virtual copies"). */
enum class CopySource {
    /** The current edit of the version it is made from: its first history step is that recipe. */
    CURRENT,

    /** The photo as it opens, without an edit. */
    ORIGINAL,
}

/**
 * Virtual copies and snapshots (docs/adr/0025). A virtual copy is a `versions` row (position > 0) of a photo with
 * its own recipe, history, snapshots, thumbnail and exports; culling stays the photo's (one XMP sidecar per file).
 * A snapshot is a named recipe of one version, applied as one history step. Every write marks the sync records through
 * the triggers ([dev.hfps.reveal.data.db.SyncTriggers]); a deleted version that reached the server becomes a
 * tombstone here, as the conflict chooser's deletes do.
 */
@Suppress("TooManyFunctions")
class VersionRepository(
    private val db: RevealDatabase,
    private val edits: EditRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val versions = db.versionDao()
    private val photos = db.photoDao()
    private val sync = db.syncDao()

    // ------------------------------------------------------------------ versions

    suspend fun versions(photoId: Long): List<VersionInfo> = versions.versionsOf(photoId).map(::info)

    fun observeVersions(photoId: Long): Flow<List<VersionInfo>> =
        versions.observeVersionsOf(photoId).map { list -> list.map(::info) }.distinctUntilChanged()

    suspend fun version(versionId: Long): VersionInfo? = versions.version(versionId)?.let(::info)

    /**
     * Makes a virtual copy of [versionId]'s photo, after its last version, named [nameFor] of the first number no
     * version uses yet ("Copy 1", "Copy 2", ...). From [CopySource.CURRENT] its history starts with one
     * [HistoryAction.VIRTUAL_COPY] step holding [versionId]'s committed recipe (none when unedited). Returns the
     * new version's id, or null when [versionId] is gone.
     */
    suspend fun createCopy(versionId: Long, source: CopySource, nameFor: (Int) -> String): Long? = db.withTransaction {
        val from = versions.version(versionId) ?: return@withTransaction null
        val siblings = versions.versionsOf(from.photoId)
        val names = siblings.mapNotNullTo(HashSet()) { it.name }
        var number = siblings.count { it.copyOf == null }.coerceAtLeast(1)
        while (nameFor(number) in names) number++
        val now = clock()
        val id = versions.insertVersion(
            VersionEntity(
                photoId = from.photoId,
                position = siblings.maxOf { it.position } + 1,
                name = nameFor(number),
                createdAt = now,
                editDevice = EditRepository.THIS_DEVICE,
                editedAt = now,
            ),
        )
        if (source == CopySource.CURRENT) {
            val recipe = edits.recipe(versionId)
            val changes = RecipeDiff.between(Recipe(), recipe)
            if (changes.isNotEmpty()) {
                val groups = RecipeGroup.entries.filter { group -> changes.any { it.field.group == group } }
                edits.commit(id, recipe, StepLabel(changes, HistoryAction.VIRTUAL_COPY, groups))
            }
        }
        id
    }

    /** [createCopy] for every version of [versionIds] (the library's selection), in one transaction. */
    suspend fun createCopies(versionIds: Collection<Long>, source: CopySource, nameFor: (Int) -> String): List<Long> =
        db.withTransaction { versionIds.mapNotNull { createCopy(it, source, nameFor) } }

    /** Renames a virtual copy; a blank name keeps the current one. */
    suspend fun rename(versionId: Long, name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isEmpty()) return
        versions.rename(versionId, trimmed)
    }

    /**
     * Deletes [versionId] with its recipe, history and snapshots, unless it is its photo's last version (a photo always
     * has one): returns false then. The next version moves up when the first one goes (the grid shows a photo by its
     * position 0). A version the server knows becomes a tombstone for the other devices.
     */
    suspend fun delete(versionId: Long): Boolean = db.withTransaction {
        val version = versions.version(versionId) ?: return@withTransaction false
        val siblings = versions.versionsOf(version.photoId)
        if (siblings.size <= 1) return@withTransaction false
        val record = sync.record(KIND_VERSION, version.uuid)
        versions.deleteVersion(versionId)
        if (version.position == 0) {
            val next = siblings.first { it.id != versionId }
            versions.setPosition(next.id, 0)
        }
        if (record != null && record.rev > 0) {
            sync.putRecord(record.copy(dirty = true, deleted = true, modifiedAt = clock()))
        } else {
            sync.deleteRecord(KIND_VERSION, version.uuid)
        }
        photos.setHasEdit(version.photoId, db.editDao().photoHasEdit(version.photoId))
        true
    }

    // ------------------------------------------------------------------ snapshots

    suspend fun snapshots(versionId: Long): List<SnapshotInfo> = versions.snapshots(versionId).map(::info)

    fun observeSnapshots(versionId: Long): Flow<List<SnapshotInfo>> =
        versions.observeSnapshots(versionId).map { list -> list.map(::info) }.distinctUntilChanged()

    /** Saves [recipe] as a snapshot of [versionId] named [name] (trimmed; blank = [fallbackName]). Returns its id. */
    suspend fun createSnapshot(versionId: Long, name: String, recipe: Recipe, fallbackName: String = ""): Long =
        versions.insertSnapshot(
            SnapshotEntity(
                versionId = versionId,
                name = name.trim().take(MAX_NAME).ifEmpty { fallbackName },
                recipeJson = RecipeCodec.Default.encode(recipe),
                createdAt = clock(),
            ),
        )

    suspend fun renameSnapshot(snapshotId: Long, name: String) {
        val trimmed = name.trim().take(MAX_NAME)
        if (trimmed.isNotEmpty()) versions.renameSnapshot(snapshotId, trimmed)
    }

    suspend fun deleteSnapshot(snapshotId: Long) = versions.deleteSnapshot(snapshotId)

    /** The snapshot's recipe (through the codec's migrators), or null when it is gone or from a newer app. */
    @Suppress("ReturnCount")
    suspend fun snapshotRecipe(snapshotId: Long): Pair<SnapshotInfo, Recipe>? {
        val row = versions.snapshot(snapshotId) ?: return null
        val recipe = runCatching { RecipeCodec.Default.decode(row.recipeJson) }.getOrNull() ?: return null
        return info(row) to recipe
    }

    /**
     * Applies a snapshot to its version outside the editor: one [HistoryAction.SNAPSHOT] step named after it (none
     * when the recipe is already the snapshot's). The editor applies it through its own history instead.
     */
    @Suppress("ReturnCount")
    suspend fun applySnapshot(snapshotId: Long): Boolean {
        val (snapshot, recipe) = snapshotRecipe(snapshotId) ?: return false
        val current = edits.recipe(snapshot.versionId)
        val changes = RecipeDiff.between(current, recipe)
        if (changes.isEmpty()) return false
        val groups = RecipeGroup.entries.filter { group -> changes.any { it.field.group == group } }
        edits.commit(snapshot.versionId, recipe, StepLabel(changes, HistoryAction.SNAPSHOT, groups, snapshot.name))
        return true
    }

    private fun info(v: VersionEntity) = VersionInfo(v.id, v.uuid, v.photoId, v.position, v.name, v.copyOf != null)

    private fun info(s: SnapshotEntity) = SnapshotInfo(s.id, s.uuid, s.versionId, s.name, s.createdAt)

    companion object {
        /** Longest version or snapshot name kept. */
        const val MAX_NAME = 80
        private const val KIND_VERSION = "version"
    }
}
