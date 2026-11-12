package dev.hfps.reveal.data.preset

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.PresetEntity
import dev.hfps.reveal.data.db.PresetGroupEntity
import dev.hfps.reveal.data.db.PresetMarkEntity
import dev.hfps.reveal.data.db.PresetPackEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetIds
import dev.hfps.reveal.pipeline.preset.PresetLimits
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.SmartPart
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** What an import did. */
sealed interface ImportResult {
    /** A preset was added (or updated to a newer [Preset.version]); [ignored] lists patch fields this app skipped. */
    data class PresetAdded(val uuid: String, val name: String, val updated: Boolean, val ignored: List<String>) :
        ImportResult

    /** The file holds a preset already in the library at the same or a newer version: nothing changed. */
    data class AlreadyThere(val uuid: String, val name: String) : ImportResult

    /** A pack was added on trial (or updated, keeping its trial state); [packRowId] for Keep / Remove. */
    data class PackAdded(
        val packRowId: Long,
        val name: String,
        val presets: Int,
        val updated: Boolean,
        val ignored: List<String>,
    ) : ImportResult
}

/**
 * The preset library (docs/adr/0019 "Library"): the user's presets and groups, imported packs, favourites and
 * recents, plus the built-in presets of [builtIns]. Every write is one transaction; the sync triggers mark the
 * changed presets.
 */
