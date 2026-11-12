package dev.hfps.reveal.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.sync.account.MemorySecretStore
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.SyncHttpException
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.blobs.SmartPreviewMaker
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

/** Pairing links, pairing, errors and the URL rules of the client. */
@RunWith(AndroidJUnit4::class)
class SyncApiTest {
    private val server = FakeSyncServer()
    private lateinit var url: String

    @Before
    fun setUp() {
        url = server.start()
    }

    @After
    fun tearDown() = server.shutdown()

    private fun link(token: String, serverId: String? = server.serverId) =
        "revealsync://pair?v=1&url=${URLEncoder.encode(url, "UTF-8")}&token=$token" +
            (serverId?.let { "&server=$it" } ?: "")

    @Test
    fun `pairing links are parsed and anything else is refused`() {
        val parsed = PairingLink.parse("  " + link("abc") + "\n")!!
        assertThat(parsed.url).isEqualTo(url)
        assertThat(parsed.token).isEqualTo("abc")
        assertThat(parsed.serverId).isEqualTo(server.serverId)
        assertThat(PairingLink.parse("https://example.com/pair?v=1&url=x&token=y")).isNull()
        assertThat(PairingLink.parse("revealsync://pair?v=2&url=http%3A%2F%2Fa&token=y")).isNull()
        assertThat(PairingLink.parse("revealsync://pair?v=1&url=ftp%3A%2F%2Fa&token=y")).isNull()
        assertThat(PairingLink.parse("revealsync://pair?v=1&url=http%3A%2F%2Fa")).isNull()
        assertThat(PairingLink.parse("not a link")).isNull()
    }

    @Test
    fun `a base URL with a path prefix keeps it`() {
        assertThat(
            SyncApi.normalise("https://host.example/reveal").toString(),
        ).isEqualTo("https://host.example/reveal/")
        assertThat(SyncApi.normalise("http://10.0.2.2:3071/").toString()).isEqualTo("http://10.0.2.2:3071/")
    }

    @Test
    fun `errors carry the stable code, and a bad key is unauthorized`() {
        val error = assertThrows(SyncHttpException::class.java) { SyncApi(url, "rvk1_nope").server() }
        assertThat(error.status).isEqualTo(401)
        assertThat(error.unauthorized).isTrue()
        assertThat(error.transient).isFalse()
    }

    @Test
    fun `pairing stores the key, a used token is refused, and unpairing forgets the server`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java)).build()
        val secrets = MemorySecretStore()
        val accounts = SyncAccountStore(context, secrets)
        val store = SyncStore(db)
        val repository = SyncRepository(
            db = db,
            accounts = accounts,
            store = store,
            engine = SyncEngine(db, store),
            uploader = BlobUploader(
                db,
                store,
                DocumentTreeFactory {
                    error("none")
                },
                SmartPreviewMaker(File(context.cacheDir, "p")),
            ),
            files = ServerFiles(db, { null }, context.cacheDir),
            clientInfo = SyncRepository.ClientInfo("Test phone", "android", "0.4.0"),
        )
        try {
            val token = server.pairingToken()
            val account = runBlocking { repository.pair(PairingLink.parse(link(token))!!) }
            assertThat(account.serverId).isEqualTo(server.serverId)
            assertThat(secrets.read()).startsWith("rvk1_")
            assertThat(server.deviceNames.values).contains("Test phone")
            assertThat(runBlocking { repository.syncMetadata() }).isNotNull()

            val reused =
                assertThrows(PairException::class.java) {
                    runBlocking { repository.pair(PairingLink.parse(link(token))!!) }
                }
            assertThat(reused.problem).isEqualTo(PairProblem.INVALID_TOKEN)
            val other = assertThrows(PairException::class.java) {
                runBlocking { repository.pair(PairingLink.parse(link(server.pairingToken(), "another"))!!) }
            }
            assertThat(other.problem).isEqualTo(PairProblem.OTHER_SERVER)

            runBlocking { repository.unpair() }
            assertThat(accounts.account.value).isNull()
            assertThat(secrets.read()).isNull()
            assertThat(runBlocking { db.metaDao().get(SyncEngine.KEY_CURSOR) }).isNull()
            // A sync that runs after unpairing (a job queued before) does nothing and brings nothing back.
            assertThat(runBlocking { repository.syncMetadata() }).isNull()
            assertThat(runBlocking { db.syncDao().serverSource() }).isNull()
        } finally {
            db.close()
        }
    }
}
