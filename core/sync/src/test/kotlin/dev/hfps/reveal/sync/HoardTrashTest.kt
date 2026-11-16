package dev.hfps.reveal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.sync.engine.EditPreviews
import dev.hfps.reveal.sync.engine.EditRenderer
import dev.hfps.reveal.sync.engine.EditReport
import dev.hfps.reveal.sync.engine.RenderedEdit
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hoard's trash as Reveal sees it (Hoard ADR 0023 "What Reveal sees", ADR 0018 "R1"), against [FakeSyncServer] with
 * `trash`: a NAS photo moved to the trash is hidden (its filter shows it), a restore brings it back with its uuid,
 * edits and culling, a purge leaves an edited photo unavailable (never a cell that fails to open), a photo the owner
 * forgot comes back when its file does, and edited previews survive a trash and restore without a new upload.
 */
@RunWith(AndroidJUnit4::class)
class HoardTrashTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
        server.trash()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
    }

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId).also { devices += it }

    private fun bytes(seed: Int, size: Int = 2048) = Random(seed).nextBytes(size)

    private fun fp(bytes: ByteArray) = FileFingerprint.of(bytes)

    private fun rows(device: TestDevice, filters: PhotoFilters = PhotoFilters.NONE): List<PhotoRow> = runBlocking {
        device.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(filters = filters)))
    }

    private val trashFilter = PhotoFilters(sync = setOf(SyncFilter.IN_TRASH))

    private fun library(device: TestDevice) = LibraryRepository(
        ApplicationProvider.getApplicationContext<Context>().contentResolver,
        device.db,
        null,
        DocumentTreeFactory { error("no reads") },
        SidecarSync(device.db, DocumentTreeFactory { error("no reads") }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { device.now },
    )

    /** A NAS JPEG the tablet syncs and edits; returns its photo id. */
    private fun editedNasPhoto(tablet: TestDevice, jpeg: ByteArray, path: String = "2026/DSC1.JPG"): Long {
        server.addNasFile("f1", "NAS", path, jpeg, fp(jpeg))
        tablet.sync()
        val photo = checkNotNull(tablet.photoByFingerprint(fp(jpeg)))
        tablet.edit(tablet.version(photo.id)) { it.copy(light = it.light.copy(exposureEv = 0.4f)) }
        runBlocking { tablet.db.photoDao().setRating(listOf(photo.id), 4) }
        tablet.sync()
        return photo.id
    }

    @Test
    fun `a trashed NAS photo is hidden, listed under its filter, and a restore gives it back with its edit`() {
        val tablet = device("Tablet")
        val jpeg = bytes(1)
        val photo = editedNasPhoto(tablet, jpeg)
        val uuid = tablet.photo(photo).uuid
        assertThat(rows(tablet).map { it.id }).contains(photo)

        server.trashNasFile("f1", "2026/DSC1.JPG")
        tablet.sync()

        // Kept, with its file marked trashed; out of the home, the Server scope and the counts.
        assertThat(tablet.photo(photo).uuid).isEqualTo(uuid)
        assertThat(rows(tablet).map { it.id }).doesNotContain(photo)
        assertThat(rows(tablet, PhotoFilters(sources = setOf(dev.hfps.reveal.data.model.SourceFilter.ServerOnly))))
            .isEmpty()
        val counts = runBlocking { tablet.db.photoDao().observeFilterCounts().first() }
        assertThat(counts.total).isEqualTo(0)
        assertThat(counts.inTrash).isEqualTo(1)
        assertThat(runBlocking { tablet.db.syncDao().observeServerPhotoCount().first() }).isEqualTo(0)
        // Its filter lists it, as a photo in the trash (a placeholder, never opened).
        val trashed = rows(tablet, trashFilter).single()
        assertThat(trashed.id).isEqualTo(photo)
        assertThat(trashed.inTrash).isTrue()
        assertThat(trashed.unavailable).isFalse()
        assertThat(runBlocking { library(tablet).isInTrash(photo) }).isTrue()

        server.restoreNasFile("f1", "2026/DSC1.JPG")
        tablet.sync()

        val back = rows(tablet).single { it.id == photo }
        assertThat(back.inTrash).isFalse()
        assertThat(back.rating).isEqualTo(4)
        assertThat(tablet.photo(photo).uuid).isEqualTo(uuid)
        assertThat(tablet.recipe(tablet.version(photo)).light.exposureEv).isEqualTo(0.4f)
        assertThat(rows(tablet, trashFilter)).isEmpty()
        assertThat(runBlocking { library(tablet).isInTrash(photo) }).isFalse()
    }

    @Test
    fun `a purge leaves an edited photo unavailable, an unedited one leaves the library`() {
        val tablet = device("Tablet")
        val edited = bytes(2)
        val plain = bytes(3)
        val photo = editedNasPhoto(tablet, edited)
        server.addNasFile("f1", "NAS", "2026/DSC2.JPG", plain, fp(plain))
        tablet.sync()
        val plainPhoto = checkNotNull(tablet.photoByFingerprint(fp(plain))).id

        server.trashNasFile("f1", "2026/DSC1.JPG")
        server.trashNasFile("f1", "2026/DSC2.JPG")
        tablet.sync()
        assertThat(rows(tablet, trashFilter).map { it.id }).containsExactly(photo, plainPhoto)

        server.purgeNasFile("f1", "2026/DSC1.JPG")
        server.purgeNasFile("f1", "2026/DSC2.JPG")
        tablet.sync()

        // The edited one stays for its edit: listed, unavailable (the placeholder and Forget), not "on the server".
        val row = rows(tablet).single { it.id == photo }
        assertThat(row.unavailable).isTrue()
        assertThat(row.cloudOriginal).isFalse()
        assertThat(row.inTrash).isFalse()
        assertThat(runBlocking { library(tablet).isUnavailable(photo) }).isTrue()
        assertThat(rows(tablet, trashFilter)).isEmpty()
        // The unedited one had nothing to keep.
        assertThat(runBlocking { tablet.db.photoDao().get(plainPhoto) }).isNull()
    }

    @Test
    fun `a server without trash purges at once, and an edited photo is unavailable instead of a broken cell`() {
        server.features -= "trash"
        val tablet = device("Tablet")
        val jpeg = bytes(4)
        val photo = editedNasPhoto(tablet, jpeg)

        server.removeNasFile("f1", "2026/DSC1.JPG")
        server.nasBytes.remove(fp(jpeg))
        tablet.sync()

        val row = rows(tablet).single { it.id == photo }
        assertThat(row.unavailable).isTrue()
        assertThat(row.cloudOriginal).isFalse()
        assertThat(runBlocking { tablet.db.photoDao().files(photo) }).isEmpty()

        // The file comes back on the NAS (same path and content): the photo is whole again, linked by folder + stem.
        server.addNasFile("f1", "NAS", "2026/DSC1.JPG", jpeg, fp(jpeg))
        tablet.sync()
        val again = rows(tablet).single { it.id == photo }
        assertThat(again.unavailable).isFalse()
        assertThat(again.cloudOriginal).isTrue()
        assertThat(tablet.recipe(tablet.version(photo)).light.exposureEv).isEqualTo(0.4f)
    }

    @Test
    fun `a photo forgotten as unavailable comes back with its file, one removed from the library does not`() {
        val tablet = device("Tablet")
        val forgotten = bytes(5)
        val removed = bytes(6)
        val photo = editedNasPhoto(tablet, forgotten)
        val other = editedNasPhoto(tablet, removed, path = "2026/DSC9.JPG")
        // "Remove from library" (not Forget): hidden for its own reason.
        runBlocking { tablet.db.photoDao().setHidden(listOf(other), true) }

        server.purgeNasFile("f1", "2026/DSC1.JPG")
        tablet.sync()
        assertThat(rows(tablet).single { it.id == photo }.unavailable).isTrue()
        runBlocking { library(tablet).forget(listOf(photo)) }
        assertThat(rows(tablet).map { it.id }).doesNotContain(photo)
        assertThat(tablet.photo(photo).forgottenAt).isNotNull()

        // Its file is back on the server: shown again, on every device (its record goes up with hidden = false).
        server.addNasFile("f1", "NAS", "2026/DSC1.JPG", forgotten, fp(forgotten))
        tablet.sync()
        assertThat(rows(tablet).map { it.id }).contains(photo)
        assertThat(tablet.photo(photo).hidden).isFalse()
        assertThat(tablet.photo(photo).forgottenAt).isNull()
        val record = server.record("photo", tablet.photo(photo).uuid)!!
        assertThat(record.data.toString()).contains("\"hidden\":false")

        // The photo removed from the library stays hidden through a trash and a restore.
        server.trashNasFile("f1", "2026/DSC9.JPG")
        tablet.sync()
        server.restoreNasFile("f1", "2026/DSC9.JPG")
        tablet.sync()
        assertThat(tablet.photo(other).hidden).isTrue()
    }

    @Test
    fun `a JPEG trashed alone leaves its pair live with the RAW, and comes back on restore`() {
        val tablet = device("Tablet")
        val raw = bytes(10, 4096)
        val jpeg = bytes(11)
        server.addNasFile("f1", "NAS", "2026/DSC10.ARW", raw, fp(raw))
        server.addNasFile("f1", "NAS", "2026/DSC10.JPG", jpeg, fp(jpeg))
        tablet.sync()
        val photo = checkNotNull(tablet.photoByFingerprint(fp(raw))).id

        // Hoard ADR 0023 amendment S1: the JPEG alone is a trashed item of its own; the RAW stays live.
        server.trashNasFile("f1", "2026/DSC10.JPG")
        tablet.sync()

        val row = rows(tablet).single { it.id == photo }
        assertThat(row.inTrash).isFalse()
        assertThat(row.cloudOriginal).isTrue()
        assertThat(rows(tablet, trashFilter)).isEmpty()
        val files = runBlocking { tablet.db.photoDao().files(photo) }
        assertThat(
            dev.hfps.reveal.data.sync.ServerTree.reachable(files, dev.hfps.reveal.data.sync.ServerTree::servable).map {
                it.name
            },
        )
            .containsExactly("DSC10.ARW")

        server.restoreNasFile("f1", "2026/DSC10.JPG")
        tablet.sync()
        val back = runBlocking { tablet.db.photoDao().files(photo) }
        assertThat(back.map { it.name }).containsExactly("DSC10.ARW", "DSC10.JPG")
        assertThat(back.all { it.trashedAt == null }).isTrue()
    }

    @Test
    fun `a purged NAS file makes this device ask again about its own copy, which is then backed up`() {
        val phone = device("Phone")
        val jpeg = bytes(12)
        server.addNasFile("f1", "NAS", "2026/IMG12.JPG", jpeg, fp(jpeg))
        val local = phone.addFolderJpeg("IMG12", jpeg)
        phone.sync()
        // The NAS holds the same file: the phone's copy counts as on the server (no backup).
        assertThat(runBlocking { phone.db.syncDao().cloud(local) }!!.originalOnServer).isTrue()

        // Trashed: still served (the fingerprint lookup finds it), nothing changes for the phone's copy.
        server.trashNasFile("f1", "2026/IMG12.JPG")
        phone.sync()
        assertThat(runBlocking { phone.db.syncDao().cloud(local) }!!.originalOnServer).isTrue()

        // Purged: the phone asks again, the server misses it, and it is a backup candidate again.
        server.purgeNasFile("f1", "2026/IMG12.JPG")
        phone.sync()
        assertThat(runBlocking { phone.db.syncDao().cloud(local) }!!.originalOnServer).isFalse()
        assertThat(runBlocking { phone.db.syncDao().backupCandidates(10) }.map { it.photoId }).contains(local)
    }

    @Test
    fun `an edited preview survives a trash and a restore without a new upload`() {
        server.hoard(edits = true)
        server.trash()
        val phone = device("Phone")
        val raw = bytes(7, 4096)
        val itemId = server.culling.addItem(listOf(fp(raw)))
        val photo = phone.addPhoto("DSC7", raw)
        phone.edit(phone.version(photo)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        val renderer = EditRenderer { _, versionId, maxEdge, _ ->
            RenderedEdit(ByteArray(64) { versionId.toByte() }, maxEdge, maxEdge * 2 / 3)
        }
        fun pass(): EditReport {
            phone.sync()
            phone.now += EditPreviews.SETTLE_MS + 1_000
            return runBlocking { EditPreviews(phone.db, renderer) { phone.now }.run(phone.api, maxRenders = 10) }
        }
        assertThat(pass().uploaded).isEqualTo(1)
        val puts = server.edits.puts.size

        // In Hoard's trash: the lookup misses the item (and a PUT would be 404); nothing is lost or re-sent.
        server.edits.trashed += itemId
        repeat(2) { pass() }
        assertThat(server.edits.puts).hasSize(puts)
        assertThat(server.edits.of(itemId)).hasSize(1)

        // Restored: the server's edit is still the current one, so nothing goes up again.
        server.edits.trashed -= itemId
        assertThat(pass().uploaded).isEqualTo(0)
        assertThat(server.edits.puts).hasSize(puts)
    }
}
