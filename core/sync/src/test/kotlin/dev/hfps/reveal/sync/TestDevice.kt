package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.CullingPendingEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncReport
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.engine.SyncedSetting
import dev.hfps.reveal.sync.engine.VersionData
import kotlinx.coroutines.runBlocking

/** One simulated device: its own in-memory database, sync store and engine, paired with [key]. */
class TestDevice(
    val name: String,
    private val url: String,
    key: String,
    private val serverId: String,
    settings: List<SyncedSetting> = emptyList(),
    var now: Long = 1_700_000_000_000L,
    device: ClientDevice? = null,
) : AutoCloseable {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    val db: RevealDatabase = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
        .allowMainThreadQueries().build()
    val store = SyncStore(db, settings) { now }
    val engine = SyncEngine(db, store, device = device) { now }
    val edits = EditRepository(db) { now }
    val presets = PresetRepository(
        db,
        java.io.File(System.getProperty("java.io.tmpdir"), "reveal-covers-$name-${System.nanoTime()}"),
    ) { now }
    var key: String = key
        private set
    var api = SyncApi(url, key)
        private set

    /** Paired again under another device [key] (same database; what the database keeps is the caller's business). */
    fun rekey(key: String) {
        this.key = key
        api = SyncApi(url, key)
    }
    val folder: Long = runBlocking {
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

    /** Another added folder (a folder added again after being removed, ADR 0018 FIX/V). */
    fun addFolder(name: String, backup: Boolean = true): Long = runBlocking {
        db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://${this@TestDevice.name}/$name/${System.nanoTime()}",
                rootDocumentId = "root",
                displayName = name,
                volumeId = null,
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
                backup = backup,
            ),
        )
    }

    /** A photo as the indexer adds it: a RAW (and optionally its JPEG) with fingerprints, and its first version. */
    fun addPhoto(stem: String, raw: ByteArray, jpeg: ByteArray? = null, source: Long = folder): Long = runBlocking {
        val photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "root", stemKey = stem.lowercase(),
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
            add(file(photoId, "$stem.ARW", raw, FileRole.RAW, source))
            if (jpeg != null) add(file(photoId, "$stem.JPG", jpeg, FileRole.RENDERED, source))
        }
        db.photoDao().insertFiles(files)
        db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = now))
        photoId
    }

    private fun file(photoId: Long, name: String, bytes: ByteArray, role: FileRole, source: Long = folder) =
        PhotoFileEntity(
            photoId = photoId, sourceId = source, relativePath = name, documentId = name, name = name,
            size = bytes.size.toLong(), modified = 1, role = role, fingerprint = FileFingerprint.of(bytes),
        )

    /** A photo another app handed over (ADR 0011): the JPEG under the "Opened from other apps" source. */
    fun addHandoffPhoto(
        stem: String,
        jpeg: ByteArray,
        uri: String = "content://dev.hfps.hoard.stream/h/${stem.hashCode()}/$stem.JPG",
    ): Long = runBlocking {
        val source = db.sourceDao().handoff()?.id ?: db.sourceDao().insert(
            SourceEntity(
                treeUri = "reveal-handoff:", rootDocumentId = "", displayName = "apps", volumeId = null,
                volumeName = null, state = SourceState.AVAILABLE, writable = true, addedAt = 0,
                kind = dev.hfps.reveal.data.model.SourceKind.HANDOFF,
            ),
        )
        val photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = uri, folderDocumentId = "", stemKey = stem.lowercase(), name = "$stem.JPG",
                format = PhotoFormat.JPEG, hasRaw = false, sortTime = now, addedAt = now, handoffAt = now,
            ),
        )
        db.photoDao().insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = photoId, sourceId = source, relativePath = uri, documentId = uri, name = "$stem.JPG",
                    size = jpeg.size.toLong(), modified = 1, role = FileRole.RENDERED,
                    fingerprint = FileFingerprint.of(jpeg),
                ),
            ),
        )
        db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = now))
        photoId
    }

    /** The same JPEG in this device's folder (as the indexer files a JPEG-only shot). */
    fun addFolderJpeg(stem: String, jpeg: ByteArray, source: Long = folder): Long = runBlocking {
        val photoId = db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "root", stemKey = stem.lowercase(),
                name = "$stem.JPG", format = PhotoFormat.JPEG, hasRaw = false, sortTime = now, addedAt = now,
            ),
        )
        db.photoDao().insertFiles(listOf(file(photoId, "$stem.JPG", jpeg, FileRole.RENDERED, source)))
        db.photoDao().insertVersion(VersionEntity(photoId = photoId, createdAt = now))
        photoId
    }

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

    /**
     * What 0.8 pushed for an edit of a photo without a record (ADR 0018 "W5"): the version alone, naming this device's
     * id for the photo and without its fingerprint. Returns the version's uuid.
     */
    fun legacyPushVersion(photoId: Long): String = runBlocking {
        val version = versions(photoId).first()
        val data = VersionData(
            photoId = photo(photoId).uuid,
            recipe = SyncApi.json.parseToJsonElement(RecipeCodec.Default.encode(recipe(version.id))),
        )
        val local = checkNotNull(db.syncDao().record("version", version.uuid))
        val result = api.push(
            listOf(
                PushChange(
                    "version",
                    version.uuid,
                    0,
                    local.modifiedAt,
                    data = SyncApi.json.encodeToJsonElement(VersionData.serializer(), data),
                ),
            ),
        ).results.single()
        db.syncDao().putRecord(local.copy(rev = result.rev, dirty = false))
        version.uuid
    }

    // ------------------------------------------------------------------ culling (ADR 0027)

    fun photo(photoId: Long): PhotoEntity = runBlocking { checkNotNull(db.photoDao().get(photoId)) }

    /** A rating tap in the library (the path the UI takes); [at] dates the decision instead of the wall clock. */
    fun rate(photoId: Long, rating: Int, at: Long? = null) = runBlocking {
        db.photoDao().setRating(listOf(photoId), rating)
        at?.let { date(photoId, "rating", it) }
    }

    fun flag(photoId: Long, flag: PickFlag, at: Long? = null) = runBlocking {
        db.photoDao().setFlag(listOf(photoId), flag)
        at?.let { date(photoId, "flag", it) }
    }

    fun label(photoId: Long, label: String?, at: Long? = null) = runBlocking {
        db.photoDao().setCulling(photoId, photo(photoId).rating, label, photo(photoId).flag)
        at?.let { date(photoId, "label", it) }
    }

    /** Dates a queued user decision (keeps it a decision made looking at the server's value, not a blind one). */
    private fun date(photoId: Long, field: String, at: Long) {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE culling_pending SET modified_at = ? WHERE photo_id = ? AND field = ?",
            arrayOf<Any>(at, photoId, field),
        )
    }

    fun cullingPending(photoId: Long): List<CullingPendingEntity> = runBlocking { db.cullingDao().pendingOf(photoId) }

    fun queued(): Int = runBlocking { db.cullingDao().pendingCount() }

    override fun close() = db.close()
}
