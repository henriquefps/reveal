package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.ImageInput
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.SyncUploadEntity
import dev.hfps.reveal.data.handoff.HandoffMerge
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.storage.DocumentEntry
import dev.hfps.reveal.data.storage.DocumentTree
import dev.hfps.reveal.pipeline.preview.SmartPreviewCodec
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.blobs.BlobUploader
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Sync around hand-offs (ADR 0011, ADR 0018): a hand-off merged into a folder photo stays one photo on every device,
 * and an original Reveal can no longer read is parked (its smart preview goes instead), never retried in a loop.
 */
@RunWith(AndroidJUnit4::class)
class HandoffSyncTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        url = server.start()
        dir = Files.createTempDirectory("handoff-sync").toFile()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId).also { devices += it }

    private fun photos(device: TestDevice): List<PhotoEntity> = runBlocking {
        device.db.photoDao().getAll((1L..200L).toList())
    }

    @Test
    fun `a hand-off merged into a folder photo the server never saw stays one photo with its edit elsewhere`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val jpeg = Random(11).nextBytes(4096)
        val handoff = phone.addHandoffPhoto("IMG1", jpeg)
        phone.edit(phone.version(handoff)) { it.copy(light = it.light.copy(exposureEv = 0.6f)) }
        phone.sync()
        tablet.sync()
        assertThat(photos(tablet)).hasSize(1)

        phone.now += 1_000
        phone.addFolderJpeg("IMG1", jpeg)
        runBlocking { HandoffMerge(phone.db) { phone.now }.mergeInto(phone.folder) }
        phone.sync()
        tablet.sync()

        val onPhone = photos(phone).single()
        assertThat(onPhone.sourceId).isEqualTo(phone.folder)
        val onTablet = photos(tablet).single()
        assertThat(onTablet.uuid).isEqualTo(onPhone.uuid)
        assertThat(tablet.recipe(tablet.version(onTablet.id)).light.exposureEv).isEqualTo(0.6f)
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `when the server knew both photos, the other device keeps one photo under the folder photo's id`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val jpeg = Random(12).nextBytes(4096)
        val handoff = phone.addHandoffPhoto("IMG2", jpeg)
        phone.edit(phone.version(handoff)) { it.copy(light = it.light.copy(exposureEv = -0.4f)) }
        phone.sync()
        phone.now += 1_000
        val folderPhoto = phone.addFolderJpeg("IMG2", jpeg)
        phone.sync() // the folder photo reaches the server under its own id
        tablet.sync()
        assertThat(photos(tablet)).hasSize(1) // the tablet already sees one photo (an alias)

        phone.now += 1_000
        runBlocking { HandoffMerge(phone.db) { phone.now }.mergeInto(phone.folder) }
        phone.sync()
        tablet.sync()
        tablet.sync()

        val folderUuid = runBlocking { phone.db.photoDao().get(folderPhoto)!!.uuid }
        assertThat(photos(phone).map { it.id }).containsExactly(folderPhoto)
        val onTablet = photos(tablet).single()
        assertThat(onTablet.uuid).isEqualTo(folderUuid)
        val versions = tablet.versions(onTablet.id)
        assertThat(versions.map { tablet.recipe(it.id).light.exposureEv }).contains(-0.4f)
        assertThat(runBlocking { tablet.db.syncDao().inbox() }).isEmpty()
    }

    // ------------------------------------------------------------------ unreadable originals

    /** A tree whose documents refuse to open, as a gallery URI after its grant ended. */
    private class RefusingTree : DocumentTree {
        var opens = 0
        override val rootDocumentId = ""

        override fun children(documentId: String) = emptyList<DocumentEntry>()

        override fun stat(documentId: String): DocumentEntry? = null

        override fun openInput(documentId: String, name: String): ImageInput {
            opens++
            throw SecurityException("Permission Denial: no access to $documentId")
        }

        override fun readBytes(documentId: String, maxBytes: Int): ByteArray = throw SecurityException("denied")

        override fun writeBytes(documentId: String, bytes: ByteArray) = throw IOException("read only")

        override fun create(parentDocumentId: String, name: String, mimeType: String) = throw IOException("no")

        override fun uri(documentId: String): android.net.Uri = android.net.Uri.parse(documentId)
    }

    @Test
    fun `an original that cannot be read is parked, its local smart preview goes up, nothing loops`() {
        val phone = device("Phone")
        val jpeg = Random(13).nextBytes(4096)
        val photo = phone.addHandoffPhoto("IMG3", jpeg)
        phone.sync() // the lookup: the server does not have it
        val tree = RefusingTree()
        val previews = LocalPreviews(File(dir, "local"))
        val print = FileFingerprint.of(jpeg)
        previews.dir.mkdirs()
        SmartPreviewCodec.encode(SyntheticImages.grayRamp(16, 12), byteArrayOf(1, 2), 64, 48)
            .let { previews.smartFile(print).writeBytes(it) }
        val uploader = BlobUploader(
            phone.db,
            phone.store,
            DocumentTreeFactory { tree },
            SmartPreviewMaker(File(dir, "made")),
            previews,
        ) { phone.now }

        val sent = runBlocking { uploader.originals(phone.api, 10) }

        assertThat(sent).isEqualTo(0)
        assertThat(tree.opens).isEqualTo(1)
        val dao = phone.db.syncDao()
        assertThat(runBlocking { dao.pendingUploads(SyncUploadEntity.KIND_ORIGINAL, 10) }).isEmpty()
        assertThat(runBlocking { phone.db.photoDao().files(photo).single().unreadableAt }).isNotNull()
        // The next runs do not touch it again (no backup candidate while it cannot be read).
        repeat(3) { runBlocking { uploader.originals(phone.api, 10) } }
        assertThat(tree.opens).isEqualTo(1)
        assertThat(runBlocking { dao.backupCandidates(10) }).isEmpty()

        // Its smart preview is what the server gets: the local one, as it is (no read of the original).
        val previewsDone = runBlocking { uploader.previews(phone.api, 10, noBackup = false) }
        assertThat(previewsDone).isAtLeast(1)
        assertThat(tree.opens).isEqualTo(1)
        val cloud = runBlocking { dao.cloud(photo) }!!
        assertThat(cloud.previewSha).isNotNull()
        assertThat(cloud.previewWidth).isEqualTo(16)
        assertThat(server.blobs[cloud.previewSha]).isEqualTo(previews.smartFile(print).readBytes())

        // Readable again (shared again, or gallery access given): queued for the backup once more.
        runBlocking { phone.db.photoDao().setReadable(phone.db.photoDao().files(photo).single().documentId) }
        assertThat(runBlocking { dao.backupCandidates(10) }.map { it.photoId }).containsExactly(photo)
    }

    @Test
    fun `a blob run that moved nothing forward does not ask for another one at once`() {
        val phone = device("Phone")
        val jpeg = Random(14).nextBytes(4096)
        phone.addHandoffPhoto("IMG4", jpeg)
        phone.sync()
        val tree = RefusingTree()
        val uploader = BlobUploader(
            phone.db,
            phone.store,
            DocumentTreeFactory { tree },
            SmartPreviewMaker(File(dir, "made")),
        ) { phone.now }
        val accounts = FakeAccounts(phone.api)
        val repository = accounts.repository(phone, uploader)

        // The original cannot be read and there is no preview to make: no run asks for another one at once.
        assertThat(runBlocking { repository.syncBlobs(originalsAllowed = true) }).isFalse()
        assertThat(tree.opens).isEqualTo(1) // the original, parked
        assertThat(runBlocking { repository.syncBlobs(originalsAllowed = true) }).isFalse()
        assertThat(tree.opens).isEqualTo(2) // its smart preview (now owed), set aside
        repeat(3) { assertThat(runBlocking { repository.syncBlobs(originalsAllowed = true) }).isFalse() }
        assertThat(tree.opens).isEqualTo(2)
    }

    @Test
    fun `a failure is logged with its class, message and the place in Reveal's code`() {
        val error = runCatching { require(false) { "Cannot round NaN value." } }.exceptionOrNull()!!
        val text = BlobUploader.describe(error)
        assertThat(text).startsWith("IllegalArgumentException: Cannot round NaN value. at HandoffSyncTest.")
        // Other failures without their message (it may hold a URI).
        val denied = BlobUploader.describe(SecurityException("content://media/external/1"))
        assertThat(denied).startsWith("SecurityException at ")
        assertThat(denied).doesNotContain("content://")
    }

    /** A [SyncRepository] over one test device (paired, options default). */
    private class FakeAccounts(private val api: dev.hfps.reveal.sync.api.SyncApi) {
        fun repository(device: TestDevice, uploader: BlobUploader): SyncRepository {
            val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            val accounts = dev.hfps.reveal.sync.account.SyncAccountStore(
                context,
                dev.hfps.reveal.sync.account.MemorySecretStore(),
            )
            accounts.save(SyncAccount("http://localhost", "server", "Server", "device", 0), "key")
            return SyncRepository(
                device.db,
                accounts,
                device.store,
                device.engine,
                uploader,
                dev.hfps.reveal.sync.files.ServerFiles(device.db, { null }, context.cacheDir),
                SyncRepository.ClientInfo("Phone", "android", "test"),
                newApi = { _, _ -> api },
            )
        }
    }
}
