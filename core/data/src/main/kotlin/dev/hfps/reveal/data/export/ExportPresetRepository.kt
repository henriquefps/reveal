package dev.hfps.reveal.data.export

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.ExportPresetEntity
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** A user export preset (docs/adr/0025): its name and the export sheet's options as JSON (`:feature:export`'s). */
data class StoredExportPreset(val uuid: String, val name: String, val optionsJson: String, val position: Int)

/**
 * The user's export presets (docs/adr/0025 "Export presets"), in Room so they sync (a `setting` record each, marked by
 * the triggers). The built-in presets are `:feature:export` code and never stored.
 */
class ExportPresetRepository(
    private val db: RevealDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val dao = db.versionDao()

    fun observe(): Flow<List<StoredExportPreset>> =
        dao.observeExportPresets().map { rows -> rows.map(::stored) }.distinctUntilChanged()

    suspend fun all(): List<StoredExportPreset> = dao.exportPresets().map(::stored)

    /** Saves a new preset after the others; returns its uuid. */
    suspend fun create(name: String, optionsJson: String): String = db.withTransaction {
        val now = clock()
        val row = ExportPresetEntity(
            name = clean(name),
            optionsJson = optionsJson,
            position = dao.nextExportPresetPosition(),
            createdAt = now,
            updatedAt = now,
        )
        dao.insertExportPreset(row)
        row.uuid
    }

    /** Changes a preset's name and/or options (null keeps it). */
    suspend fun update(uuid: String, name: String? = null, optionsJson: String? = null) = db.withTransaction {
        val row = dao.exportPresetByUuid(uuid) ?: return@withTransaction
        val next = row.copy(
            name = name?.let(::clean)?.ifEmpty { null } ?: row.name,
            optionsJson = optionsJson ?: row.optionsJson,
            updatedAt = clock(),
        )
        if (next != row) dao.updateExportPreset(next)
    }

    suspend fun delete(uuid: String) = db.withTransaction {
        dao.exportPresetByUuid(uuid)?.let { dao.deleteExportPreset(it.id) }
    }

    private fun clean(name: String) = name.trim().take(MAX_NAME)

    private fun stored(row: ExportPresetEntity) = StoredExportPreset(row.uuid, row.name, row.optionsJson, row.position)

    companion object {
        const val MAX_NAME = 60
    }
}
