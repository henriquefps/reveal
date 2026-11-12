package dev.hfps.reveal.sync.engine

import dev.hfps.reveal.data.db.PresetEntity
import dev.hfps.reveal.data.db.PresetGroupEntity
import dev.hfps.reveal.data.db.PresetMarkEntity
import dev.hfps.reveal.data.db.PresetPackEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.preset.PresetOrigin
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetFormatException
import dev.hfps.reveal.sync.api.FeedRecord
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** The pack a synced preset came from (docs/adr/0019 "Sync"); the cover is not synced. */
@Serializable
data class PackData(
    val id: String,
    val name: String,
    val author: String = "",
    val version: Int = 1,
    val licenceName: String = "",
    val licenceText: String = "",
    val link: String? = null,
)

/**
 * `preset.data` (ADR 0017 "Records", docs/adr/0019 "Sync"): the `.revealpreset` object of the preset plus where it
 * lives in the library (its group by uuid, name and order, its place in the group), whether it is a favourite, its
 * origin, and its pack.
 */
@Serializable
data class PresetData(
    val preset: JsonElement,
    val groupId: String,
    val groupName: String = "",
    val groupPosition: Int = 0,
    val position: Int = 0,
    val favourite: Boolean = false,
    val origin: String = PresetOrigin.USER.name,
    val pack: PackData? = null,
)

/** Pushes and applies `preset` records (called by [SyncStore] inside its transactions). */
class PresetSync(private val db: RevealDatabase, private val clock: () -> Long) {
    private val presets = db.presetDao()
    private val json = SyncApi.json

    /**
     * The change to push for [record], or null when nothing is to be pushed (a preset gone before it ever reached the
     * server, or one of a pack still on trial: keeping the pack marks it again).
     */
    suspend fun change(record: SyncRecordEntity): PushChange? {
        if (record.deleted) {
            return if (record.rev == 0L) {
                null
            } else {
                PushChange(Kinds.PRESET, record.recordId, record.rev, record.modifiedAt, deleted = true)
            }
        }
        val row = presets.presetByUuid(record.recordId) ?: return null
        val group = presets.group(row.groupId) ?: return null
        val pack = group.packId?.let { presets.pack(it) }
        if (pack?.trial == true) return null
        val preset = PresetRepository.decode(row) ?: return null
        val data = PresetData(
            preset = PresetFiles.toJson(preset.copy(group = group.name)),
            groupId = group.uuid,
            groupName = group.name,
            groupPosition = group.position,
            position = row.position,
            favourite = presets.mark(row.uuid)?.favourite == true,
            origin = row.origin,
            pack = pack?.let {
                PackData(it.uuid, it.name, it.author, it.version, it.licenceName, it.licenceText, it.link)
            },
        )
        return PushChange(
            Kinds.PRESET,
            row.uuid,
            record.rev,
            record.modifiedAt,
            data = json.encodeToJsonElement(PresetData.serializer(), data),
        )
    }

    /** Applies a pulled preset (or its tombstone). Always placed: a preset depends on nothing else. */
    suspend fun apply(record: FeedRecord): Boolean {
        if (record.deleted) {
            delete(record.id)
            return true
        }
        val data = record.data?.let {
            runCatching { json.decodeFromJsonElement(PresetData.serializer(), it) }.getOrNull()
        } ?: return true
        val preset = try {
            (data.preset as? JsonObject)?.let { PresetFiles.fromJson(it).preset } ?: return true
        } catch (e: PresetFormatException) {
            // A preset this app cannot read (a newer format) is left for a newer app; never a crash.
            return true
        }
        if (preset.id != record.id) return true
        val packId = data.pack?.let { pack(it) }
        val group = group(data, packId)
        val existing = presets.presetByUuid(preset.id)
        val origin = if (data.pack != null) PresetOrigin.PACK else PresetOrigin.SYNC
        val row = PresetEntity(
            id = existing?.id ?: 0,
            uuid = preset.id,
            groupId = group.id,
            name = preset.name,
            author = preset.author,
            version = preset.version,
            createdAt = preset.createdAt.takeIf { it > 0 } ?: clock(),
            updatedAt = clock(),
            position = data.position,
            patchJson = preset.patch.toJson().toString(),
            smartJson = preset.smart?.rules?.toString(),
            origin = existing?.origin?.takeIf { it == PresetOrigin.USER.name } ?: origin.name,
        )
        if (existing == null) presets.insertPreset(row) else presets.updatePreset(row)
        if (existing != null && existing.groupId != group.id) dropIfEmpty(existing.groupId)
        val mark = presets.mark(preset.id)
        if ((mark?.favourite == true) != data.favourite) {
            presets.putMark((mark ?: PresetMarkEntity(preset.id)).copy(favourite = data.favourite))
        }
        return true
    }

    private suspend fun delete(uuid: String) {
        val row = presets.presetByUuid(uuid) ?: return
        presets.deletePreset(row.id)
        presets.deleteMark(uuid)
        dropIfEmpty(row.groupId)
    }

    private suspend fun dropIfEmpty(groupId: Long) {
        val group = presets.group(groupId) ?: return
        if (presets.countIn(groupId) > 0) return
        val packId = group.packId
        if (packId != null) {
            presets.deleteGroup(groupId)
            if (presets.groups().none { it.packId == packId }) presets.deletePack(packId)
        } else if (group.name.isNotEmpty()) {
            presets.deleteGroup(groupId)
        }
    }

    private suspend fun pack(data: PackData): Long {
        val existing = presets.packByUuid(data.id)
        val row = PresetPackEntity(
            id = existing?.id ?: 0,
            uuid = data.id,
            name = data.name,
            author = data.author,
            version = data.version,
            licenceName = data.licenceName,
            licenceText = data.licenceText,
            link = data.link,
            coverFile = existing?.coverFile,
            trial = false,
            importedAt = existing?.importedAt ?: clock(),
        )
        return if (existing == null) presets.insertPack(row) else row.id.also { presets.updatePack(row) }
    }

    private suspend fun group(data: PresetData, packId: Long?): PresetGroupEntity {
        val existing = presets.groupByUuid(data.groupId)
        if (existing != null) {
            if (existing.name != data.groupName || existing.position != data.groupPosition) {
                presets.updateGroup(existing.copy(name = data.groupName, position = data.groupPosition))
            }
            return checkNotNull(presets.group(existing.id))
        }
        val id = presets.insertGroup(
            PresetGroupEntity(
                uuid = data.groupId,
                name = data.groupName,
                position = data.groupPosition,
                packId = packId,
                createdAt = clock(),
            ),
        )
        return checkNotNull(presets.group(id))
    }
}
