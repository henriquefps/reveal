package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.pipeline.preview.MadePreview
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import dev.hfps.reveal.sync.blobs.BlobUploader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which photos get a server smart preview, and what happens to the ones that no longer qualify (ADR 0018 "Smart
 * previews", FIX/P): two simulated devices against the fake server, previews made by a stand-in maker (the real one
 * decodes on a device: `SmartPreviewMakerTest`).
 */
@RunWith(AndroidJUnit4::class)
class SmartPreviewRulesTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var dir: File
    private lateinit var phone: TestDevice
    private lateinit var tablet: TestDevice
    private lateinit var maker: CountingMaker
    private lateinit var uploader: BlobUploader

    @Before
    fun setUp() {
        url = server.start()
        dir = Files.createTempDirectory("preview-rules").toFile()
        phone = TestDevice("Phone", url, server.addDevice("Phone"), server.serverId)
        tablet = TestDevice("Tablet", url, server.addDevice("Tablet"), server.serverId)
        maker = CountingMaker(File(dir, "made"))
        val tree = FileTree(File(dir, "original.bin").apply { writeBytes(Random(1).nextBytes(64)) })
        uploader = BlobUploader(phone.db, phone.store, DocumentTreeFactory { tree }, maker) { phone.now }
    }

    @After
    fun tearDown() {
        phone.close()
        tablet.close()
        server.shutdown()
        dir.deleteRecursively()
    }

    /** Writes a small current-format preview whose bytes depend on the file name (each photo its own blob). */
    private class CountingMaker(dir: File) : SmartPreviewMaker(dir) {
        val made = ArrayList<String>()
        private val temp = File(dir.parentFile, "temp").apply { mkdirs() }

        override fun make(input: ImageInput, cancel: CancelToken): MadePreview {
            made += input.name
            val file = File(temp, "${made.size}.rvsp")
            file.writeBytes(
                SmartPreviewCodec.encode(
                    SyntheticImages.grayRamp(16, 12),
                    "${input.name}#${made.size}".toByteArray(),
                    32,
                    24,
                ),
            )
            return adopt(file)
        }
    }

    /** A document is `<dir>/<document id>` when a test wrote one, else a small shared file (never decoded). */
    private class FileTree(private val file: File) : DocumentTree {
        private fun of(documentId: String) = File(file.parentFile, documentId).takeIf { it.isFile } ?: file

        override val rootDocumentId = "root"

        override fun children(documentId: String) = emptyList<DocumentEntry>()

        override fun stat(documentId: String): DocumentEntry? = null

        override fun openInput(documentId: String, name: String): ImageInput = ImageInput(
            name,
            android.os.ParcelFileDescriptor.open(of(documentId), android.os.ParcelFileDescriptor.MODE_READ_ONLY),
        )

        override fun readBytes(documentId: String, maxBytes: Int): ByteArray = file.readBytes()

        override fun writeBytes(documentId: String, bytes: ByteArray) = throw IOException("read only")

        override fun create(parentDocumentId: String, name: String, mimeType: String) = throw IOException("no")

        override fun uri(documentId: String): android.net.Uri = android.net.Uri.parse("file:///$documentId")
    }

    /** The preview run with the originals backup off (the case where edited photos need a preview). */
    private fun previews(reformat: Int = 0, originalsOff: Boolean = true) =
        runBlocking { uploader.previews(phone.api, 20, originalsOff, reformat) }

    private fun originals() = runBlocking { uploader.originals(phone.api, 20) }

    /** A photo whose document holds [bytes] (the originals backup reads it). */
    private fun addReadable(stem: String, bytes: ByteArray): Long {
        File(dir, "$stem.ARW").writeBytes(bytes)
        return phone.addPhoto(stem, bytes)
    }

    private fun cloud(device: TestDevice, photoId: Long) = runBlocking { device.db.syncDao().cloud(photoId) }

    private fun remoteRow(raw: ByteArray) = runBlocking {
        val id = tablet.photoByFingerprint(FileFingerprint.of(raw))!!.id
        tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.Server))).single { it.id == id }
    }

    @Test
    fun `with the originals backup off only edited photos get a preview, ratings and flags travel without`() {
        val edited = Random(10).nextBytes(512)
        val rated = Random(11).nextBytes(512)
        val picked = Random(12).nextBytes(512)
        val plain = Random(13).nextBytes(512)
        val e = phone.addPhoto("EDITED", edited)
        val r = phone.addPhoto("RATED", rated)
        val p = phone.addPhoto("PICKED", picked)
        phone.addPhoto("PLAIN", plain)
        phone.edit(phone.version(e)) { it.copy(light = Light(exposureEv = 0.7f)) }
        runBlocking {
            phone.db.photoDao().setRating(listOf(r), 5)
            phone.db.photoDao().setCulling(r, 5, "Red", PickFlag.NONE)
            phone.db.photoDao().setFlag(listOf(p), PickFlag.PICK)
        }
        phone.sync() // lookups: none of the originals is on the server

        previews()
        phone.sync()
        tablet.sync()

        assertThat(maker.made).containsExactly("EDITED.ARW")
        assertThat(server.blobs.keys).containsExactly(cloud(phone, e)!!.previewSha)
        // The tablet sees every photo, with its metadata; only the edited one has a preview to develop.
        assertThat(remoteRow(edited).cloudPreview).isTrue()
        val ratedRow = remoteRow(rated)
        assertThat(ratedRow.rating).isEqualTo(5)
        assertThat(ratedRow.label).isEqualTo("Red")
        assertThat(ratedRow.remote).isTrue()
        assertThat(ratedRow.cloudPreview).isFalse()
        assertThat(ratedRow.cloudOriginal).isFalse()
        assertThat(remoteRow(picked).flag).isEqualTo(PickFlag.PICK)
        assertThat(remoteRow(picked).cloudPreview).isFalse()
        assertThat(remoteRow(plain).cloudPreview).isFalse()

        // An edit made later queues the preview then.
        phone.now += 1_000
        phone.edit(phone.version(p)) { it.copy(light = Light(contrast = 20f)) }
        previews()
        phone.sync()
        tablet.sync()
        assertThat(maker.made).containsExactly("EDITED.ARW", "PICKED.ARW")
        assertThat(remoteRow(picked).cloudPreview).isTrue()
        // Nothing more is owed.
        previews()
        assertThat(maker.made).hasSize(2)
    }

    @Test
    fun `an unreadable original that is not backed up still gets its preview`() {
        val raw = Random(20).nextBytes(512)
        val photo = phone.addPhoto("GONE", raw)
        phone.sync()
        runBlocking { phone.db.photoDao().setUnreadable("GONE.ARW", phone.now) }
        // Whatever the originals backup: the file cannot be uploaded.
        for (off in listOf(false, true)) {
            assertThat(runBlocking { phone.db.syncDao().previewCandidates(off, 10) }.map { it.photoId })
                .containsExactly(photo)
        }
        // Once its original reaches the server it no longer qualifies.
        runBlocking { phone.store.lookedUp(photo, dev.hfps.reveal.sync.engine.SyncStore.SOURCE_BACKUP) }
        assertThat(runBlocking { phone.db.syncDao().previewCandidates(true, 10) }).isEmpty()
    }

    @Test
    fun `a preview that no longer qualifies leaves the record, then the server, and the other device forgets it`() {
        val raw = Random(30).nextBytes(512)
        val photo = phone.addPhoto("DSC30", raw)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        previews()
        phone.sync()
        tablet.sync()
        val sha = cloud(phone, photo)!!.previewSha!!
        assertThat(remoteRow(raw).cloudPreview).isTrue()

        // The edit is reset: the photo is unedited again.
        phone.now += 1_000
        phone.edit(phone.version(photo)) { Recipe() }
        assertThat(runBlocking { uploader.markDrops() }).isEqualTo(1)
        assertThat(cloud(phone, photo)!!.previewSha).isNull()
        // Before the push the server refuses: the record still names it.
        assertThat(runBlocking { uploader.drops(phone.api) }).isEqualTo(0)
        assertThat(server.blobs).containsKey(sha)
        phone.sync()
        assertThat(server.previewNamed(sha)).isFalse()
        assertThat(runBlocking { uploader.drops(phone.api) }).isEqualTo(1)
        assertThat(server.blobs).doesNotContainKey(sha)
        assertThat(runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_DROP, 10) }).isEmpty()

        tablet.sync()
        assertThat(remoteRow(raw).cloudPreview).isFalse()
        assertThat(cloud(tablet, tablet.photoByFingerprint(FileFingerprint.of(raw))!!.id)!!.previewSha).isNull()

        // Edited again: a new preview goes up (the same bytes are a new blob after the drop).
        phone.now += 1_000
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 0.5f)) }
        previews()
        phone.sync()
        tablet.sync()
        assertThat(remoteRow(raw).cloudPreview).isTrue()
    }

    @Test
    fun `a backed-up original's preview is dropped too`() {
        val raw = Random(40).nextBytes(512)
        val photo = phone.addPhoto("DSC40", raw)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        previews()
        phone.sync()
        val sha = cloud(phone, photo)!!.previewSha!!
        runBlocking { phone.store.lookedUp(photo, dev.hfps.reveal.sync.engine.SyncStore.SOURCE_BACKUP) }
        assertThat(runBlocking { uploader.markDrops() }).isEqualTo(1)
        phone.sync()
        runBlocking { uploader.drops(phone.api) }
        assertThat(server.blobs).doesNotContainKey(sha)
    }

    @Test
    fun `old format previews are made again on an unmetered network, and the old blob goes`() {
        val raw = Random(50).nextBytes(512)
        val photo = phone.addPhoto("DSC50", raw)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        // What 0.5.1 left: a format 1 preview this device uploaded, named by the record (no format recorded).
        val old = SmartPreviewCodec.encode(
            SyntheticImages.grayRamp(16, 12),
            byteArrayOf(1),
            32,
            24,
            SmartPreviewCodec.VERSION_1,
        )
        val oldSha = BlobUploader.sha256(old.inputStream())
        runBlocking {
            val dao = phone.db.syncDao()
            dao.putCloud(
                dao.cloud(photo)!!.copy(
                    previewSha = oldSha,
                    previewWidth = 16,
                    previewHeight = 12,
                    previewFingerprint = FileFingerprint.of(raw),
                    previewFormat = null,
                ),
            )
        }
        server.blobs[oldSha] = old
        server.blobKinds[oldSha] = "preview"
        server.blobOwners[oldSha] = server.devices[phone.key]!!
        phone.now += 1_000
        runBlocking { phone.db.photoDao().setRating(listOf(photo), 1) } // any change: the record is pushed
        phone.sync()
        assertThat(server.previewNamed(oldSha)).isTrue()

        // Metered (or no room in this run): nothing is made again.
        previews(reformat = 0)
        assertThat(maker.made).isEmpty()
        // Unmetered: made again, uploaded, the record names the new one, the old one is dropped.
        previews(reformat = 3)
        assertThat(maker.made).containsExactly("DSC50.ARW")
        val cloud = cloud(phone, photo)!!
        assertThat(cloud.previewSha).isNotEqualTo(oldSha)
        assertThat(cloud.previewFormat).isEqualTo(SmartPreviewCodec.VERSION)
        phone.sync()
        runBlocking { uploader.drops(phone.api) }
        assertThat(server.blobs).doesNotContainKey(oldSha)
        assertThat(server.blobs).containsKey(cloud.previewSha)
        // Done: not made again.
        previews(reformat = 3)
        assertThat(maker.made).hasSize(1)
    }

    @Test
    fun `a server without the drop route keeps the drops queued until it is updated`() {
        val raw = Random(60).nextBytes(512)
        val photo = phone.addPhoto("DSC60", raw)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        previews()
        phone.sync()
        val sha = cloud(phone, photo)!!.previewSha!!
        phone.now += 1_000
        phone.edit(phone.version(photo)) { Recipe() }
        runBlocking { uploader.markDrops() }
        phone.sync()
        server.dropUnsupported = true
        assertThat(runBlocking { uploader.drops(phone.api) }).isEqualTo(0)
        assertThat(runBlocking { phone.db.syncDao().pendingUploads(SyncUploadEntity.KIND_DROP, 10) }).hasSize(1)
        server.dropUnsupported = false
        assertThat(runBlocking { uploader.drops(phone.api) }).isEqualTo(1)
        assertThat(server.blobs).doesNotContainKey(sha)
    }

    @Test
    fun `a record written without this device's preview is pushed again with it`() {
        val raw = Random(70).nextBytes(512)
        val photo = phone.addPhoto("DSC70", raw)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        previews()
        phone.sync()
        val sha = cloud(phone, photo)!!.previewSha!!
        val uuid = runBlocking { phone.db.photoDao().get(photo)!!.uuid }
        // Another device wrote the photo without having seen the preview.
        val record = server.record("photo", uuid)!!
        record.data = JsonObject((record.data as JsonObject).filterKeys { it != "preview" })
        server.bump("photo", uuid)
        assertThat(server.previewNamed(sha)).isFalse()
        phone.sync()
        assertThat(cloud(phone, photo)!!.previewSha).isEqualTo(sha)
        assertThat(phone.pending()).isAtLeast(1)
        phone.sync()
        assertThat(server.previewNamed(sha)).isTrue()
        tablet.sync()
        assertThat(remoteRow(raw).cloudPreview).isTrue()
    }

    @Test
    fun `a folder with backup off uploads the original of an edited photo and nothing for a rated one`() {
        runBlocking { phone.db.sourceDao().setBackup(phone.folder, false) }
        val editedBytes = Random(80).nextBytes(700)
        val ratedBytes = Random(81).nextBytes(700)
        val edited = addReadable("EDIT80", editedBytes)
        val rated = addReadable("RATE81", ratedBytes)
        runBlocking { phone.db.photoDao().setRating(listOf(rated), 5) }
        phone.sync()

        // Rating only: nothing goes up.
        assertThat(originals()).isEqualTo(0)
        assertThat(runBlocking { phone.db.syncDao().backupCandidates(10) }).isEmpty()
        assertThat(server.blobs).isEmpty()

        // An edit: its original is queued and uploaded (same uploader, same rules), no preview made.
        phone.now += 1_000
        phone.edit(phone.version(edited)) { it.copy(light = Light(exposureEv = 0.3f)) }
        assertThat(runBlocking { phone.db.syncDao().backupCandidates(10) }.map { it.photoId }).containsExactly(edited)
        assertThat(originals()).isEqualTo(1)
        assertThat(server.blobs.values.map { it.toList() }).containsExactly(editedBytes.toList())
        assertThat(server.originals).containsKey(FileFingerprint.of(editedBytes))
        previews(originalsOff = false)
        assertThat(maker.made).isEmpty()
        assertThat(server.blobs).hasSize(1)

        // The rated photo still syncs as metadata; the other device shows it without pixels.
        phone.sync()
        tablet.sync()
        assertThat(remoteRow(ratedBytes).rating).isEqualTo(5)
        assertThat(remoteRow(ratedBytes).cloudPreview).isFalse()
        assertThat(remoteRow(ratedBytes).cloudOriginal).isFalse()
        assertThat(remoteRow(editedBytes).cloudOriginal).isTrue()

        // Removing the edit never deletes the uploaded original.
        phone.now += 1_000
        phone.edit(phone.version(edited)) { Recipe() }
        runBlocking { uploader.markDrops() }
        phone.sync()
        runBlocking { uploader.drops(phone.api) }
        assertThat(server.originals).containsKey(FileFingerprint.of(editedBytes))
    }

    @Test
    fun `with the originals backup on, an older preview of an edited photo goes once its original is up`() {
        val bytes = Random(90).nextBytes(700)
        val photo = addReadable("DSC90", bytes)
        phone.edit(phone.version(photo)) { it.copy(light = Light(exposureEv = 1f)) }
        phone.sync()
        // 0.5.1 made it a preview; now the backup is on: no new preview, the original goes up.
        previews(originalsOff = true)
        phone.sync()
        val sha = cloud(phone, photo)!!.previewSha!!
        assertThat(runBlocking { phone.db.syncDao().previewCandidates(false, 10) }).isEmpty()
        assertThat(runBlocking { uploader.markDrops() }).isEqualTo(0) // kept while the original waits
        assertThat(originals()).isEqualTo(1)
        assertThat(runBlocking { uploader.markDrops() }).isEqualTo(1)
        phone.sync()
        runBlocking { uploader.drops(phone.api) }
        assertThat(server.blobs).doesNotContainKey(sha)
        tablet.sync()
        assertThat(remoteRow(bytes).cloudOriginal).isTrue()
        assertThat(remoteRow(bytes).cloudPreview).isFalse()
    }
}
