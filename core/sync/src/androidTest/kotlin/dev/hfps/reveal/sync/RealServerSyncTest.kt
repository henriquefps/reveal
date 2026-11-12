package dev.hfps.reveal.sync

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.files.ServerFiles
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sync against the real `reveal-sync` (ADR 0018 "Tests"), run locally with `scripts/sync/local-server.sh` and reached
 * through `adb reverse`. Two databases are two devices. Arguments: `-e syncLinks <link>,<link>` (two pairing links)
 * and, for the NAS case, `-e nasSeed <seed> -e nasSize <bytes>` (the synthetic file the script indexed). Skipped
 * without links. Logs `REVEAL_SYNC` lines (timings).
 */
@RunWith(AndroidJUnit4::class)
class RealServerSyncTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val args = InstrumentationRegistry.getArguments()
    private val links = args.getString("syncLinks").orEmpty().split(',').filter { it.isNotBlank() }
    private val devices = ArrayList<Device>()
    private val root = File(context.cacheDir, "sync-test-${UUID.randomUUID()}").apply { mkdirs() }

    /** One simulated device: its database, sync store, engine and its own device key on the server. */
    private inner class Device(name: String, index: Int, link: PairingLink) {
        val db: RevealDatabase = RevealDatabase.configure(
            Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java),
        ).build()
        val store = SyncStore(db)
        val engine = SyncEngine(db, store)
        val edits = EditRepository(db)
        val api: SyncApi
        val serverId: String
        val folder: Long
        val files = File(root, name).apply { mkdirs() }

        init {
            // A pairing token is single use: each simulated device pairs once and keeps its key for every test.
            val (key, id) = keys[index] ?: SyncApi(link.url, null)
                .pair(PairRequest(link.token, "$name (test)", "android", "test"))
                .let { it.apiKey to it.serverId }
                .also { keys[index] = it }
            api = SyncApi(link.url, key)
            serverId = id
            folder = runBlocking {
                db.sourceDao().insert(
                    SourceEntity(
                        treeUri = "file://${files.path}",
                        rootDocumentId = "",
                        displayName = name,
                        volumeId = null,
                        volumeName = null,
                        state = SourceState.AVAILABLE,
                        writable = false,
                        addedAt = 0,
                    ),
                )
            }
        }

        val trees = DocumentTreeFactory { FileTree(files) }

        fun sync() = runBlocking { engine.sync(api, serverId) }

        /** A photo of [file] (inside [files]) as the indexer files it. */
        fun add(file: File, role: FileRole = FileRole.RAW): Long = runBlocking {
            val id = db.photoDao().insertPhoto(
                PhotoEntity(
                    sourceId = folder, folder = "", folderDocumentId = "",
                    stemKey = file.nameWithoutExtension.lowercase(),
                    name = file.name, format = if (role == FileRole.RAW) PhotoFormat.RAW else PhotoFormat.JPEG,
                    hasRaw = role == FileRole.RAW, sortTime = System.currentTimeMillis(), addedAt = 0,
                ),
            )
            db.photoDao().insertFiles(
                listOf(
                    PhotoFileEntity(
                        photoId = id, sourceId = folder, relativePath = file.name, documentId = file.name,
                        name = file.name,
                        size = file.length(), modified = file.lastModified(), role = role,
                        fingerprint = FileFingerprint.of(file.readBytes()),
                    ),
                ),
            )
            db.photoDao().insertVersion(VersionEntity(photoId = id, createdAt = 0))
            id
        }

        fun version(photoId: Long) = runBlocking { checkNotNull(db.photoDao().firstVersion(photoId)) }

        fun edit(versionId: Long, exposure: Float) = runBlocking {
            val before = edits.recipe(versionId)
            val after = before.copy(light = before.light.copy(exposureEv = exposure))
            edits.commit(versionId, after, RecipeDiff.between(before, after))
        }

        fun close() = db.close()
    }

    /** A folder of plain files as a [DocumentTree] (document id = file name). */
    private class FileTree(private val dir: File) : DocumentTree {
        override val rootDocumentId = ""
        override fun children(documentId: String) = emptyList<DocumentEntry>()
        override fun stat(documentId: String): DocumentEntry? = null
        override fun openInput(documentId: String, name: String) =
            ImageInput(name, ParcelFileDescriptor.open(File(dir, documentId), ParcelFileDescriptor.MODE_READ_ONLY))
        override fun readBytes(documentId: String, maxBytes: Int) = throw FileNotFoundException()
        override fun writeBytes(documentId: String, bytes: ByteArray) = throw FileNotFoundException()
        override fun create(parentDocumentId: String, name: String, mimeType: String) = throw FileNotFoundException()
        override fun uri(documentId: String) = android.net.Uri.fromFile(File(dir, documentId))
    }

    @Before
    fun setUp() {
        assumeTrue("needs -e syncLinks <link>,<link> (scripts/sync/local-server.sh)", links.size >= 2)
    }

    @After
    fun tearDown() {
        devices.forEach(Device::close)
        root.deleteRecursively()
    }

    private fun device(index: Int, name: String) =
        Device(name, index, checkNotNull(PairingLink.parse(links[index]))).also { devices += it }

    private fun synthetic(dir: File, name: String, seed: Int, size: Int): File =
        File(dir, name).apply { writeBytes(generated(seed, size)) }

    private fun generated(seed: Int, size: Int) =
        ByteArray(size) { i -> (((i.toLong() * 2654435761L + seed * 97L) shr 13) and 0xff).toByte() }

    private fun jpeg(dir: File, name: String, hue: Float): File {
        val bitmap = Bitmap.createBitmap(1200, 800, Bitmap.Config.ARGB_8888)
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.5f, 0.9f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 60) % 360, 0.7f, 0.3f))
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader =
                    LinearGradient(0f, 0f, 1200f, 800f, a, b, Shader.TileMode.CLAMP)
            },
        )
        return File(dir, name).apply { outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
    }

    @Test
    fun anEditTravelsAndAConflictBecomesACopyOnBothDevices() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val bytes = generated(UUID.randomUUID().hashCode(), 300_000)
        val p = phone.add(File(phone.files, "DSC1.ARW").apply { writeBytes(bytes) })
        val t = tablet.add(File(tablet.files, "DSC1.ARW").apply { writeBytes(bytes) })
        phone.edit(phone.version(p), 0.5f)
        phone.sync()
        tablet.sync()
        assertThat(runBlocking { tablet.edits.recipe(tablet.version(t)) }.light.exposureEv).isEqualTo(0.5f)

        phone.edit(phone.version(p), -1f)
        Thread.sleep(20)
        tablet.edit(tablet.version(t), 1.5f)
        tablet.sync()
        phone.sync()
        tablet.sync()
        for ((device, photo) in listOf(phone to p, tablet to t)) {
            val versions = runBlocking { device.db.syncDao().versionsOf(photo) }
            assertThat(versions).hasSize(2)
            val copy = versions.single { it.copyOf != null }
            assertThat(copy.copyDevice).isEqualTo("Phone (test)")
            assertThat(runBlocking { device.edits.recipe(copy.id) }.light.exposureEv).isEqualTo(-1f)
            assertThat(runBlocking { device.edits.recipe(versions.single { it.copyOf == null }.id) }.light.exposureEv)
                .isEqualTo(1.5f)
        }
    }

    @Test
    fun aSmartPreviewMadeHereOpensOnTheOtherDevice() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        // Unique content per run: the server keeps every earlier run's photos, and equal files are one photo.
        val file =
            jpeg(phone.files, "IMG_${UUID.randomUUID().toString().take(6)}.JPG", (System.nanoTime() % 3600) / 10f)
        val p = phone.add(file, FileRole.RENDERED)
        phone.edit(phone.version(p), 0.3f)
        phone.sync() // pushes and looks the photo up: missing on the server
        val uploader =
            BlobUploader(phone.db, phone.store, phone.trees, SmartPreviewMaker(File(phone.files, "previews")))
        val made = runBlocking { uploader.previews(phone.api, limit = 5, noBackup = true) }
        assertThat(made).isAtLeast(1)
        phone.sync() // the photo record now names its preview
        tablet.sync()
        val fingerprint = FileFingerprint.of(file.readBytes())
        val remote = runBlocking { tablet.db.photoDao().filesByFingerprint(listOf(fingerprint)) }.single()
        val files = ServerFiles(tablet.db, { tablet.api }, File(tablet.files, "cache"))
        val preview = checkNotNull(runBlocking { files.smartPreview(fingerprint) })
        val decoded = preview.inputStream().use(SmartPreviewCodec::decode)
        assertThat(decoded.input.width).isEqualTo(1200)
        assertThat(decoded.jpeg.size).isGreaterThan(1000)
        assertThat(runBlocking { files.original(fingerprint) }).isNull()
        val rows =
            runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.Server))) }
        val row = rows.single { it.id == remote.file.photoId }
        assertThat(row.cloudPreview).isTrue()
        assertThat(row.cloudOriginal).isFalse()
        assertThat(row.hasEdit).isTrue()
    }

    @Test
    fun anOriginalUploadKilledMidWayResumesAndTheOtherDeviceReadsItByRange() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val size = 20 * 1024 * 1024 + 12_345
        val file =
            synthetic(
                phone.files,
                "BIG_${UUID.randomUUID().toString().take(6)}.ARW",
                UUID.randomUUID().hashCode(),
                size,
            )
        val p = phone.add(file)
        phone.sync()
        // The first run is killed after its first chunk (the coroutine is cancelled, as when the process dies).
        val first = BlobUploader(phone.db, phone.store, phone.trees, SmartPreviewMaker(File(phone.files, "p")))
        try {
            runBlocking {
                first.originals(phone.api, 5) { sent, _ ->
                    if (sent >=
                        BlobUploader.DEFAULT_CHUNK
                    ) {
                        throw CancellationException("killed")
                    }
                }
            }
        } catch (_: CancellationException) {
        }
        val stored = runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_ORIGINAL, 5) }.single()
        assertThat(stored.offset).isEqualTo(BlobUploader.DEFAULT_CHUNK)
        val start = System.nanoTime()
        val second = BlobUploader(phone.db, phone.store, phone.trees, SmartPreviewMaker(File(phone.files, "p")))
        assertThat(runBlocking { second.originals(phone.api, 5) }).isEqualTo(1)
        Log.i(TAG, "resumed upload of ${size - stored.offset} bytes in ${(System.nanoTime() - start) / 1_000_000} ms")
        assertThat(runBlocking { phone.db.syncDao().cloud(p) }!!.originalSource).isEqualTo("backup")

        phone.sync()
        tablet.sync()
        val fingerprint = FileFingerprint.of(file.readBytes())
        val files = ServerFiles(tablet.db, { tablet.api }, File(tablet.files, "cache"))
        val remote = checkNotNull(runBlocking { files.original(fingerprint) })
        assertThat(remote.size).isEqualTo(size.toLong())
        val expected = file.readBytes()
        val buffer = ByteArray(100_000)
        for (offset in listOf(0L, 9_000_000L, size - 50_000L)) {
            val n = remote.read(offset, buffer.size, buffer)
            assertThat(buffer.copyOf(n)).isEqualTo(expected.copyOfRange(offset.toInt(), offset.toInt() + n))
        }
    }

    @Test
    fun nasFilesShowAsServerPhotosAndReadByRange() {
        val seed = args.getString("nasSeed")?.toIntOrNull()
        val size = args.getString("nasSize")?.toIntOrNull()
        assumeTrue("needs -e nasSeed and -e nasSize", seed != null && size != null)
        val tablet = device(1, "Tablet")
        // The server indexes in the background: give it a few syncs.
        val expected = generated(seed!!, size!!)
        val fingerprint = FileFingerprint.of(expected)
        var found = false
        repeat(20) {
            tablet.sync()
            found = runBlocking { tablet.db.photoDao().filesByFingerprint(listOf(fingerprint)) }.isNotEmpty()
            if (found) return@repeat
            Thread.sleep(500)
        }
        assertThat(found).isTrue()
        val files = ServerFiles(tablet.db, { tablet.api }, File(tablet.files, "cache"))
        val remote = checkNotNull(runBlocking { files.original(fingerprint) })
        val buffer = ByteArray(4096)
        val n = remote.read(size - 4096L, 4096, buffer)
        assertThat(buffer.copyOf(n)).isEqualTo(expected.copyOfRange(size - 4096, size))
    }

    @Test
    fun aThousandEditedPhotosSyncToTheOtherDevice() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val run = UUID.randomUUID().hashCode()
        for (i in 0 until 1000) phone.add(File(phone.files, "IMG$i.ARW").apply { writeBytes(generated(run + i, 2048)) })
        val ids = runBlocking { phone.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())) }.map { it.versionId }
        for ((i, v) in ids.withIndex()) phone.edit(v, 0.1f + (i % 9) / 10f)
        val t0 = System.nanoTime()
        val pushed = phone.sync()
        val t1 = System.nanoTime()
        val pulled = tablet.sync()
        val t2 = System.nanoTime()
        val push = (t1 - t0) / 1_000_000
        val pull = (t2 - t1) / 1_000_000
        Log.i(TAG, "1000 edited photos: push $push ms ($pushed), pull $pull ms ($pulled)")
        val rows =
            runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.Server))) }
        assertThat(rows.count { it.hasEdit }).isAtLeast(1000)
    }

    private companion object {
        const val TAG = "REVEAL_SYNC"
        val keys = arrayOfNulls<Pair<String, String>>(2)
    }
}
