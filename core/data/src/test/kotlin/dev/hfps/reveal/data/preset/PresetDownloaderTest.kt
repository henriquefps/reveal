package dev.hfps.reveal.data.preset

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * Importing a preset or pack from a link (docs/adr/0019 "Import from a link"): an https server with a test
 * certificate serves the file; the download is bounded, https only, cancellable, and the file then goes through the
 * same validation and trial flow as an opened file.
 */
@RunWith(AndroidJUnit4::class)
class PresetDownloaderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverCerts = HandshakeCertificates.Builder().heldCertificate(localhost).build()
    private val clientCerts = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
        .build()
    private val server = MockWebServer().apply { useHttps(serverCerts.sslSocketFactory()) }
    private lateinit var db: RevealDatabase
    private lateinit var repo: PresetRepository

    private val clarity = PresetPatch.fromSections(
        Recipe(effects = Effects(clarity = 30f)),
        listOf(PresetSection.CLARITY),
    )

    @Before
    fun setUp() {
        server.start()
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        repo = PresetRepository(db, folder.newFolder("covers"), { emptyList() }) { 1000L }
    }

    @After
    fun tearDown() {
        server.close()
        db.close()
    }

    private fun url(path: String) = server.url(path).toString()

    private fun downloader(maxBytes: Long = 32L * 1024 * 1024, callTimeout: Long = 30) =
        PresetDownloader(client, maxBytes, callTimeout)

    @Test
    fun `a pack downloaded from a link lands on trial like an opened file`() = runBlocking {
        val bytes = pack()
        server.enqueue(MockResponse.Builder().body(Buffer().write(bytes)).build())
        val file = folder.newFile("pack.part")
        val progress = mutableListOf<Pair<Long, Long?>>()
        downloader().download(url("/golden.revealpack"), file) { r, t -> progress += r to t }
        assertThat(file.readBytes()).isEqualTo(bytes)
        assertThat(progress.last()).isEqualTo(bytes.size.toLong() to bytes.size.toLong())
        // Nothing but the request line and generic headers: no cookies, no identity.
        val request = server.takeRequest()
        assertThat(request.headers["User-Agent"]).isEqualTo("Reveal")
        assertThat(request.headers["Cookie"]).isNull()

        val context = ApplicationProvider.getApplicationContext<Context>()
        val event = PresetFilesIo(context.contentResolver, repo).import(file)
        assertThat(event).isInstanceOf(PresetEvent.Imported::class.java)
        assertThat(repo.library().first().trialPacks.single().name).isEqualTo("Golden hour")
    }

    @Test
    fun `a single preset is imported too, and an invalid file is refused like a file`() = runBlocking {
        val preset = Preset("0f8fad5b-d9cb-469f-a165-70867728950e", "Soft matte", clarity, group = "Matte")
        server.enqueue(MockResponse.Builder().body(Buffer().write(PresetFiles.encode(preset))).build())
        server.enqueue(MockResponse.Builder().body("<html>not a preset</html>").build())
        val context = ApplicationProvider.getApplicationContext<Context>()
        val io = PresetFilesIo(context.contentResolver, repo)
        val file = folder.newFile("p.part")
        downloader().download(url("/soft.revealpreset"), file)
        assertThat(io.import(file)).isInstanceOf(PresetEvent.Imported::class.java)
        downloader().download(url("/page"), file)
        assertThat(io.import(file)).isInstanceOf(PresetEvent.Refused::class.java)
    }

    @Test
    fun `cleartext links and redirects to cleartext are refused`() {
        val file = folder.newFile("x.part")
        assertError(PresetDownloadError.NOT_HTTPS) {
            downloader().download("http://example.com/a.revealpack", file)
        }
        server.enqueue(
            MockResponse.Builder().code(302).addHeader("Location", "http://example.com/a.revealpack").build(),
        )
        assertError(PresetDownloadError.NOT_HTTPS) { downloader().download(url("/moved"), file) }
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `size is limited by the declared length and while reading`() {
        val file = folder.newFile("big.part")
        server.enqueue(MockResponse.Builder().body(Buffer().write(ByteArray(2048))).build())
        assertError(PresetDownloadError.TOO_LARGE) { downloader(maxBytes = 1024).download(url("/big"), file) }
        // A chunked body has no length: the count while reading stops it.
        server.enqueue(MockResponse.Builder().chunkedBody(Buffer().write(ByteArray(4096)), 256).build())
        assertError(PresetDownloadError.TOO_LARGE) { downloader(maxBytes = 1024).download(url("/chunked"), file) }
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun `errors and timeouts are reported, cancelling stops the transfer`() {
        val file = folder.newFile("e.part")
        server.enqueue(MockResponse.Builder().code(404).build())
        val status = assertError(PresetDownloadError.HTTP_STATUS) { downloader().download(url("/gone"), file) }
        assertThat(status.status).isEqualTo(404)
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(ByteArray(64))).headersDelay(5, TimeUnit.SECONDS).build(),
        )
        assertError(PresetDownloadError.TIMEOUT) { downloader(callTimeout = 1).download(url("/slow"), file) }

        server.enqueue(
            MockResponse.Builder().body(Buffer().write(ByteArray(64 * 1024)))
                .throttleBody(1024, 1, TimeUnit.SECONDS).build(),
        )
        runBlocking {
            val received = kotlinx.coroutines.CompletableDeferred<Unit>()
            val job = async(Dispatchers.IO) {
                downloader().download(url("/throttled"), file) { r, _ -> if (r > 0) received.complete(Unit) }
            }
            withTimeout(10_000) { received.await() }
            job.cancel()
            val outcome = runCatching { job.await() }
            assertThat(outcome.exceptionOrNull()).isInstanceOf(kotlinx.coroutines.CancellationException::class.java)
        }
    }

    private fun assertError(error: PresetDownloadError, block: suspend () -> Unit): PresetDownloadException {
        val e = assertThrows(PresetDownloadException::class.java) { runBlocking { block() } }
        assertThat(e.error).isEqualTo(error)
        return e
    }

    private fun pack(): ByteArray {
        val group = PackGroup("1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d", "Warm")
        val presets = listOf(Preset("2b3c4d5e-6f70-4182-9a3b-4c5d6e7f8091", "Glow", clarity, group = group.name))
        val pack = PresetPack(
            id = "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d", name = "Golden hour", author = "Ana Example", version = 1,
            licenceName = "Personal use", licenceText = "Yours to use.", link = "https://example.com",
            groups = listOf(group), presets = presets, groupOf = presets.associate { it.id to group.id }, cover = null,
        )
        return ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()
    }
}
