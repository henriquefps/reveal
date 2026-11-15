package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0018 "W5": edited Server photos an unpairing keeps belong to the server they came from. Paired with another
 * server, nothing about them goes up (no photo record with the old NAS's file names, no version, no culling); paired
 * again with their own server, the edits it did not hold go up as before. A version edited here on top of the server's
 * revision of this installation's own record is rebased, not turned into a conflict copy.
 */
@RunWith(AndroidJUnit4::class)
class KeptServerPhotosTest {
    private val serverA = FakeSyncServer()
    private val serverB = FakeSyncServer("858e1b9d-0000-4000-8000-000000000002")
    private lateinit var urlA: String
    private lateinit var urlB: String
    private val devices = ArrayList<TestDevice>()

    @Before
    fun setUp() {
        urlA = serverA.start()
        urlB = serverB.start()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        serverA.shutdown()
        serverB.shutdown()
    }

    private fun bytes(seed: Int, size: Int = 4096) = Random(seed).nextBytes(size)

    private fun nas(server: FakeSyncServer, name: String, seed: Int): String {
        val raw = bytes(seed)
        val fingerprint = FileFingerprint.of(raw)
        server.addNasFile("f1", "Shoots", "2026/$name", raw, fingerprint)
        return fingerprint
    }

    private fun TestDevice.exposure(photoId: Long) = recipe(version(photoId)).light.exposureEv

    private fun TestDevice.editExposure(photoId: Long, ev: Float) =
        edit(version(photoId)) { it.copy(light = it.light.copy(exposureEv = ev)) }

    private fun TestDevice.unpair() = runBlocking {
        store.forget()
        engine.resetCursors()
    }

    /** A paired device that knows its device id, as `SyncRepository` passes it to every sync. */
    private fun TestDevice.noteOwn(server: FakeSyncServer) = runBlocking {
        store.noteOwnDevice(server.devices.getValue(key))
    }

    @Suppress("LongMethod")
    @Test
    fun `kept Server photos never go up to another server and go up again on their own`() {
        serverB.hoard()
        val phone = TestDevice("Phone", urlA, serverA.addDevice("Phone"), serverA.serverId).also { devices += it }
        phone.noteOwn(serverA)
        val synced = nas(serverA, "DSC09301.ARW", 71)
        val unsynced = nas(serverA, "DSC09302.ARW", 72)
        phone.sync()
        val syncedPhoto = phone.photoByFingerprint(synced)!!
        val unsyncedPhoto = phone.photoByFingerprint(unsynced)!!
        phone.editExposure(syncedPhoto.id, 0.5f)
        phone.sync()
        phone.now += 1_000
        phone.editExposure(unsyncedPhoto.id, -0.5f)
        val syncedUuid = phone.versions(syncedPhoto.id).single().uuid
        val unsyncedUuid = phone.versions(unsyncedPhoto.id).single().uuid
        val revA = serverA.record("version", syncedUuid)!!.rev

        // Unpaired, then paired with another server: a folder photo goes up, the kept Server photos stay here.
        phone.unpair()
        val local = phone.addPhoto("IMG_0001", bytes(73))
        phone.editExposure(local, 0.2f)
        phone.switchServer(urlB, serverB.addDevice("Phone"), serverB.serverId)
        phone.noteOwn(serverB)
        phone.now += 1_000
        phone.sync()
        // Edited, rated and given a virtual-copy-free new edit while paired with B: still nothing about them.
        phone.now += 1_000
        phone.editExposure(syncedPhoto.id, 0.7f)
        phone.rate(syncedPhoto.id, 4)
        phone.rate(unsyncedPhoto.id, 2)
        phone.sync()
        phone.sync()

        val keptPhotos = listOf(syncedPhoto.uuid, unsyncedPhoto.uuid)
        val keptVersions = listOf(syncedUuid, unsyncedUuid)
        assertThat(serverB.records.keys.map { it.second }).containsNoneIn(keptPhotos + keptVersions)
        val names = serverB.records.values.mapNotNull { it.data?.toString() }
        assertThat(names.filter { "DSC09301" in it || "DSC09302" in it }).isEmpty()
        assertThat(serverB.culling.pushes.flatten().map { it["fingerprint"]!!.jsonPrimitive.content })
            .containsNoneOf(synced, unsynced)
        assertThat(serverB.record("photo", phone.photo(local).uuid)).isNotNull()
        assertThat(phone.exposure(syncedPhoto.id)).isEqualTo(0.7f)
        assertThat(phone.exposure(unsyncedPhoto.id)).isEqualTo(-0.5f)
        assertThat(runBlocking { phone.store.keptPhotos() }).containsExactlyElementsIn(keptPhotos)
        assertThat(runBlocking { phone.store.keptPhotoCount() }).isEqualTo(2)

        // Back to the server they came from (unpairing B keeps them A's): both edits go up, as updates.
        phone.unpair()
        phone.switchServer(urlA, serverA.addDevice("Phone"), serverA.serverId)
        phone.noteOwn(serverA)
        phone.now += 1_000
        phone.sync()
        val record = serverA.record("version", syncedUuid)!!
        assertThat(record.rev).isGreaterThan(revA)
        assertThat(exposureOf(record)).isEqualTo(0.7f)
        assertThat(exposureOf(serverA.record("version", unsyncedUuid)!!)).isEqualTo(-0.5f)
        assertThat(serverA.copies()).isEmpty()
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(runBlocking { phone.store.keptPhotos() }).isEmpty()
        // B's folder photo is A's now too, and nothing of B's server photos came along.
        assertThat(serverA.record("photo", phone.photo(local).uuid)).isNotNull()
    }

    @Test
    fun `photos kept from the previous server can be removed without telling the new one`() {
        val phone = TestDevice("Phone", urlA, serverA.addDevice("Phone"), serverA.serverId).also { devices += it }
        val fingerprint = nas(serverA, "DSC09311.ARW", 81)
        phone.sync()
        val photo = phone.photoByFingerprint(fingerprint)!!
        phone.editExposure(photo.id, 0.4f)
        phone.unpair()
        phone.switchServer(urlB, serverB.addDevice("Phone"), serverB.serverId)
        phone.sync()
        assertThat(runBlocking { phone.store.keptPhotoCount() }).isEqualTo(1)

        assertThat(runBlocking { phone.store.removeKeptPhotos() }).isEqualTo(1)
        phone.sync()
        assertThat(phone.photoByFingerprint(fingerprint)).isNull()
        assertThat(runBlocking { phone.store.keptPhotos() }).isEmpty()
        assertThat(serverB.records.keys.map { it.second }).doesNotContain(photo.uuid)
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `a local edit on top of this installation's own record is rebased when paired again`() {
        val phone = TestDevice("Phone", urlA, serverA.addDevice("Phone"), serverA.serverId).also { devices += it }
        phone.noteOwn(serverA)
        val local = phone.addPhoto("IMG_0002", bytes(91))
        phone.editExposure(local, 0.3f)
        phone.sync()
        val uuid = phone.versions(local).single().uuid
        val rev = serverA.record("version", uuid)!!.rev
        // Edited again, never pushed, then paired again (a new device: the old key no longer works).
        phone.now += 1_000
        phone.editExposure(local, 0.9f)
        phone.unpair()
        phone.rekey(serverA.addDevice("Phone"))
        phone.noteOwn(serverA)
        phone.now += 1_000
        phone.sync()

        val record = serverA.record("version", uuid)!!
        assertThat(record.rev).isGreaterThan(rev)
        assertThat(exposureOf(record)).isEqualTo(0.9f)
        assertThat(record.deviceId).isEqualTo(serverA.devices.getValue(phone.key))
        assertThat(serverA.copies()).isEmpty()
        assertThat(phone.versions(local)).hasSize(1)
        assertThat(phone.exposure(local)).isEqualTo(0.9f)
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(runBlocking { phone.db.syncDao().inbox() }).isEmpty()
    }

    @Test
    fun `another device's record under an unpushed local edit is still a conflict`() {
        val phone = TestDevice("Phone", urlA, serverA.addDevice("Phone"), serverA.serverId).also { devices += it }
        phone.noteOwn(serverA)
        val fingerprint = nas(serverA, "DSC09321.ARW", 92)
        phone.sync()
        val tablet = TestDevice("Tablet", urlA, serverA.addDevice("Tablet"), serverA.serverId).also { devices += it }
        tablet.sync()
        val onTablet = tablet.photoByFingerprint(fingerprint)!!
        tablet.editExposure(onTablet.id, 0.6f)
        tablet.sync()
        phone.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        phone.now += 1_000
        phone.editExposure(onPhone.id, -0.6f)
        phone.unpair()
        phone.rekey(serverA.addDevice("Phone"))
        phone.now += 1_000
        phone.sync()
        // The tablet's record is not this installation's: the server decides, the losing edit becomes a copy.
        assertThat(serverA.copies()).hasSize(1)
    }

    private fun exposureOf(record: FakeSyncServer.Record): Float {
        val recipe = record.data!!.jsonObject["recipe"]!!
        return dev.hfps.reveal.pipeline.recipe.RecipeCodec.Default.decode(recipe.toString()).light.exposureEv
    }
}