class PresetRepository(
    private val db: RevealDatabase,
    private val coverDir: File,
    private val builtIns: BuiltInPresetSource = BuiltInPresetSource.NONE,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.presetDao()

    /** Decoded patches by their JSON: a library emission (a mark changed) does not re-validate every patch. */
    private val patches = HashMap<String, PresetPatch?>()

    // ------------------------------------------------------------------ read

    /** The library, kept current. */
    fun library(): Flow<PresetLibrary> = combine(
        dao.observeGroups(),
        dao.observePresets(),
        dao.observePacks(),
        dao.observeMarks(),
    ) { groups, presets, packs, marks -> build(groups, presets, packs, marks) }.distinctUntilChanged()

    /** A preset by id (database or built-in), or null. */
    suspend fun preset(uuid: String): Preset? =
        dao.presetByUuid(uuid)?.let(::decode) ?: builtIns.groups().flatMap { it.presets }.firstOrNull { it.id == uuid }

    private fun build(
        groups: List<PresetGroupEntity>,
        presets: List<PresetEntity>,
        packs: List<PresetPackEntity>,
        marks: List<PresetMarkEntity>,
    ): PresetLibrary {
        val markOf = marks.associateBy { it.uuid }
        val packOf = packs.associateBy { it.id }
        val byGroup = presets.groupBy { it.groupId }
        val builtIn = builtIns.groups().map { group ->
            LibraryGroup(
                uuid = group.uuid,
                rowId = null,
                name = group.name,
                kind = GroupKind.BUILT_IN,
                presets = group.presets.map { preset ->
                    val mark = markOf[preset.id]
                    LibraryPreset(
                        preset,
                        null,
                        group.uuid,
                        PresetOrigin.BUILT_IN,
                        mark?.favourite == true,
                        mark?.lastUsedAt,
                    )
                },
            )
        }
        val stored = groups.map { group ->
            val pack = group.packId?.let(packOf::get)?.let(::packInfo)
            LibraryGroup(
                uuid = group.uuid,
                rowId = group.id,
                name = group.name,
                kind = if (pack != null) GroupKind.PACK else GroupKind.USER,
                pack = pack,
                presets = byGroup[group.id].orEmpty().mapNotNull { row ->
                    val preset = cached(row) ?: return@mapNotNull null
                    val mark = markOf[row.uuid]
                    LibraryPreset(
                        preset,
                        row.id,
                        group.uuid,
                        origin(row.origin),
                        mark?.favourite == true,
                        mark?.lastUsedAt,
                    )
                },
            )
        }
        return PresetLibrary(builtIn + stored)
    }

    private fun cached(row: PresetEntity): Preset? {
        val patch = synchronized(patches) { patches.getOrPut(row.patchJson) { decodePatch(row.patchJson) } }
        return patch?.let { decode(row, it) }
    }

    private fun packInfo(pack: PresetPackEntity) = PackInfo(
        rowId = pack.id,
        uuid = pack.uuid,
        name = pack.name,
        author = pack.author,
        version = pack.version,
        licenceName = pack.licenceName,
        licenceText = pack.licenceText,
        link = pack.link,
        cover = pack.coverFile?.let { File(coverDir, it) }?.takeIf { it.isFile },
        trial = pack.trial,
    )

    // ------------------------------------------------------------------ user presets

    /**
     * Saves a new preset named [name] that sets [patch] into the user group [groupId] (null = the default group,
     * created when missing). Returns its uuid.
     */
    suspend fun create(name: String, patch: PresetPatch, groupId: Long?, author: String = ""): String =
        db.withTransaction {
            val group = groupId?.let { dao.group(it) }?.takeIf { it.packId == null } ?: defaultGroup()
            val now = clock()
            val uuid = PresetIds.newId()
            dao.insertPreset(
                PresetEntity(
                    uuid = uuid,
                    groupId = group.id,
                    name = cleanName(name),
                    author = author.take(PresetLimits.NAME),
                    version = 1,
                    createdAt = now,
                    updatedAt = now,
                    position = dao.nextPresetPosition(group.id),
                    patchJson = patch.toJson().toString(),
                    smartJson = null,
                    origin = PresetOrigin.USER.name,
                ),
            )
            uuid
        }

    /** Renames a user preset. */
    suspend fun rename(uuid: String, name: String) = db.withTransaction {
        val row = dao.presetByUuid(uuid) ?: return@withTransaction
        dao.updatePreset(row.copy(name = cleanName(name), updatedAt = clock()))
    }

    /** Moves a preset to the end of the user group [groupId]; its old group is removed if it is left empty. */
    suspend fun move(uuid: String, groupId: Long) = db.withTransaction {
        val row = dao.presetByUuid(uuid) ?: return@withTransaction
        val group = dao.group(groupId)?.takeIf { it.packId == null } ?: return@withTransaction
        if (row.groupId == group.id) return@withTransaction
        dao.updatePreset(row.copy(groupId = group.id, position = dao.nextPresetPosition(group.id), updatedAt = clock()))
        dropIfEmpty(row.groupId)
    }

    /** Orders the presets of one group as [uuids] (presets not listed keep their place after them). */
    suspend fun reorder(uuids: List<String>) = db.withTransaction {
        uuids.forEachIndexed { index, uuid ->
            val row = dao.presetByUuid(uuid) ?: return@forEachIndexed
            if (row.position != index) dao.updatePreset(row.copy(position = index, updatedAt = clock()))
        }
    }

    /** A copy of a preset (a new id, "name 2" style naming is the caller's) in the same user group or the default. */
    suspend fun duplicate(uuid: String, name: String): String? = db.withTransaction {
        val source = preset(uuid) ?: return@withTransaction null
        val row = dao.presetByUuid(uuid)
        val group = row?.let { dao.group(it.groupId) }?.takeIf { it.packId == null } ?: defaultGroup()
        val now = clock()
        val copy = PresetIds.newId()
        dao.insertPreset(
            PresetEntity(
                uuid = copy,
                groupId = group.id,
                name = cleanName(name),
                author = source.author,
                version = 1,
                createdAt = now,
                updatedAt = now,
                position = dao.nextPresetPosition(group.id),
                patchJson = source.patch.toJson().toString(),
                smartJson = source.smart?.rules?.toString(),
                origin = PresetOrigin.USER.name,
            ),
        )
        copy
    }

    /** Deletes a preset (a pack's too) and its marks; an emptied user group goes with it. */
    suspend fun delete(uuid: String) = db.withTransaction {
        val row = dao.presetByUuid(uuid)
        dao.deletePresetByUuid(uuid)
        dao.deleteMark(uuid)
        row?.let { dropIfEmpty(it.groupId) }
    }

    // ------------------------------------------------------------------ groups

    /** Creates a user group at the end; returns its row id. */
    suspend fun createGroup(name: String): Long = db.withTransaction {
        dao.insertGroup(
            PresetGroupEntity(name = cleanName(name), position = dao.nextGroupPosition(), createdAt = clock()),
        )
    }

    suspend fun renameGroup(groupId: Long, name: String) = db.withTransaction {
        val group = dao.group(groupId)?.takeIf { it.packId == null } ?: return@withTransaction
        dao.updateGroup(group.copy(name = cleanName(name)))
    }

    /** Orders the groups as [groupIds]. */
    suspend fun reorderGroups(groupIds: List<Long>) = db.withTransaction {
        groupIds.forEachIndexed { index, id ->
            val group = dao.group(id) ?: return@forEachIndexed
            if (group.position != index) dao.updateGroup(group.copy(position = index))
        }
    }

    /** Deletes a group with its presets (a pack's group too; the pack goes when its last group does). */
    suspend fun deleteGroup(groupId: Long) = db.withTransaction {
        val group = dao.group(groupId) ?: return@withTransaction
        for (row in dao.presetsIn(groupId)) dao.deleteMark(row.uuid)
        // Delete the presets first so each leaves its sync tombstone (a cascade would too, but explicitly is clearer).
        for (row in dao.presetsIn(groupId)) dao.deletePreset(row.id)
        dao.deleteGroup(groupId)
        val packId = group.packId ?: return@withTransaction
        if (dao.groups().none { it.packId == packId }) removePackRow(packId)
    }

    // ------------------------------------------------------------------ marks

    suspend fun setFavourite(uuid: String, favourite: Boolean) = db.withTransaction {
        val mark = dao.mark(uuid) ?: PresetMarkEntity(uuid)
        dao.putMark(mark.copy(favourite = favourite))
    }

    /** Records that [uuid] was just applied (the Recent row). */
    suspend fun markUsed(uuid: String) = db.withTransaction {
        val mark = dao.mark(uuid) ?: PresetMarkEntity(uuid)
        dao.putMark(mark.copy(lastUsedAt = clock()))
    }

    // ------------------------------------------------------------------ import / export

    /**
     * Imports a `.revealpreset` file: into the user group named by the file (created when missing; the default group
     * when it names none). A preset already here is updated only when the file's version is higher.
     */
    suspend fun importPreset(bytes: ByteArray): ImportResult {
        val decoded = PresetFiles.decode(bytes)
        val preset = decoded.preset
        return db.withTransaction {
            val existing = dao.presetByUuid(preset.id)
            when {
                existing == null -> {
                    val group = if (preset.group.isBlank()) defaultGroup() else userGroup(preset.group)
                    insert(preset, group.id, PresetOrigin.IMPORTED)
                    ImportResult.PresetAdded(preset.id, preset.name, updated = false, ignored = decoded.ignored)
                }

                preset.version > existing.version -> {
                    dao.updatePreset(updated(existing, preset))
                    ImportResult.PresetAdded(preset.id, preset.name, updated = true, ignored = decoded.ignored)
                }

                else -> ImportResult.AlreadyThere(existing.uuid, existing.name)
            }
        }
    }

    /**
     * Imports a `.revealpack` from [input] **on trial** (docs/adr/0019 "Import and export"): its groups and presets
     * show in the panel, its cover is stored, and [keepPack] or [removePack] ends the trial. A newer version of a pack
     * already here replaces its groups and presets and keeps its trial state; the same or an older one changes nothing.
     */
    suspend fun importPack(input: InputStream): ImportResult {
        val pack = PresetPacks.read(input)
        return db.withTransaction {
            val existing = dao.packByUuid(pack.id)
            if (existing != null && existing.version >= pack.version) {
                return@withTransaction ImportResult.PackAdded(
                    existing.id,
                    existing.name,
                    dao.groups().filter { it.packId == existing.id }
                        .sumOf { dao.countIn(it.id) },
                    updated = false,
                    ignored = pack.ignored,
                )
            }
            val trial = existing?.trial ?: true
            existing?.let { removePackRow(it.id, keepCover = false) }
            val cover = pack.cover?.let { writeCover(pack.id, it) }
            val packId = dao.insertPack(
                PresetPackEntity(
                    uuid = pack.id,
                    name = pack.name,
                    author = pack.author,
                    version = pack.version,
                    licenceName = pack.licenceName,
                    licenceText = pack.licenceText,
                    link = pack.link,
                    coverFile = cover,
                    trial = trial,
                    importedAt = clock(),
                ),
            )
            insertPackContent(pack, packId)
            ImportResult.PackAdded(packId, pack.name, pack.presets.size, updated = existing != null, pack.ignored)
        }
    }

    /** Keeps a pack tried on (its presets start syncing). */
    suspend fun keepPack(packRowId: Long) = db.withTransaction {
        val pack = dao.pack(packRowId) ?: return@withTransaction
        if (pack.trial) dao.updatePack(pack.copy(trial = false))
    }

    /** Removes a pack with its groups, presets, marks and cover. */
    suspend fun removePack(packRowId: Long) = db.withTransaction { removePackRow(packRowId) }

    /** A preset as a `.revealpreset` file. */
    suspend fun export(uuid: String): ByteArray? = preset(uuid)?.let(PresetFiles::encode)

    /**
     * A user group as a `.revealpack` (for sharing a set of one's own presets): [licenceName] is shown to whoever
     * imports it; [author] fills the pack's author.
     */
    @Suppress("ReturnCount")
    suspend fun exportGroup(groupId: Long, author: String, licenceName: String): ByteArray? {
        val group = dao.group(groupId) ?: return null
        val presets = dao.presetsIn(groupId).mapNotNull(::decode)
        if (presets.isEmpty()) return null
        val packGroup = PackGroup(group.uuid, group.name.ifBlank { licenceName })
        val pack = PresetPack(
            id = PresetIds.newId(),
            name = packGroup.name,
            author = author.take(PresetLimits.NAME),
            version = 1,
            licenceName = licenceName,
            licenceText = "",
            link = null,
            groups = listOf(packGroup),
            presets = presets.map { it.copy(group = packGroup.name) },
            groupOf = presets.associate { it.id to group.uuid },
            cover = null,
        )
        return ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()
    }

    // ------------------------------------------------------------------ internals (inside a transaction)

    /** The default user group (empty name, shown as "My presets"), created first in the list when missing. */
    private suspend fun defaultGroup(): PresetGroupEntity = dao.userGroupNamed("") ?: run {
        val id = dao.insertGroup(PresetGroupEntity(name = "", position = dao.nextGroupPosition(), createdAt = clock()))
        checkNotNull(dao.group(id))
    }

    private suspend fun userGroup(name: String): PresetGroupEntity = dao.userGroupNamed(name) ?: run {
        val id = dao.insertGroup(
            PresetGroupEntity(name = cleanName(name), position = dao.nextGroupPosition(), createdAt = clock()),
        )
        checkNotNull(dao.group(id))
    }

    private suspend fun insert(preset: Preset, groupId: Long, origin: PresetOrigin) {
        val now = clock()
        dao.insertPreset(
            PresetEntity(
                uuid = preset.id,
                groupId = groupId,
                name = preset.name,
                author = preset.author,
                version = preset.version,
                createdAt = preset.createdAt.takeIf { it > 0 } ?: now,
                updatedAt = now,
                position = dao.nextPresetPosition(groupId),
                patchJson = preset.patch.toJson().toString(),
                smartJson = preset.smart?.rules?.toString(),
                origin = origin.name,
            ),
        )
    }

    private fun updated(row: PresetEntity, preset: Preset) = row.copy(
        name = preset.name,
        author = preset.author,
        version = preset.version,
        updatedAt = clock(),
        patchJson = preset.patch.toJson().toString(),
        smartJson = preset.smart?.rules?.toString(),
    )

    private suspend fun insertPackContent(pack: PresetPack, packId: Long) {
        val groupRows = pack.groups.associate { group ->
            // A pack group's uuid may already be taken by an unrelated row (a hostile pack): give it a new one.
            val uuid = if (dao.groupByUuid(group.id) == null) group.id else PresetIds.newId()
            group.id to dao.insertGroup(
                PresetGroupEntity(
                    uuid = uuid,
                    name = group.name,
                    position = dao.nextGroupPosition(),
                    packId = packId,
                    createdAt = clock(),
                ),
            )
        }
        for (preset in pack.presets) {
            val groupId = groupRows.getValue(pack.groupOf.getValue(preset.id))
            // A preset id already in the library (the user's, or another pack's) stays where it is.
            if (dao.presetByUuid(preset.id) == null) insert(preset, groupId, PresetOrigin.PACK)
        }
    }

    private suspend fun removePackRow(packRowId: Long, keepCover: Boolean = false) {
        val pack = dao.pack(packRowId) ?: return
        for (group in dao.groups().filter { it.packId == packRowId }) {
            for (row in dao.presetsIn(group.id)) {
                dao.deleteMark(row.uuid)
                dao.deletePreset(row.id)
            }
            dao.deleteGroup(group.id)
        }
        dao.deletePack(packRowId)
        if (!keepCover) pack.coverFile?.let { File(coverDir, it).delete() }
    }

    private suspend fun dropIfEmpty(groupId: Long) {
        val group = dao.group(groupId) ?: return
        // The default group stays (it is where "Create preset" puts presets); named user groups go when emptied.
        if (group.packId == null && group.name.isNotEmpty()) dao.deleteGroupIfEmpty(groupId)
    }

    private fun writeCover(packUuid: String, bytes: ByteArray): String {
        coverDir.mkdirs()
        val name = packUuid + if (bytes.size > 1 && bytes[1] == 'P'.code.toByte()) ".png" else ".jpg"
        File(coverDir, name).writeBytes(bytes)
        return name
    }

    private fun cleanName(name: String): String =
        name.trim().filterNot { it.isISOControl() }.take(PresetLimits.NAME).ifEmpty { "-" }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The [Preset] of a row; null when it cannot be read (written by a newer app). */
        fun decode(row: PresetEntity): Preset? = decodePatch(row.patchJson)?.let { decode(row, it) }

        private fun decodePatch(text: String): PresetPatch? = runCatching {
            PresetPatch.fromJson(json.parseToJsonElement(text) as JsonObject).patch
        }.getOrNull()

        private fun decode(row: PresetEntity, patch: PresetPatch): Preset? = runCatching {
            Preset(
                id = row.uuid,
                name = row.name,
                patch = patch,
                author = row.author,
                version = row.version,
                createdAt = row.createdAt,
                smart = row.smartJson?.let { SmartPart(json.parseToJsonElement(it) as JsonObject) },
            )
        }.getOrNull()

        fun origin(name: String): PresetOrigin =
            PresetOrigin.entries.firstOrNull { it.name == name } ?: PresetOrigin.USER
    }
}
