package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncReport
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.engine.SyncedSetting
import kotlinx.coroutines.runBlocking

/** One simulated device: its own in-memory database, sync store and engine, paired with [key]. */
class TestDevice(
    val name: String,
    private val url: String,
    val key: String,
    private val serverId: String,
    settings: List<SyncedSetting> = emptyList(),
    var now: Long = 1_700_000_000_000L,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val db: RevealDatabase = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
        .allowMainThreadQueries().build()
    val store = SyncStore(db, settings) { now }
    val engine = SyncEngine(db, store) { now }
    val edits = EditRepository(db) { now }
    val presets = PresetRepository(
        db,
        java.io.File(System.getProperty("java.io.tmpdir"), "reveal-covers-$name-${System.nanoTime()}"),
    ) { now }
    val api = SyncApi(url, key)
    private val folder: Long = runBlocking {
        db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://$name/tree",
                rootDocumentId = "root",
                displayName = "DCIM",
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
            ),
        )
    }

    fun sync(): SyncReport = runBlocking { engine.sync(api, serverId) }

    /** A photo as the indexer adds it: a RAW (and optionally its JPEG) with fingerprints, and its first version. */
    fun addPhoto(stem: String, raw: ByteArray, jpeg: ByteArray? = null): Long = runBlocking {
        val photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = folder, folder = "", folderDocumentId = "root", stemKey = stem.lowercase(),
                name = "$stem.ARW",
                format = if (jpeg ==
                    null
                ) {
                    PhotoFormat.RAW
                } else {
                    PhotoFormat.RAW_PAIR
                },
                hasRaw = true, sortTime = now,
                addedAt = now,
            ),
        )
        val files = buildList {
            add(file(photoId, "$stem.ARW", raw, FileRole.RAW))
            if (jpeg != null) add(file(photoId, "$stem.JPG", jpeg, FileRole.RENDERED))
        }
        db.photoDao().insertFiles(files)
        db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = now))
        photoId
    }

    private fun file(photoId: Long, name: String, bytes: ByteArray, role: FileRole) = PhotoFileEntity(
        photoId = photoId, sourceId = folder, relativePath = name, documentId = name, name = name,
        size = bytes.size.toLong(), modified = 1, role = role, fingerprint = FileFingerprint.of(bytes),
    )

    fun version(photoId: Long): Long = runBlocking { checkNotNull(db.photoDao().firstVersion(photoId)) }

    fun versions(photoId: Long): List<VersionEntity> = runBlocking { db.syncDao().versionsOf(photoId) }

    fun edit(versionId: Long, change: (Recipe) -> Recipe) = runBlocking {
        val before = edits.recipe(versionId)
        val after = change(before)
        edits.commit(versionId, after, RecipeDiff.between(before, after))
    }

    fun recipe(versionId: Long): Recipe = runBlocking { edits.recipe(versionId) }

    fun photoByFingerprint(fingerprint: String): PhotoEntity? = runBlocking {
        db.photoDao().filesByFingerprint(listOf(fingerprint)).firstOrNull()?.let { db.photoDao().get(it.file.photoId) }
    }

    fun pending(): Int = runBlocking { db.syncDao().dirty(10_000).size }

    override fun close() = db.close()
}
