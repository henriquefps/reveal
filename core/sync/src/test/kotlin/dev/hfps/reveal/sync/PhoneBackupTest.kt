package dev.hfps.reveal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.SyncDeleteEntity
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.data.sync.BackupCoverage
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.blobs.BlobUploader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * One backup per phone (Hoard ADR 0024 "For Reveal's orchestrator" 1-5, ADR 0018 "R1") against [FakeSyncServer] with
 * `backup`: the roots of `GET /v1/device` cover `ExternalStorageProvider` folders by volume, path and type; every
 * covered candidate waits 24 hours, is looked up again and uploaded only if still missing, with `wait`; `in_progress`
 * keeps the upload queued without a failure; `409 mirror` ends a delete at once; `freed-adopt` is declared.
 */
@RunWith(AndroidJUnit4::class)
class PhoneBackupTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var dir: File
    private lateinit var phone: TestDevice
    private lateinit var uploader: BlobUploader
    private var camera = 0L

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
        server.backup()
        dir = Files.createTempDirectory("phone-backup").toFile()
        phone = TestDevice("Phone", url, server.addDevice("Phone"), server.serverId, device = pixel)
        uploader = BlobUploader(
            phone.db,
            phone.store,
            DocumentTreeFactory { FileTree(dir) },
            SmartPreviewMaker(File(dir, "made")),
        ) { phone.now }
        camera = externalFolder("primary:DCIM/Camera", backup = true)
    }

    @After
    fun tearDown() {
        phone.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    private val pixel = ClientDevice("reveal", "Google", "Pixel 8")

    private fun deviceId(): String = phone.api.server().deviceId

    /** An added folder of Android's own storage provider ([rootDocumentId] = `primary:DCIM/Camera`). */
    private fun externalFolder(rootDocumentId: String, backup: Boolean): Long = runBlocking {
        phone.db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://${BackupCoverage.EXTERNAL_STORAGE_AUTHORITY}/tree/" +
                    rootDocumentId.replace(":", "%3A").replace("/", "%2F"),
                rootDocumentId = rootDocumentId,
                displayName = rootDocumentId.substringAfterLast('/'),
                volumeId = rootDocumentId.substringBefore(':'),
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
                backup = backup,
            ),
        )
    }

    /** A photo whose document holds random bytes (the backup reads it); the RAW's name decides its type. */
    private fun readable(stem: String, seed: Int, source: Long, ext: String = "ARW"): Pair<Long, ByteArray> {
        val bytes = Random(seed).nextBytes(3000)
        val photo = phone.addPhoto(stem, bytes, source = source)
        if (ext != "ARW") {
            runBlocking {
                val file = phone.db.photoDao().files(photo).single()
                phone.db.openHelper.writableDatabase.execSQL(
                    "UPDATE photo_files SET name = ?, document_id = ? WHERE id = ?",
                    arrayOf<Any>("$stem.$ext", "$stem.$ext", file.id),
                )
            }
        }
        File(dir, "$stem.$ext").writeBytes(bytes)
        return photo to bytes
    }

    private fun originals() = runBlocking { uploader.originals(phone.api, 50) }

    private fun uploadedFingerprints(): Set<String> = server.originals.keys

    private fun requestFor(bytes: ByteArray) = server.uploadRequests.lastOrNull {
        it["fingerprint"]?.jsonPrimitive?.content == FileFingerprint.of(bytes)
    }

    @Test
    fun `the device view's roots cover ExternalStorage folders by volume, path and activity`() {
        val pictures = externalFolder("primary:Pictures", backup = true)
        val card = externalFolder("1A2B-3C4D:DCIM", backup = true)
        server.addRoot(deviceId(), "primary", "DCIM", types = listOf("image", "raw"))
        server.addRoot(deviceId(), "1a2b-3c4d", "", types = listOf("image"), active = false)
        server.addRoot("another-phone", "primary", "Pictures")
        phone.sync()

        val stored = BackupCoverage.decode(runBlocking { phone.db.metaDao().get(BackupCoverage.META_ROOTS) })
        assertThat(stored.map { it.path }).containsExactly("DCIM", "")
        val library = LibraryRepository(
            ApplicationProvider.getApplicationContext<Context>().contentResolver,
            phone.db,
            null,
            DocumentTreeFactory { FileTree(dir) },
            SidecarSync(phone.db, DocumentTreeFactory { FileTree(dir) }, "Reveal test"),
            CoroutineScope(Dispatchers.Unconfined),
        )
        val covered = runBlocking { library.sources().first() }.associate { it.id to it.coveredByHoard }
        // DCIM/Camera is inside the active DCIM root; Pictures is another phone's root; the card's root is paused;
        // the test folder is not an ExternalStorageProvider tree.
        assertThat(covered[camera]).isTrue()
        assertThat(covered[pictures]).isFalse()
        assertThat(covered[card]).isFalse()
        assertThat(covered[phone.folder]).isFalse()

        // A server without `backup` forgets the roots: nothing is covered any more.
        server.features -= "backup"
        phone.sync()
        assertThat(runBlocking { phone.db.metaDao().get(BackupCoverage.META_ROOTS) }).isNull()
        assertThat(runBlocking { library.sources().first() }.none { it.coveredByHoard }).isTrue()
    }

    @Test
    fun `covered candidates wait a day and go only if still missing, uncovered ones go at once`() {
        server.addRoot(deviceId(), "primary", "DCIM/Camera", types = listOf("image"))
        val editedOnly = externalFolder("primary:DCIM/Camera/Edits", backup = false)
        val (dng, dngBytes) = readable("PXL_1", 1, camera, ext = "dng")
        val (hoardTakes, hoardBytes) = readable("PXL_2", 2, camera, ext = "jpg")
        val (cr3, cr3Bytes) = readable("IMG_3", 3, camera, ext = "CR3")
        val (elsewhere, elsewhereBytes) = readable("DSC4", 4, phone.folder)
        val (edited, editedBytes) = readable("PXL_5", 5, editedOnly, ext = "jpg")
        phone.edit(phone.version(edited)) { it.copy(light = it.light.copy(exposureEv = 0.2f)) }
        phone.sync()

        originals()
        // The CR3 is not of the root's types and the test folder is no phone folder: both at once, as before.
        assertThat(
            uploadedFingerprints(),
        ).containsExactly(FileFingerprint.of(cr3Bytes), FileFingerprint.of(elsewhereBytes))
        val graceStart = phone.now
        listOf(dng, hoardTakes, edited).forEach {
            assertThat(runBlocking { phone.db.syncDao().cloud(it) }!!.candidateAt).isEqualTo(graceStart)
        }

        // During the day Hoard backs one of them up; 23 hours in, nothing of the covered ones moves.
        server.putOriginal(hoardBytes, device = "hoard")
        phone.now += 23 * HOUR
        originals()
        assertThat(uploadedFingerprints()).doesNotContain(FileFingerprint.of(dngBytes))
        assertThat(server.uploadRequests.map { it["fingerprint"]?.jsonPrimitive?.content })
            .containsNoneOf(FileFingerprint.of(dngBytes), FileFingerprint.of(editedBytes))

        // A day later: asked again; Hoard's is on the server (never sent), the others go with `wait`.
        phone.now += 2 * HOUR
        originals()
        assertThat(
            uploadedFingerprints(),
        ).containsAtLeast(FileFingerprint.of(dngBytes), FileFingerprint.of(editedBytes))
        assertThat(requestFor(hoardBytes)).isNull()
        assertThat(runBlocking { phone.db.syncDao().cloud(hoardTakes) }!!.originalOnServer).isTrue()
        assertThat(requestFor(dngBytes)!!["wait"]?.jsonPrimitive?.booleanOrNull).isTrue()
        assertThat(requestFor(dngBytes)!!["claim"]).isNull()
        // Every original this server receives carries `wait` (it lists `backup`), the uncovered ones too.
        assertThat(requestFor(cr3Bytes)!!["wait"]?.jsonPrimitive?.booleanOrNull).isTrue()
    }

    @Test
    fun `a server without backup gets no wait and covers nothing`() {
        server.features -= "backup"
        val (_, bytes) = readable("PXL_6", 6, camera, ext = "jpg")
        phone.sync()
        originals()
        assertThat(uploadedFingerprints()).contains(FileFingerprint.of(bytes))
        assertThat(requestFor(bytes)!!.containsKey("wait")).isFalse()
    }

    @Test
    fun `in_progress keeps the upload queued without a failure and it goes after retryAfter`() {
        val (photo, bytes) = readable("DSC7", 7, phone.folder)
        val sha = BlobUploader.sha256(ByteArrayInputStream(bytes))
        server.inProgress[sha] = 120L
        phone.sync()

        originals()
        val queued = runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_ORIGINAL, 10) }.single()
        assertThat(queued.photoId).isEqualTo(photo)
        assertThat(queued.attempts).isEqualTo(0)
        assertThat(queued.state).isEqualTo(SyncUploadEntity.STATE_PENDING)
        assertThat(queued.retryAt).isEqualTo(phone.now + 120_000)
        assertThat(server.patchCount.get()).isEqualTo(0)

        // Before the time: not asked again (no second, parallel upload).
        val asked = server.uploadRequests.size
        phone.now += 60_000
        originals()
        assertThat(server.uploadRequests).hasSize(asked)

        // The other device's upload is gone: it goes now. Without retryAfter the default is 300 s.
        server.inProgress.clear()
        phone.now += 61_000
        originals()
        assertThat(uploadedFingerprints()).contains(FileFingerprint.of(bytes))

        val (_, other) = readable("DSC8", 8, phone.folder)
        server.inProgress[BlobUploader.sha256(ByteArrayInputStream(other))] = null
        phone.sync()
        originals()
        val waiting = runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_ORIGINAL, 10) }.single()
        assertThat(waiting.retryAt).isEqualTo(phone.now + BlobUploader.DEFAULT_RETRY_AFTER_S * 1000)
    }

    @Test
    fun `409 mirror ends a delete at once, keeps the file on the server and tells the owner`() {
        val bytes = Random(9).nextBytes(2000)
        val root = server.addRoot(deviceId(), "primary", "DCIM/Camera")
        server.addMirrorItem(root, "primary", "DCIM/Camera/PXL_9.jpg", bytes, 0, onDevice = true, gone = null)
        val fp = FileFingerprint.of(bytes)
        // The same file is still a photo of this phone's folder (the server holds it: on_server).
        phone.addFolderJpeg("PXL_9", bytes, source = camera)
        runBlocking {
            phone.db.syncDao().setOnServer(fp, true)
            phone.db.syncDao().queueDeletes(listOf(SyncDeleteEntity(fp, phone.now)))
        }

        val done = runBlocking { uploader.deleteOriginals(phone.api) }

        assertThat(done).isEqualTo(1)
        assertThat(runBlocking { phone.db.syncDao().pendingDeletes(10) }).isEmpty()
        assertThat(server.hasOriginal(fp)).isTrue()
        assertThat(runBlocking { phone.db.syncDao().knownOnServer(fp) }).isTrue()
        assertThat(runBlocking { phone.db.metaDao().get(BlobUploader.KEY_MIRROR_KEPT) }).isEqualTo("1")
    }

    @Test
    fun `freed-adopt is declared with culling only to a server with backup`() {
        phone.sync()
        val declared = server.culling.reports.last().second["features"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(declared).containsExactly("culling", "freed-adopt")

        server.features -= "backup"
        phone.sync()
        val later = server.culling.reports.last().second["features"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertThat(later).containsExactly("culling")
    }

    /** A document is `<dir>/<document id>`. */
    private class FileTree(private val dir: File) : DocumentTree {
        override val rootDocumentId = "root"

        override fun children(documentId: String) = emptyList<DocumentEntry>()

        override fun stat(documentId: String): DocumentEntry? = null

        override fun openInput(documentId: String, name: String): ImageInput = ImageInput(
            name,
            android.os.ParcelFileDescriptor.open(File(dir, documentId), android.os.ParcelFileDescriptor.MODE_READ_ONLY),
        )

        override fun readBytes(documentId: String, maxBytes: Int): ByteArray = File(dir, documentId).readBytes()

        override fun writeBytes(documentId: String, bytes: ByteArray) = throw IOException("read only")

        override fun create(parentDocumentId: String, name: String, mimeType: String) = throw IOException("no")

        override fun uri(documentId: String): android.net.Uri = android.net.Uri.parse("file:///$documentId")
    }

    private companion object {
        const val HOUR = 60L * 60 * 1000
    }
}
