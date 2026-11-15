package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.pipeline.preview.SmartPreviewMaker
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.MemorySecretStore
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.files.ServerFiles
import java.io.File
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** One device identity for Hoard and Reveal (Hoard ADR 0017): pairing, the one question, This device, claims. */
@RunWith(AndroidJUnit4::class)
class DeviceIdentityTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val databases = ArrayList<RevealDatabase>()
    private val pixel = ClientDevice("reveal", "Google", "Pixel 8")

    @Before
    fun setUp() {
        url = server.start()
        server.hoard()
    }

    @After
    fun tearDown() {
        databases.forEach(RevealDatabase::close)
        server.shutdown()
    }

    private fun link() = "revealsync://pair?v=1&url=${URLEncoder.encode(url, "UTF-8")}&token=${server.pairingToken()}"

    private fun repository(device: ClientDevice = pixel): SyncRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build().also { databases += it }
        val store = SyncStore(db)
        return SyncRepository(
            db = db,
            accounts = SyncAccountStore(context, MemorySecretStore()),
            store = store,
            engine = SyncEngine(db, store, device = device),
            uploader = BlobUploader(db, store, DocumentTreeFactory { error("none") }, SmartPreviewMaker(File("/tmp"))),
            files = ServerFiles(db, { null }, context.cacheDir),
            clientInfo = SyncRepository.ClientInfo("Pixel 8", "android", "0.8.0", device),
        )
    }

    /** Hoard pairing on [model] (as Hoard does it: its own key, `app: hoard`). */
    private fun hoardOn(model: String, manufacturer: String = "Google"): String = SyncApi(url, null).pair(
        PairRequest(server.pairingToken(), model, "android", "1.11.0", "hoard", manufacturer, model),
    ).deviceId

    @Test
    fun `the app id carries the build variant`() {
        assertThat(ClientDevice.appId("dev.hfps.reveal")).isEqualTo("reveal")
        assertThat(ClientDevice.appId("dev.hfps.reveal.debug")).isEqualTo("reveal-debug")
        assertThat(ClientDevice.appId("dev.hfps.reveal.bench")).isEqualTo("reveal-bench")
        assertThat(ClientDevice.of("dev.hfps.reveal", " samsung ", "x".repeat(80)).model).hasLength(64)
        assertThat(ClientDevice.of("dev.hfps.reveal", "", null).manufacturer).isNull()
    }

    @Test
    fun `pairing reports the app and model, and asks once when Hoard is on a phone of the same model`() {
        val hoard = hoardOn("Pixel 8")
        hoardOn("Galaxy S24", manufacturer = "samsung")
        val reveal = repository()

        val account = runBlocking { reveal.pair(PairingLink.parse(link())!!) }

        assertThat(server.culling.app[account.deviceId]).isEqualTo("reveal")
        assertThat(server.culling.model[account.deviceId]).isEqualTo("Pixel 8")
        // Only the model match is asked about: "Is this the same phone as Hoard on Pixel 8?"
        val question = reveal.pairCandidates.value.single()
        assertThat(question.physicalDeviceId).isEqualTo(hoard)
        assertThat(question.devices.single().app).isEqualTo("hoard")

        // "Same phone": one tap, both apps in one physical device.
        val device = runBlocking { reveal.claim(hoard) }
        assertThat(device.physicalDevice!!.id).isEqualTo(hoard)
        assertThat(device.others.single().app).isEqualTo("hoard")
        assertThat(reveal.pairCandidates.value).isEmpty()
        assertThat(server.culling.physical[account.deviceId]).isEqualTo(hoard)
    }

    @Test
    fun `no question without a phone of the same model`() {
        hoardOn("Galaxy S24", manufacturer = "samsung")
        val reveal = repository()
        runBlocking { reveal.pair(PairingLink.parse(link())!!) }
        assertThat(reveal.pairCandidates.value).isEmpty()
    }

    @Test
    fun `This device shows the group and the candidates, leaving and a second Reveal are handled`() {
        val hoard = hoardOn("Pixel 8")
        val reveal = repository()
        runBlocking { reveal.pair(PairingLink.parse(link())!!) }
        val before = runBlocking { reveal.thisDevice() }!!
        assertThat(before.supported).isTrue()
        assertThat(before.app).isEqualTo("reveal")
        assertThat(before.model).isEqualTo("Google Pixel 8")
        assertThat(before.others).isEmpty()
        assertThat(before.candidates.map { it.physicalDeviceId }).containsExactly(hoard)

        runBlocking { reveal.claim(hoard) }
        // A second release Reveal cannot join the same phone (`same_app`): a reinstall must revoke the old one.
        val second = repository()
        runBlocking { second.pair(PairingLink.parse(link())!!) }
        val refused = assertThrows(ClaimException::class.java) { runBlocking { second.claim(hoard) } }
        assertThat(refused.problem).isEqualTo(ClaimProblem.SAME_APP)
        // A debug build is another app of the same phone.
        val debug = repository(ClientDevice("reveal-debug", "Google", "Pixel 8"))
        runBlocking { debug.pair(PairingLink.parse(link())!!) }
        assertThat(runBlocking { debug.claim(hoard) }.others.mapNotNull { it.app }).containsExactly("hoard", "reveal")

        // "Not the same phone": back to a group of its own.
        val alone = runBlocking { reveal.claim(null) }
        assertThat(alone.others).isEmpty()
    }

    @Test
    fun `an older server has no device identity, nothing is reported and This device says so`() {
        server.features.clear()
        val reveal = repository()
        val account = runBlocking { reveal.pair(PairingLink.parse(link())!!) }
        runBlocking { reveal.syncMetadata() }
        assertThat(server.culling.reports).isEmpty()
        val device = runBlocking { reveal.thisDevice() }!!
        assertThat(device.supported).isFalse()
        assertThat(device.deviceId).isEqualTo(account.deviceId)
    }

    @Test
    fun `an updated Reveal reports itself once on its next sync, and again when it changes`() {
        val reveal = repository()
        runBlocking { reveal.pair(PairingLink.parse(link())!!) }
        runBlocking { reveal.syncMetadata() }
        runBlocking { reveal.syncMetadata() }
        assertThat(server.culling.reports).hasSize(1)
        assertThat(server.culling.reports.single().second["features"].toString()).isEqualTo("[\"culling\"]")
    }

    @Test
    fun `a culling pull asked for while a sync run holds the lock follows that run`() {
        val reveal = repository()
        runBlocking { reveal.pair(PairingLink.parse(link())!!) }
        val mutex = SyncRepository::class.java.getDeclaredField("mutex").apply { isAccessible = true }
            .get(reveal) as kotlinx.coroutines.sync.Mutex
        runBlocking {
            reveal.syncMetadata()
            val plain = server.cullingPages.also { reveal.syncMetadata() }.let { server.cullingPages - it }
            mutex.lock() // a run is going
            val before = server.cullingPages
            reveal.pullCulling()
            assertThat(server.cullingPages).isEqualTo(before) // not pulled under the run...
            mutex.unlock()
            reveal.syncMetadata()
            // ...but pulled right after it, on top of the run's own pull.
            assertThat(server.cullingPages - before).isEqualTo(plain + 1)
        }
    }
}
