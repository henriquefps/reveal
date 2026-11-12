package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.handoff.HandoffMerge
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.ServerLibrary
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.files.ServerFiles
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The server's view (ADR 0018 "Server photos" and "Deleting from the server", FIX/V, 0.5.3), on two simulated devices
 * against [FakeSyncServer]: the Server scope lists only photos that open, a shot opens from whichever of its files the
 * server holds, removing a folder keeps or deletes its photos on the server, adding it again never duplicates a photo,
 * "Delete from server", and the one-off clean-up of what 0.5.2 left behind.
 */
@RunWith(AndroidJUnit4::class)
class ServerViewTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        url = server.start()
        dir = Files.createTempDirectory("server-view").toFile()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
        dir.deleteRecursively()
    }

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId).also { devices += it }

    private fun bytes(seed: Int, size: Int = 2048) = Random(seed).nextBytes(size)

    private fun fp(bytes: ByteArray) = FileFingerprint.of(bytes)

    /** The Server scope as the library lists it. */
    private fun serverScope(device: TestDevice) = runBlocking {
        device.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.SERVER_ONLY))
    }

    private fun serverCount(device: TestDevice) = runBlocking { device.db.syncDao().observeServerPhotoCount().first() }

    private fun library(device: TestDevice) = ServerLibrary(device.db, device.store) { device.now }

    private fun uploader(device: TestDevice) = BlobUploader(
        device.db,
        device.store,
        DocumentTreeFactory { error("no reads") },
        SmartPreviewMaker(File(dir, "made-${device.name}")),
    ) { device.now }

    private fun deleteOriginals(device: TestDevice) = runBlocking { uploader(device).deleteOriginals(device.api) }

    private fun photo(device: TestDevice, id: Long): PhotoEntity? = runBlocking { device.db.photoDao().get(id) }

    private fun files(device: TestDevice, photoId: Long) = runBlocking { device.db.photoDao().files(photoId) }

    /** Live photo records on the server that name [fingerprint] as their main file. */
    private fun liveRecords(fingerprint: String) = server.records.values.filter { r ->
        r.kind == "photo" && !r.deleted &&
            (r.data as? JsonObject)?.get("fingerprint")?.jsonPrimitive?.content == fingerprint
    }

    /** Forgets folder [source] as the library does after [ServerLibrary.removeFolder]. */
    private fun forgetFolder(device: TestDevice, source: Long) = runBlocking {
        device.db.sourceDao().delete(source)
    }

    // ------------------------------------------------------------------ the Server scope lists what opens

    @Test
    fun `metadata-only records stay in the database but the Server scope lists only photos that open`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val described = bytes(1)
        val backedUp = bytes(2)
        val previewOnly = bytes(3)
        val a = phone.addPhoto("DSC1", described)
        phone.addPhoto("DSC2", backedUp)
        val c = phone.addPhoto("DSC3", previewOnly)
        runBlocking { phone.db.photoDao().setRating(listOf(a, c), 3) }
        server.putOriginal(backedUp)
        // A preview another device made (e.g. of an unreadable hand-off): the record names it.
        phone.sync()
        val record = server.records.values.first { r ->
            (r.data as? JsonObject)?.get("fingerprint")?.jsonPrimitive?.content == fp(previewOnly)
        }
        val previewSha = "ab".repeat(32)
        record.data = JsonObject(
            (record.data as JsonObject) + (
                "preview" to kotlinx.serialization.json.buildJsonObject {
                    put("sha256", kotlinx.serialization.json.JsonPrimitive(previewSha))
                    put("width", kotlinx.serialization.json.JsonPrimitive(2560))
                    put("height", kotlinx.serialization.json.JsonPrimitive(1707))
                }
                ),
        )
        server.bump("photo", record.id)
        tablet.sync()

        // All three are known (their ratings and edits merge when the photo shows up) ...
        assertThat(tablet.photoByFingerprint(fp(described))).isNotNull()
        assertThat(tablet.photoByFingerprint(fp(described))!!.rating).isEqualTo(3)
        // ... but only the backed-up one and the preview-only one are listed and counted.
        assertThat(serverScope(tablet).map { it.name }).containsExactly("DSC2.ARW", "DSC3.ARW")
        assertThat(serverCount(tablet)).isEqualTo(2)
        // Nothing in the Server scope is "unavailable" (that placeholder is for this device's own unreadable files).
        assertThat(serverScope(tablet).none { it.unavailable }).isTrue()
    }

    @Test
    fun `a RAW and JPEG shot with only its RAW backed up opens from the RAW and never asks for the JPEG`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(10, 4096)
        val jpeg = bytes(11, 1024)
        val p = phone.addPhoto("DSC10", raw, jpeg)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.3f)) }
        server.putOriginal(raw) // the backup sends the main file only
        phone.sync()
        tablet.sync()

        val remote = tablet.photoByFingerprint(fp(raw))!!
        assertThat(serverScope(tablet).map { it.id }).containsExactly(remote.id)
        val byRole = files(tablet, remote.id).associateBy { it.role }
        assertThat(byRole.getValue(FileRole.RAW).onServer).isTrue()
        assertThat(byRole.getValue(FileRole.RENDERED).onServer).isFalse()
        // The open sites take the files the server holds: the RAW.
        val reachable = ServerTree.reachable(files(tablet, remote.id)) { it.onServer }
        assertThat(reachable.map { it.role }).containsExactly(FileRole.RAW)

        // The provider's view: the RAW is served, the JPEG is not (0.5.2 trusted the photo's flag and asked: 404).
        val files = ServerFiles(tablet.db, { tablet.api }, File(dir, "tablet-cache"))
        runBlocking {
            val served = files.original(fp(raw))!!
            val head = ByteArray(16)
            assertThat(served.read(0, 16, head)).isEqualTo(16)
            assertThat(head).isEqualTo(raw.copyOf(16))
            assertThat(files.original(fp(jpeg))).isNull()
        }
    }

    @Test
    fun `a shot whose JPEG alone is on the server opens from the JPEG`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(20, 4096)
        val jpeg = bytes(21, 1024)
        phone.addPhoto("DSC20", raw, jpeg)
        server.putOriginal(jpeg) // e.g. the camera JPEGs are on a NAS folder or were backed up by another app
        phone.sync()
        tablet.sync()

        val remote = tablet.photoByFingerprint(fp(raw))!!
        assertThat(serverScope(tablet).map { it.id }).containsExactly(remote.id)
        val reachable = ServerTree.reachable(files(tablet, remote.id)) { it.onServer }
        assertThat(reachable.map { it.role }).containsExactly(FileRole.RENDERED)
    }

    @Test
    fun `RAW-only and JPEG-only photos and a hand-off are listed once their original is on the server`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val rawOnly = bytes(30)
        val jpegOnly = bytes(31)
        val shared = bytes(32)
        phone.addPhoto("DSC30", rawOnly)
        phone.addFolderJpeg("IMG31", jpegOnly)
        phone.addHandoffPhoto("IMG32", shared)
        phone.sync()
        tablet.sync()
        assertThat(serverScope(tablet)).isEmpty()

        for (b in listOf(rawOnly, jpegOnly, shared)) server.putOriginal(b)
        // Their backups land: each record is pushed again, so the tablet asks at once.
        runBlocking {
            for (b in listOf(rawOnly, jpegOnly, shared)) {
                val id = phone.photoByFingerprint(fp(b))!!.id
                phone.store.originalLanded(id, fp(b))
            }
        }
        phone.sync()
        tablet.sync()
        assertThat(serverScope(tablet).map { it.name }).containsExactly("DSC30.ARW", "IMG31.JPG", "IMG32.JPG")
    }

    // ------------------------------------------------------------------ removing a folder

    @Test
    fun `removing a folder and keeping the server copies moves them to this device's Server scope`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val editedUp = bytes(40)
        val plain = bytes(41)
        val editedLocal = bytes(42)
        val ratedUp = bytes(43)
        val a = phone.addPhoto("DSC40", editedUp)
        val b = phone.addPhoto("DSC41", plain)
        val c = phone.addPhoto("DSC42", editedLocal)
        val d = phone.addPhoto("DSC43", ratedUp)
        phone.edit(phone.version(a)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        phone.edit(phone.version(c)) { it.copy(light = it.light.copy(exposureEv = -0.2f)) }
        runBlocking {
            phone.db.photoDao().setRating(listOf(d), 5)
            phone.db.photoDao().setFlag(listOf(d), PickFlag.PICK)
        }
        server.putOriginal(editedUp)
        server.putOriginal(ratedUp)
        phone.sync()
        tablet.sync()
        val uuidA = photo(phone, a)!!.uuid
        val uuidB = photo(phone, b)!!.uuid
        val uuidC = photo(phone, c)!!.uuid

        phone.now += 1_000
        val removal = runBlocking { library(phone).removeFolder(phone.folder, deleteFromServer = false) }
        forgetFolder(phone, phone.folder)
        assertThat(removal.keptOnServer).isEqualTo(2)
        assertThat(removal.tombstoned).isEqualTo(1)
        assertThat(removal.originalsQueued).isEqualTo(0)

        // On the phone itself (no reliance on the pull cursor): the backed-up photos are Server photos, same ids,
        // same edit and culling, listed; the unedited one with nothing on the server is gone everywhere.
        val listed = serverScope(phone)
        assertThat(listed.map { it.name }).containsExactly("DSC40.ARW", "DSC43.ARW")
        val movedA = phone.db.let { runBlocking { it.syncDao().photoByUuid(uuidA) } }!!
        assertThat(runBlocking { phone.db.sourceDao().get(movedA.sourceId) }!!.kind).isEqualTo(SourceKind.SERVER)
        assertThat(phone.recipe(phone.version(movedA.id)).light.exposureEv).isEqualTo(0.7f)
        assertThat(listed.single { it.name == "DSC43.ARW" }.rating).isEqualTo(5)
        assertThat(files(phone, movedA.id).single().documentId)
            .isEqualTo(ServerTree.documentId(fp(editedUp), "DSC40.ARW"))
        // The edited photo with nothing on the server keeps its record here, unlisted.
        assertThat(runBlocking { phone.db.syncDao().photoByUuid(uuidC) }).isNotNull()
        assertThat(listed.map { it.name }).doesNotContain("DSC42.ARW")

        phone.sync()
        tablet.sync()
        assertThat(server.record("photo", uuidB)!!.deleted).isTrue()
        assertThat(server.record("photo", uuidA)!!.deleted).isFalse()
        assertThat(server.record("photo", uuidC)!!.deleted).isFalse()
        assertThat(tablet.photoByFingerprint(fp(plain))).isNull()
        assertThat(serverScope(tablet).map { it.name }).containsExactly("DSC40.ARW", "DSC43.ARW")
        assertThat(phone.pending()).isEqualTo(0)

        // Editable from the server copy: an edit on the phone reaches the tablet.
        phone.now += 1_000
        phone.edit(phone.version(movedA.id)) { it.copy(light = it.light.copy(exposureEv = 1.1f)) }
        phone.sync()
        tablet.sync()
        val onTablet = tablet.photoByFingerprint(fp(editedUp))!!
        assertThat(tablet.recipe(tablet.version(onTablet.id)).light.exposureEv).isEqualTo(1.1f)
    }

    @Test
    fun `removing a folder and deleting from the server drops its records, originals and previews`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(50, 4096)
        val jpeg = bytes(51, 1024)
        val other = bytes(52)
        val a = phone.addPhoto("DSC50", raw, jpeg)
        phone.addPhoto("DSC52", other)
        phone.edit(phone.version(a)) { it.copy(light = it.light.copy(exposureEv = 0.5f)) }
        server.putOriginal(raw)
        server.putOriginal(jpeg)
        phone.sync()
        tablet.sync()
        assertThat(serverScope(tablet)).hasSize(1)
        assertThat(runBlocking { library(phone).editedCount(phone.folder) }).isEqualTo(1)

        phone.now += 1_000
        val removal = runBlocking { library(phone).removeFolder(phone.folder, deleteFromServer = true) }
        forgetFolder(phone, phone.folder)
        assertThat(removal.tombstoned).isEqualTo(2)
        assertThat(removal.originalsQueued).isEqualTo(3) // every file of every photo: the server decides

        // Before the tombstones are pushed the server refuses (a live record names them): nothing is lost.
        deleteOriginals(phone)
        assertThat(server.hasOriginal(fp(raw))).isTrue()
        phone.sync()
        deleteOriginals(phone)
        assertThat(server.hasOriginal(fp(raw))).isFalse()
        assertThat(server.hasOriginal(fp(jpeg))).isFalse()
        assertThat(runBlocking { phone.db.syncDao().pendingDeletes(10) }).isEmpty()
        assertThat(server.records.values.filter { it.kind == "photo" }.all { it.deleted }).isTrue()
        assertThat(server.records.values.filter { it.kind == "version" && it.rev > 0 }.all { it.deleted }).isTrue()

        tablet.sync()
        assertThat(serverScope(tablet)).isEmpty()
        assertThat(tablet.photoByFingerprint(fp(raw))).isNull()
        assertThat(tablet.photoByFingerprint(fp(other))).isNull()
    }

    @Test
    fun `a photo tombstone releases the smart preview its record named`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(55)
        val p = phone.addPhoto("DSC55", raw)
        phone.sync()
        // A preview this device uploaded and named (e.g. an edited photo with the originals backup off).
        val previewBytes = bytes(56, 512)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(previewBytes)
            .joinToString("") { "%02x".format(it) }
        server.blobs[sha] = previewBytes
        server.blobKinds[sha] = "preview"
        runBlocking {
            phone.db.syncDao().putCloud(
                checkNotNull(phone.db.syncDao().cloud(p)).copy(previewSha = sha, previewWidth = 1, previewHeight = 1),
            )
            phone.store.originalLanded(p, "v1:" + "0".repeat(64)) // marks the record dirty: pushed with the preview
        }
        phone.now += 1_000
        phone.sync()
        assertThat(server.previewNamed(sha)).isTrue()
        tablet.sync()
        val remote = tablet.photoByFingerprint(fp(raw))!!

        tablet.now += 5_000
        assertThat(runBlocking { library(tablet).deleteFromServer(listOf(remote.id)) }).isEqualTo(1)
        tablet.sync()
        assertThat(server.blobs).doesNotContainKey(sha)
        // The phone keeps its file, forgets the preview it named (never names it again) and its server state.
        phone.sync()
        val cloud = runBlocking { phone.db.syncDao().cloud(p) }!!
        assertThat(cloud.previewSha).isNull()
        assertThat(cloud.originalOnServer).isFalse()
        assertThat(photo(phone, p)).isNotNull()
    }

    // ------------------------------------------------------------------ adding a folder again

    @Test
    fun `adding a removed folder again adopts the server photos' ids, edits and ratings instead of duplicating`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val upEdited = bytes(60)
        val localEdited = bytes(61)
        val a = phone.addPhoto("DSC60", upEdited)
        val b = phone.addPhoto("DSC61", localEdited)
        phone.edit(phone.version(a)) { it.copy(light = it.light.copy(exposureEv = 0.9f)) }
        phone.edit(phone.version(b)) { it.copy(light = it.light.copy(exposureEv = -0.6f)) }
        runBlocking { phone.db.photoDao().setRating(listOf(a), 4) }
        server.putOriginal(upEdited)
        phone.sync()
        tablet.sync()
        val uuidA = photo(phone, a)!!.uuid
        val uuidB = photo(phone, b)!!.uuid

        phone.now += 1_000
        runBlocking { library(phone).removeFolder(phone.folder, deleteFromServer = false) }
        forgetFolder(phone, phone.folder)
        phone.sync()

        // The folder comes back (new photo rows, as the indexer makes them), then its fingerprint pass merges.
        phone.now += 1_000
        val again = phone.addFolder("DCIM")
        val a2 = phone.addPhoto("DSC60", upEdited, source = again)
        val b2 = phone.addPhoto("DSC61", localEdited, source = again)
        val report = runBlocking { HandoffMerge(phone.db) { phone.now }.mergeServerPhotosInto(again) }
        assertThat(report.merged).isEqualTo(2)
        assertThat(photo(phone, a2)!!.uuid).isEqualTo(uuidA)
        assertThat(photo(phone, b2)!!.uuid).isEqualTo(uuidB)
        assertThat(photo(phone, a2)!!.rating).isEqualTo(4)
        assertThat(phone.versions(a2).map { phone.recipe(it.id).light.exposureEv }).containsExactly(0.9f)
        assertThat(phone.versions(b2).map { phone.recipe(it.id).light.exposureEv }).containsExactly(-0.6f)
        assertThat(serverScope(phone)).isEmpty() // they are folder photos again

        phone.sync()
        tablet.sync()
        assertThat(liveRecords(fp(upEdited))).hasSize(1)
        assertThat(liveRecords(fp(localEdited))).hasSize(1)
        val onTablet = runBlocking { tablet.db.photoDao().filesByFingerprint(listOf(fp(upEdited))) }
        assertThat(onTablet.map { it.file.photoId }.distinct()).hasSize(1)
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `a folder added on another device holding a backed-up photo becomes that photo, not a second one`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(65)
        val p = phone.addPhoto("DSC65", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.2f)) }
        server.putOriginal(raw)
        phone.sync()
        tablet.sync()
        assertThat(serverScope(tablet)).hasSize(1)

        // The tablet adds a folder with the same file (a card read on both devices).
        tablet.now += 1_000
        val folder = tablet.addFolder("Card")
        val local = tablet.addPhoto("DSC65", raw, source = folder)
        runBlocking { HandoffMerge(tablet.db) { tablet.now }.mergeServerPhotosInto(folder) }
        tablet.sync()
        phone.sync()
        assertThat(photo(tablet, local)!!.uuid).isEqualTo(photo(phone, p)!!.uuid)
        assertThat(tablet.recipe(tablet.version(local)).light.exposureEv).isEqualTo(0.2f)
        assertThat(serverScope(tablet)).isEmpty()
        assertThat(liveRecords(fp(raw))).hasSize(1)
    }

    // ------------------------------------------------------------------ delete from server by hand

    @Test
    fun `delete from server in the Server scope removes the photo, its edit and its original on every device`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(70)
        val p = phone.addPhoto("DSC70", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.4f)) }
        server.putOriginal(raw)
        phone.sync()
        tablet.sync()
        val remote = serverScope(tablet).single()

        tablet.now += 1_000
        assertThat(runBlocking { library(tablet).deleteFromServer(listOf(remote.id)) }).isEqualTo(1)
        assertThat(serverScope(tablet)).isEmpty()
        tablet.sync()
        deleteOriginals(tablet)
        assertThat(server.hasOriginal(fp(raw))).isFalse()

        // The phone keeps its file (its folder is where it lives) but the edit went with the server's copy.
        phone.sync()
        assertThat(photo(phone, p)).isNotNull()
        assertThat(phone.recipe(phone.version(p)).light.exposureEv).isEqualTo(0f)
        assertThat(runBlocking { phone.db.syncDao().cloud(p) }!!.originalOnServer).isFalse()
        // Not a Server photo: nothing to delete there.
        assertThat(runBlocking { library(phone).deleteFromServer(listOf(p)) }).isEqualTo(0)
    }

    @Test
    fun `a server before 0_5_3 keeps the deletes queued`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(75)
        phone.addPhoto("DSC75", raw)
        server.putOriginal(raw)
        phone.sync()
        tablet.sync()
        server.deleteOriginalUnsupported = true
        runBlocking { library(tablet).deleteFromServer(listOf(serverScope(tablet).single().id)) }
        tablet.sync()
        deleteOriginals(tablet)
        assertThat(runBlocking { tablet.db.syncDao().pendingDeletes(10) }).hasSize(1)
        server.deleteOriginalUnsupported = false
        deleteOriginals(tablet)
        assertThat(runBlocking { tablet.db.syncDao().pendingDeletes(10) }).isEmpty()
        assertThat(server.hasOriginal(fp(raw))).isFalse()
    }

    // ------------------------------------------------------------------ the one-off clean-up

    @Test
    fun `the clean-up brings back lost own records, drops duplicates and metadata-only ones, once`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val backedUp = bytes(80)
        val again = bytes(81)
        val gone = bytes(82)
        val editedGone = bytes(83)
        val e = phone.addPhoto("DSC83", editedGone)
        phone.addPhoto("DSC80", backedUp)
        phone.addPhoto("DSC81", again)
        phone.addPhoto("DSC82", gone)
        phone.edit(phone.version(e)) { it.copy(light = it.light.copy(exposureEv = 0.3f)) }
        server.putOriginal(backedUp)
        phone.sync() // the first sync of a 0.5.3 device does its (empty) clean-up

        // What 0.5.2 did: removing the folder forgot its photos here (records stay on the server, past the cursor),
        // then adding it again made new ids for the same files.
        runBlocking {
            phone.db.metaDao().put(
                dev.hfps.reveal.data.db.MetaEntity(SyncEngine.KEY_CLEANUP, SyncEngine.CLEANUP_VERSION),
            )
        }
        forgetFolder(phone, phone.folder)
        phone.now += 1_000
        val folder = phone.addFolder("DCIM")
        phone.addPhoto("DSC81", again, source = folder)
        // 0.5.2 had no server merge: the new id is pushed as a second record.
        phone.sync()
        assertThat(liveRecords(fp(again))).hasSize(2)
        tablet.sync()

        // 0.5.3 arrives: the next sync pulls from the start, then cleans up.
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_CLEANUP) }
        phone.now += 1_000
        val report = phone.sync()
        assertThat(report.cleanup).isNotNull()

        // The backed-up photo is back on the phone, in its Server scope.
        assertThat(serverScope(phone).map { it.name }).containsExactly("DSC80.ARW")
        // One live record per file; the metadata-only unedited record is gone; the edited one is kept (unlisted).
        assertThat(liveRecords(fp(again))).hasSize(1)
        assertThat(liveRecords(fp(gone))).isEmpty()
        assertThat(liveRecords(fp(editedGone))).hasSize(1)
        assertThat(liveRecords(fp(backedUp))).hasSize(1)

        // Idempotent: the next run does nothing more.
        phone.now += 1_000
        assertThat(phone.sync().cleanup).isNull()
        tablet.sync()
        assertThat(tablet.photoByFingerprint(fp(gone))).isNull()
        val againOnTablet = runBlocking { tablet.db.photoDao().filesByFingerprint(listOf(fp(again))) }
        assertThat(againOnTablet.map { it.file.photoId }.distinct()).hasSize(1)
        assertThat(serverScope(tablet).map { it.name }).containsExactly("DSC80.ARW")
    }
}
