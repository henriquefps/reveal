package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.sync.BackupCoverage
import dev.hfps.reveal.sync.engine.FreedPhotos
import dev.hfps.reveal.sync.engine.SyncStore
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Freed-adopt (Hoard ADR 0024 item 6, ADR 0018 "R1") against [FakeSyncServer] with `backup`: a folder photo whose
 * files vanished from the phone (the walk keeps it, `vanished_at`) is adopted by the Server source in place when Hoard
 * freed it, found by fingerprint or by its mirror location (size and time confirmed); a deleted one only for its edit;
 * one the mirror still sees on the phone waits; anything else goes as before.
 */
@RunWith(AndroidJUnit4::class)
class FreedAdoptTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var phone: TestDevice
    private lateinit var root: FakeSyncServer.Root
    private var camera = 0L

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
        server.backup()
        // A device that reports itself: its physical device id is known after the first sync (ownership needs it).
        phone = TestDevice(
            "Phone",
            url,
            server.addDevice("Phone"),
            server.serverId,
            device = dev.hfps.reveal.sync.account.ClientDevice("reveal", "Google", "Pixel 8"),
        )
        camera = runBlocking {
            phone.db.sourceDao().insert(
                SourceEntity(
                    treeUri = "content://${BackupCoverage.EXTERNAL_STORAGE_AUTHORITY}/tree/primary%3ADCIM%2FCamera",
                    rootDocumentId = "primary:DCIM/Camera",
                    displayName = "Camera",
                    volumeId = "primary",
                    volumeName = null,
                    state = SourceState.AVAILABLE,
                    writable = false,
                    addedAt = 0,
                ),
            )
        }
        root = server.addRoot(phone.api.server().deviceId, "primary", "DCIM/Camera")
        phone.sync()
    }

    @After
    fun tearDown() {
        phone.close()
        server.shutdown()
    }

    private fun bytes(seed: Int) = Random(seed).nextBytes(2500)

    /** A JPEG of the camera folder (or [source]); [printed] false = Reveal has not fingerprinted it yet. */
    private fun photo(
        name: String,
        bytes: ByteArray,
        modified: Long,
        printed: Boolean = true,
        source: Long = camera,
    ): Long = runBlocking {
        val id = phone.db.photoDao().insertPhoto(
            PhotoEntity(
                sourceId = source, folder = "", folderDocumentId = "primary:DCIM/Camera",
                stemKey = name.substringBeforeLast('.').lowercase(), name = name, format = PhotoFormat.JPEG,
                hasRaw = false, sortTime = modified, addedAt = 0,
            ),
        )
        phone.db.photoDao().insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = id, sourceId = source, relativePath = name, documentId = "primary:DCIM/Camera/$name",
                    name = name, size = bytes.size.toLong(), modified = modified, role = FileRole.RENDERED,
                    fingerprint = FileFingerprint.of(bytes).takeIf { printed },
                ),
            ),
        )
        phone.db.photoDao().insertVersion(VersionEntity(photoId = id, createdAt = 0))
        id
    }

    /** What a walk does when the photo's files are gone (paired: every such photo waits). */
    private fun vanish(vararg photoIds: Long) = runBlocking {
        for (id in photoIds) phone.db.photoDao().markVanished(phone.db.photoDao().files(id).map { it.id }, phone.now)
    }

    private fun mirror(name: String, bytes: ByteArray, modified: Long, gone: String?, onDevice: Boolean = false) =
        server.addMirrorItem(root, "primary", "DCIM/Camera/$name", bytes, modified, onDevice, gone)

    private fun get(id: Long) = runBlocking { phone.db.photoDao().get(id) }

    private fun files(id: Long) = runBlocking { phone.db.photoDao().files(id) }

    private fun serverSource() = runBlocking { checkNotNull(phone.db.syncDao().serverSource()).id }

    private fun listed() = runBlocking { phone.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())) }.map { it.id }

    private fun edit(id: Long) = phone.edit(phone.version(id)) { it.copy(light = it.light.copy(exposureEv = 0.6f)) }

    @Test
    fun `a freed photo with a fingerprint is adopted in place with its edit, culling and uuid`() {
        val bytes = bytes(1)
        val id = photo("PXL_1.jpg", bytes, 1_730_000_000_000L)
        edit(id)
        phone.flag(id, PickFlag.PICK)
        val uuid = get(id)!!.uuid
        mirror("PXL_1.jpg", bytes, 1_730_000_000_000L, gone = "freed")
        vanish(id)
        assertThat(listed()).doesNotContain(id)

        val report = phone.sync().freed

        assertThat(report.adopted).isEqualTo(1)
        val adopted = get(id)!!
        assertThat(adopted.sourceId).isEqualTo(serverSource())
        assertThat(adopted.uuid).isEqualTo(uuid)
        assertThat(adopted.flag).isEqualTo(PickFlag.PICK)
        assertThat(adopted.folder).isEqualTo("DCIM/Camera")
        assertThat(phone.recipe(phone.version(id)).light.exposureEv).isEqualTo(0.6f)
        val file = files(id).single()
        assertThat(file.relativePath).startsWith(SyncStore.FREED_PREFIX)
        assertThat(file.onServer).isTrue()
        assertThat(file.vanishedAt).isNull()
        assertThat(runBlocking { phone.db.syncDao().cloud(id) }!!.originalOnServer).isTrue()
        assertThat(listed()).contains(id)
        // The NAS feed and later syncs leave it where it is.
        phone.sync()
        assertThat(get(id)!!.sourceId).isEqualTo(serverSource())
    }

    @Test
    fun `a freed photo Reveal never fingerprinted is found by its mirror location, size and time`() {
        val bytes = bytes(2)
        val time = 1_730_000_000_456L
        val id = photo("PXL_2.jpg", bytes, time, printed = false)
        // Hoard's location keeps whole seconds (MediaStore): the same second matches.
        mirror("PXL_2.jpg", bytes, 1_730_000_000_000L, gone = "freed")
        // Another file at another path, same size: never this one.
        val decoy = bytes(3)
        val other = photo("PXL_3.jpg", decoy, time, printed = false)
        mirror("PXL_3.jpg", decoy + byteArrayOf(1), time, gone = "freed")
        vanish(id, other)

        val report = phone.sync().freed

        assertThat(report.adopted).isEqualTo(1)
        assertThat(get(id)!!.sourceId).isEqualTo(serverSource())
        assertThat(files(id).single().fingerprint).isEqualTo(FileFingerprint.of(bytes))
        assertThat(server.folderQueries).isAtLeast(1)
        // PXL_3's size differs: no match, and with no edit it goes as before.
        assertThat(get(other)).isNull()
    }

    @Test
    fun `a deleted photo is adopted for its edit, otherwise it goes`() {
        val kept = bytes(4)
        val gone = bytes(5)
        val edited = photo("PXL_4.jpg", kept, 1_730_000_000_000L)
        val plain = photo("PXL_5.jpg", gone, 1_730_000_000_000L)
        edit(edited)
        mirror("PXL_4.jpg", kept, 1_730_000_000_000L, gone = "deleted")
        mirror("PXL_5.jpg", gone, 1_730_000_000_000L, gone = "deleted")
        vanish(edited, plain)

        val report = phone.sync().freed

        assertThat(report.adopted).isEqualTo(1)
        assertThat(report.deleted).isEqualTo(1)
        assertThat(get(edited)!!.sourceId).isEqualTo(serverSource())
        assertThat(get(plain)).isNull()
    }

    @Test
    fun `an unknown reason counts as deleted, and a photo the server never had goes as before`() {
        val strange = bytes(6)
        val unknown = photo("PXL_6.jpg", strange, 1_730_000_000_000L)
        mirror("PXL_6.jpg", strange, 1_730_000_000_000L, gone = "recycled")
        val never = photo("PXL_7.jpg", bytes(7), 1_730_000_000_000L)
        edit(never)
        vanish(unknown, never)

        val report = phone.sync().freed

        assertThat(report.deleted).isEqualTo(2)
        assertThat(get(unknown)).isNull()
        assertThat(get(never)).isNull()
    }

    @Test
    fun `a photo the mirror still sees on the phone waits for a later walk`() {
        val bytes = bytes(8)
        val id = photo("PXL_8.jpg", bytes, 1_730_000_000_000L)
        mirror("PXL_8.jpg", bytes, 1_730_000_000_000L, gone = null, onDevice = true)
        vanish(id)

        val report = phone.sync().freed

        assertThat(report.kept).isEqualTo(1)
        assertThat(get(id)!!.sourceId).isEqualTo(camera)
        assertThat(files(id).single().vanishedAt).isNotNull()
    }

    @Test
    fun `a server without backup cannot have freed anything, so vanished photos go at once`() {
        val bytes = bytes(9)
        val id = photo("PXL_9.jpg", bytes, 1_730_000_000_000L)
        edit(id)
        mirror("PXL_9.jpg", bytes, 1_730_000_000_000L, gone = "freed")
        vanish(id)
        server.features -= "backup"

        assertThat(phone.sync().freed.deleted).isEqualTo(1)
        assertThat(get(id)).isNull()
    }

    /** A folder of Android's storage outside every root of this phone (Pictures). */
    private fun pictures(): Long = runBlocking {
        phone.db.sourceDao().insert(
            SourceEntity(
                treeUri = "content://${BackupCoverage.EXTERNAL_STORAGE_AUTHORITY}/tree/primary%3APictures" +
                    System.nanoTime(),
                rootDocumentId = "primary:Pictures",
                displayName = "Pictures",
                volumeId = "primary",
                volumeName = null,
                state = SourceState.AVAILABLE,
                writable = false,
                addedAt = 0,
            ),
        )
    }

    private fun clearVanished(id: Long) = runBlocking {
        phone.db.photoDao().clearVanished(phone.db.photoDao().files(id).map { it.id })
    }

    @Test
    fun `a refused item lookup or folder query keeps a photo instead of deleting it`() {
        // Known by fingerprint only (outside every root): the item lookup is refused.
        val printed = photo("IMG_20.jpg", bytes(20), 0, source = pictures())
        edit(printed)
        // Known by path only, under the root: the folder query is refused.
        val byPath = photo("PXL_21.jpg", bytes(21), 0, printed = false)
        vanish(printed, byPath)
        server.refuseItemLookups = "invalid_request"
        server.refuseFolderQueries = true

        val report = phone.sync().freed

        assertThat(report.deleted).isEqualTo(0)
        assertThat(report.kept).isEqualTo(2)
        assertThat(get(printed)).isNotNull()
        assertThat(get(byPath)).isNotNull()

        // Not asked again at once (the next pass waits an hour), then answered: the server has nothing for either.
        server.refuseItemLookups = null
        server.refuseFolderQueries = false
        assertThat(phone.sync().freed.deleted).isEqualTo(0)
        phone.now += FreedPhotos.FIRST_RETRY_MS + 1
        assertThat(phone.sync().freed.deleted).isEqualTo(2)
    }

    @Test
    fun `photos past the per-pass read cap wait for the next pass instead of going`() {
        val folder = pictures()
        val ids = (0..FreedPhotos.MAX_ITEM_READS).map { i ->
            val bytes = bytes(1000 + i)
            server.addMirrorItem(root, "primary", "Pictures/IMG_$i.jpg", bytes, 0, onDevice = false, gone = "freed")
            photo("IMG_$i.jpg", bytes, 0, source = folder)
        }
        vanish(*ids.toLongArray())

        val first = phone.sync().freed
        assertThat(first.adopted).isEqualTo(FreedPhotos.MAX_ITEM_READS)
        assertThat(first.deleted).isEqualTo(0)
        assertThat(first.kept).isEqualTo(1)
        phone.now += FreedPhotos.FIRST_RETRY_MS + 1
        assertThat(phone.sync().freed.adopted).isEqualTo(1)
        assertThat(ids.all { get(it)!!.sourceId == serverSource() }).isTrue()
    }

    @Test
    fun `a photo a walk lists again while the server is asked is neither adopted nor deleted`() {
        val folder = pictures()
        val freed = bytes(30)
        val adoptable = photo("IMG_30.jpg", freed, 0, source = folder)
        server.addMirrorItem(root, "primary", "Pictures/IMG_30.jpg", freed, 0, onDevice = false, gone = "freed")
        val plain = photo("IMG_31.jpg", bytes(31), 0, source = folder)
        server.culling.addItem(listOf(FileFingerprint.of(bytes(31))))
        vanish(adoptable, plain)
        // The walk finds both files again while the server answers.
        server.onItemRead = {
            clearVanished(adoptable)
            clearVanished(plain)
        }

        val report = phone.sync().freed

        assertThat(report.adopted).isEqualTo(0)
        assertThat(report.deleted).isEqualTo(0)
        assertThat(get(adoptable)!!.sourceId).isEqualTo(folder)
        assertThat(get(plain)!!.sourceId).isEqualTo(folder)
        assertThat(files(adoptable).single().relativePath).isEqualTo("IMG_30.jpg")
    }

    @Test
    fun `another phone's mirror location at the same path is never this photo's`() {
        val other = server.addRoot("another-phone", "primary", "DCIM/Camera")
        val freedThere = bytes(40)
        val seenThere = bytes(41)
        val a = photo("PXL_40.jpg", freedThere, 0)
        val b = photo("PXL_41.jpg", seenThere, 0)
        server.addMirrorItem(
            other,
            "primary",
            "DCIM/Camera/PXL_40.jpg",
            freedThere,
            0,
            onDevice = false,
            gone = "freed",
        )
        server.addMirrorItem(other, "primary", "DCIM/Camera/PXL_41.jpg", seenThere, 0, onDevice = true, gone = null)
        vanish(a, b)

        val report = phone.sync().freed

        // Neither the other phone's "freed" (adopting an unedited photo this phone deleted) nor its "on the device"
        // (keeping it a week): no location of this phone, no edit, so it goes as before.
        assertThat(report.adopted).isEqualTo(0)
        assertThat(report.deleted).isEqualTo(2)
    }

    @Test
    fun `a photo the mirror still sees on the phone is not asked about again for six hours`() {
        val bytes = bytes(50)
        val id = photo("PXL_50.jpg", bytes, 1_730_000_000_000L, printed = false)
        mirror("PXL_50.jpg", bytes, 1_730_000_000_000L, gone = null, onDevice = true)
        vanish(id)
        phone.sync()
        val queries = server.folderQueries

        assertThat(phone.sync().freed.kept).isEqualTo(1)
        assertThat(server.folderQueries).isEqualTo(queries)

        phone.now += FreedPhotos.BACKOFF_MS + 1
        phone.sync()
        assertThat(server.folderQueries).isGreaterThan(queries)
    }

    @Test
    fun `before this phone's physical device is known nothing unmatched is deleted`() {
        runBlocking { phone.db.metaDao().delete(dev.hfps.reveal.sync.engine.SyncEngine.KEY_PHYSICAL_DEVICE) }
        val id = photo("PXL_60.jpg", bytes(60), 0)
        vanish(id)

        assertThat(phone.sync().freed.deleted).isEqualTo(0)
        assertThat(get(id)).isNotNull()
    }

    @Test
    fun `a paired server that does not answer this run decides nothing, even if it listed backup before`() {
        val bytes = bytes(11)
        val id = photo("PXL_11.jpg", bytes, 1_730_000_000_000L)
        vanish(id)
        // The last run stored the server's backup roots; now it is offline (the features list is never reused).
        assertThat(runBlocking { phone.db.metaDao().get(BackupCoverage.META_ROOTS) }).isNotNull()
        server.shutdown()

        assertThat(runCatching { phone.sync() }.isFailure).isTrue()

        assertThat(get(id)!!.sourceId).isEqualTo(camera)
        assertThat(files(id).single().vanishedAt).isNotNull()
    }

    @Test
    fun `a freed hand-off-like folder file outside any root is still found by fingerprint`() {
        // A folder Reveal did not see as covered (another volume's path): the fingerprint lookup finds the item.
        val elsewhere = runBlocking {
            phone.db.sourceDao().insert(
                SourceEntity(
                    treeUri = "content://${BackupCoverage.EXTERNAL_STORAGE_AUTHORITY}/tree/primary%3APictures",
                    rootDocumentId = "primary:Pictures", displayName = "Pictures", volumeId = "primary",
                    volumeName = null, state = SourceState.AVAILABLE, writable = false, addedAt = 0,
                    kind = SourceKind.FOLDER,
                ),
            )
        }
        val bytes = bytes(10)
        val id = runBlocking {
            val photo = phone.db.photoDao().insertPhoto(
                PhotoEntity(
                    sourceId = elsewhere, folder = "", folderDocumentId = "", stemKey = "img_10", name = "IMG_10.jpg",
                    format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
                ),
            )
            phone.db.photoDao().insertFiles(
                listOf(
                    PhotoFileEntity(
                        photoId = photo, sourceId = elsewhere, relativePath = "IMG_10.jpg", documentId = "x",
                        name = "IMG_10.jpg", size = bytes.size.toLong(), modified = 0, role = FileRole.RENDERED,
                        fingerprint = FileFingerprint.of(bytes),
                    ),
                ),
            )
            phone.db.photoDao().insertVersion(VersionEntity(photoId = photo, createdAt = 0))
            photo
        }
        server.addMirrorItem(root, "primary", "Pictures/IMG_10.jpg", bytes, 0, onDevice = false, gone = "freed")
        vanish(id)

        assertThat(phone.sync().freed.adopted).isEqualTo(1)
        assertThat(get(id)!!.sourceId).isEqualTo(serverSource())
    }
}
