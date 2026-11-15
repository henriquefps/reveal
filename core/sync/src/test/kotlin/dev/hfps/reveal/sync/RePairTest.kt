package dev.hfps.reveal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.MemorySecretStore
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.VersionData
import dev.hfps.reveal.sync.files.ServerFiles
import java.io.File
import java.net.URLEncoder
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Pairing again with the same server (ADR 0018 "W5 (f)"; the owner moved the server from a tunnel to Tailscale and
 * scanned a new pairing QR on both devices): the device, its physical device and the library's sync state survive.
 */
@RunWith(AndroidJUnit4::class)
class RePairTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()
    private val pixel = ClientDevice("reveal", "Google", "Pixel 8")

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
    }

    /** The same server at another address (the loopback by name or by number). */
    private val otherUrl: String
        get() = if ("localhost" in url) {
            url.replace("localhost", "127.0.0.1")
        } else {
            url.replace("127.0.0.1", "localhost")
        }

    /** A pairing code as hoard-server prints it (with `server=`); [serverId] null = an older code without it. */
    private fun link(at: String = url, token: String = server.pairingToken(), serverId: String? = server.serverId) =
        PairingLink.parse(
            "revealsync://pair?v=1&url=${URLEncoder.encode(at, "UTF-8")}&token=$token" +
                (serverId?.let { "&server=$it" } ?: ""),
        )!!

    /** An installation: its library ([TestDevice]) and the app's sync repository over the same database. */
    private class Install(val device: TestDevice, val repository: SyncRepository)

    private fun install(): Install {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val device = TestDevice("Phone", url, "unused", server.serverId, device = pixel).also { devices += it }
        val repository = SyncRepository(
            db = device.db,
            accounts = SyncAccountStore(context, MemorySecretStore()),
            store = device.store,
            engine = device.engine,
            uploader = BlobUploader(
                device.db,
                device.store,
                DocumentTreeFactory { error("none") },
                SmartPreviewMaker(File("/tmp")),
            ),
            files = ServerFiles(device.db, { null }, context.cacheDir),
            clientInfo = SyncRepository.ClientInfo("Pixel 8", "android", "0.9.0", pixel),
        )
        return Install(device, repository)
    }

    private fun hoardOnPixel(): String = SyncApi(url, null).pair(
        PairRequest(server.pairingToken(), "Pixel 8", "android", "2.2.0", "hoard", "Google", "Pixel 8"),
    ).deviceId

    private fun records(device: TestDevice): List<Triple<String, String, Long>> =
        device.db.openHelper.readableDatabase.query("SELECT kind, record_id, rev FROM sync_records ORDER BY 1, 2")
            .use { c -> buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1), c.getLong(2))) } }

    /** Paired, on Hoard's phone, one edited photo synced. */
    private fun pairedWithAnEdit(app: Install, hoard: String): Long {
        runBlocking { app.repository.pair(link()) }
        runBlocking { app.repository.claim(hoard) }
        val photo = app.device.addPhoto("DSC1", Random(1).nextBytes(4096))
        app.device.edit(app.device.version(photo)) { it.copy(light = it.light.copy(exposureEv = 0.5f)) }
        runBlocking { app.repository.syncMetadata() }
        assertThat(app.device.pending()).isEqualTo(0)
        return photo
    }

    @Test
    fun `a new address with a key that still works keeps the device, its phone and the sync state`() {
        val hoard = hoardOnPixel()
        val app = install()
        pairedWithAnEdit(app, hoard)
        val before = app.repository.account.value!!
        val rows = records(app.device)
        val known = server.devices.size
        val token = link(otherUrl)

        val after = runBlocking { app.repository.pair(token) }

        assertThat(after.deviceId).isEqualTo(before.deviceId)
        assertThat(after.url).isEqualTo(otherUrl)
        assertThat(server.devices.size).isEqualTo(known)
        assertThat(server.tokens).contains(token.token)
        assertThat(server.culling.physical[after.deviceId]).isEqualTo(hoard)
        assertThat(records(app.device)).isEqualTo(rows)
        runBlocking { app.repository.syncMetadata() }
        assertThat(app.device.pending()).isEqualTo(0)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `when the old key is revoked the new device keeps the sync state and rejoins the phone`() {
        val hoard = hoardOnPixel()
        val app = install()
        val photo = pairedWithAnEdit(app, hoard)
        val old = app.repository.account.value!!.deviceId
        val rows = records(app.device)
        val versionUuid = app.device.versions(photo).single().uuid
        val rev = server.record("version", versionUuid)!!.rev
        server.revoke(old)

        val after = runBlocking { app.repository.pair(link(otherUrl)) }

        assertThat(after.deviceId).isNotEqualTo(old)
        // The installation's phone (with Hoard) is offered again once the old Reveal is gone from it: joined at once.
        assertThat(server.culling.physical[after.deviceId]).isEqualTo(hoard)
        assertThat(app.repository.pairCandidates.value).isEmpty()
        // Records, uuids and revisions stay: nothing is pushed again, no conflict copy.
        assertThat(records(app.device)).isEqualTo(rows)
        runBlocking { app.repository.syncMetadata() }
        assertThat(server.record("version", versionUuid)!!.rev).isEqualTo(rev)
        assertThat(server.copies()).isEmpty()
        // The new device declares itself (culling) on its first sync.
        assertThat(server.culling.reports.map { it.first }).contains(after.deviceId)

        // The W5 repair still runs for it after an update: this device's edit goes up again, naming its photo.
        runBlocking { app.device.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        runBlocking { app.repository.syncMetadata() }
        val record = server.record("version", versionUuid)!!
        assertThat(record.rev).isGreaterThan(rev)
        assertThat(record.data!!.jsonObject["photoId"]!!.jsonPrimitive.content)
            .isEqualTo(app.device.photo(photo).uuid)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `when the old device is still live in the phone the new one cannot join and the question is not forced`() {
        val hoard = hoardOnPixel()
        val app = install()
        pairedWithAnEdit(app, hoard)
        val old = app.repository.account.value!!.deviceId
        // The key no longer works (e.g. wiped from the Keystore) but the server still lists the old device.
        server.devices.entries.removeIf { it.value == old }

        val after = runBlocking { app.repository.pair(link(otherUrl)) }

        assertThat(after.deviceId).isNotEqualTo(old)
        // hoard-server offers no phone that already has a live Reveal (same_app): the new device stays on its own.
        assertThat(server.culling.physical[after.deviceId]).isEqualTo(after.deviceId)
        assertThat(server.culling.physical[old]).isEqualTo(hoard)
        runBlocking { app.repository.syncMetadata() }
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `unpairing then pairing again with the same server makes no conflict copy of an unchanged edit`() {
        // What every re-pair did before W5 (f): forget, then a first sync that marks everything at rev 0. The server
        // used to keep the losing side of each edited version as a conflict copy.
        val app = install()
        val hoard = hoardOnPixel()
        pairedWithAnEdit(app, hoard)
        runBlocking { app.repository.unpair() }
        runBlocking { app.repository.pair(link(otherUrl)) }
        runBlocking { app.repository.syncMetadata() }
        assertThat(server.copies()).isEmpty()
        assertThat(app.device.pending()).isEqualTo(0)
    }

    @Test
    fun `the key never goes to an address the link does not prove to be the paired server`() {
        val app = install()
        pairedWithAnEdit(app, hoardOnPixel())
        val other = FakeSyncServer("858e1b9d-0000-4000-8000-0000000000ff")
        try {
            val otherUrl = other.start()
            // An older code without `server=`, then one naming another server: both pair normally with that server.
            runBlocking { app.repository.pair(link(otherUrl, other.pairingToken(), serverId = null)) }
            assertThat(other.authorizations).isEmpty()
            runBlocking { app.repository.unpair() }
            runBlocking { app.repository.pair(link(url)) }
            runBlocking { app.repository.pair(link(otherUrl, other.pairingToken(), serverId = other.serverId)) }
            assertThat(other.authorizations).isEmpty()
            assertThat(app.repository.account.value!!.serverId).isEqualTo(other.serverId)
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `unpairing then pairing again after the photo adopted another device's id pushes the version naming it`() {
        val app = install()
        runBlocking { app.repository.pair(link()) }
        runBlocking { app.repository.syncMetadata() }
        val raw = Random(9).nextBytes(4096)
        val fingerprint = FileFingerprint.of(raw)
        // A folder photo whose record never went up (as a quietly filed hand-off leaves it).
        val photo = app.device.addPhoto("DSC9", raw)
        val oldUuid = app.device.photo(photo).uuid
        runBlocking { app.device.db.syncDao().deleteRecord("photo", oldUuid) }
        app.device.edit(app.device.version(photo)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        // What 0.8 pushed: the version alone, naming this device's id for the photo, without its fingerprint.
        val versionUuid = legacyPush(app, photo)
        // Another device's record of the same photo goes up under its own id; this one adopts it on its next pull.
        val tablet = TestDevice("Tablet", url, server.addDevice("Tablet"), server.serverId).also { devices += it }
        val onTablet = tablet.photo(tablet.addPhoto("DSC9", raw))
        tablet.sync()
        // The repair ran before the adoption (as on a device updated earlier).
        runBlocking {
            app.device.db.metaDao().put(MetaEntity(SyncEngine.KEY_VERSION_PHOTOS, SyncEngine.VERSION_PHOTOS_VERSION))
        }
        runBlocking { app.repository.syncMetadata() }
        assertThat(app.device.photo(photo).uuid).isEqualTo(onTablet.uuid)
        assertThat(photoIdOf(versionUuid)).isEqualTo(oldUuid)

        runBlocking { app.repository.unpair() }
        runBlocking { app.repository.pair(link(otherUrl)) }
        runBlocking { app.repository.syncMetadata() }

        val record = server.record("version", versionUuid)!!.data!!.jsonObject
        assertThat(record["photoId"]!!.jsonPrimitive.content).isEqualTo(onTablet.uuid)
        assertThat(record["photoFingerprint"]!!.jsonPrimitive.content).isEqualTo(fingerprint)
        assertThat(server.copies()).isEmpty()
        assertThat(app.device.pending()).isEqualTo(0)
        // This device's history and authorship stay its own.
        val version = app.device.versions(photo).single()
        assertThat(version.editDevice).isEqualTo(EditRepository.THIS_DEVICE)
        assertThat(app.device.recipe(version.id).light.exposureEv).isEqualTo(0.7f)
    }

    private fun photoIdOf(versionUuid: String) =
        server.record("version", versionUuid)!!.data!!.jsonObject["photoId"]!!.jsonPrimitive.content

    private fun legacyPush(app: Install, photo: Long): String = runBlocking {
        val version = app.device.versions(photo).first()
        val data = VersionData(
            photoId = app.device.photo(photo).uuid,
            recipe = SyncApi.json.parseToJsonElement(RecipeCodec.Default.encode(app.device.recipe(version.id))),
        )
        val local = checkNotNull(app.device.db.syncDao().record("version", version.uuid))
        val result = checkNotNull(app.repository.api()).push(
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
        app.device.db.syncDao().putRecord(local.copy(rev = result.rev, dirty = false))
        version.uuid
    }
}
