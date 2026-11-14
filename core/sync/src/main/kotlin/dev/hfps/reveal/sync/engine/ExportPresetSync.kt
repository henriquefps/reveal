package dev.hfps.reveal.sync.engine

import dev.hfps.reveal.data.db.ExportPresetEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.db.SyncTriggers
import dev.hfps.reveal.sync.api.FeedRecord
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi

/**
 * Pushes and applies the user's export presets (docs/adr/0025 "Sync"): one `setting` record per preset, id
 * `export-preset.<uuid>`, so the server needs no new record kind; a deleted preset is a tombstone. Last write wins per
 * preset, as for every `setting`. Called by [SyncStore] inside its transactions.
 */
class ExportPresetSync(private val db: RevealDatabase, private val clock: () -> Long) {
    private val dao = db.versionDao()
    private val json = SyncApi.json

    /** The change to push for [record], or null when it is not an export preset or there is nothing to push. */
    @Suppress("ReturnCount")
    suspend fun change(record: SyncRecordEntity): PushChange? {
        val uuid = uuidOf(record.recordId) ?: return null
        if (record.deleted) {
            return if (record.rev == 0L) {
                null
            } else {
                PushChange(Kinds.SETTING, record.recordId, record.rev, record.modifiedAt, deleted = true)
            }
        }
        val row = dao.exportPresetByUuid(uuid) ?: return null
        val options = runCatching { json.parseToJsonElement(row.optionsJson) }.getOrNull() ?: return null
        val data = ExportPresetData(row.name, options, row.position)
        return PushChange(
            Kinds.SETTING,
            record.recordId,
            record.rev,
            record.modifiedAt,
            data = json.encodeToJsonElement(ExportPresetData.serializer(), data),
        )
    }

    /** Applies a pulled export preset or its tombstone. Always placed: a preset depends on nothing else. */
    @Suppress("ReturnCount")
    suspend fun apply(record: FeedRecord): Boolean {
        val uuid = uuidOf(record.id) ?: return true
        val existing = dao.exportPresetByUuid(uuid)
        if (record.deleted || record.data == null) {
            existing?.let { dao.deleteExportPreset(it.id) }
            return true
        }
        val data = runCatching {
            json.decodeFromJsonElement(ExportPresetData.serializer(), checkNotNull(record.data))
        }.getOrNull() ?: return true
        val now = clock()
        val row = ExportPresetEntity(
            id = existing?.id ?: 0,
            uuid = uuid,
            name = data.name,
            optionsJson = data.options.toString(),
            position = data.position,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
        )
        if (existing == null) {
            dao.insertExportPreset(row)
        } else if (row != existing) {
            dao.updateExportPreset(row)
        }
        return true
    }

    companion object {
        /** True when the `setting` record [id] is an export preset. */
        fun owns(id: String): Boolean = id.startsWith(SyncTriggers.EXPORT_PRESET_PREFIX)

        fun uuidOf(id: String): String? = id.takeIf(::owns)?.removePrefix(SyncTriggers.EXPORT_PRESET_PREFIX)

        /** The record id of the preset [uuid]. */
        fun recordId(uuid: String): String = SyncTriggers.EXPORT_PRESET_PREFIX + uuid
    }
}
