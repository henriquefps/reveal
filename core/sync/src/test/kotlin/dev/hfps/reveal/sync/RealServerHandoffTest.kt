package dev.hfps.reveal.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.HandoffRequest
import dev.hfps.reveal.data.handoff.ServerItemRef
import dev.hfps.reveal.data.handoff.ServerPairing
import dev.hfps.reveal.data.handoff.ServerRefs
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.CullingChange
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.api.UploadCreateRequest
import dev.hfps.reveal.sync.handoff.SyncServerItems
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-off server references against the real hoard-server (pinned 0.9.0, `scripts/sync/local-server.sh`, loopback;
 * ADR 0011 "Amendment W3/H"): a NAS item and a backed-up (uploaded original) item resolve to Server photos, a JPEG
 * item that a later RAW replaced is followed once through `replacedBy`, and the culling another app set on the server
 * before the hand-off is on the photo when the batch is shown. `REVEAL_HANDOFF_DIR` = the server's work dir (the test
 * adds NAS files to `<dir>/mnt/nas/Shoots` and runs `local-server.sh reindex`), `REVEAL_HANDOFF_LINKS` = two fresh
 * pairing links (Reveal's device, then "Hoard"). Skipped without them; run with `--no-configuration-cache`:
 *
 * ```
 * D=$(mktemp -d) && scripts/sync/local-server.sh start $D 3474 app/src/androidTest/assets/faces/studio-trio.jpg
 * REVEAL_HANDOFF_DIR=$D REVEAL_HANDOFF_LINKS=$(for i in 1 2; do scripts/sync/local-server.sh pair $D; done | paste -sd,) \
 *   ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest --tests '*RealServerHandoffTest*'
 * scripts/sync/local-server.sh stop $D
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealServerHandoffTest {
    private val dir = System.getenv("REVEAL_HANDOFF_DIR")
    private val links = System.getenv("REVEAL_HANDOFF_LINKS").orEmpty().split(',').filter { it.isNotBlank() }
    private val model = ClientDevice("reveal", "samsung", "SM-X916B")
    private var device: TestDevice? = null

    @Before
    fun setUp() = assumeTrue("needs REVEAL_HANDOFF_DIR and two REVEAL_HANDOFF_LINKS", !dir.isNullOrBlank() && links.size >= 2)

    @After
    fun tearDown() {
        device?.close()
    }

    private fun jpeg(seed: Int): ByteArray {
        val image = BufferedImage(900, 600, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(seed * 37 % 256, 90, 160)
        g.fillRect(0, 0, 900, 600)
        g.color = Color(240, seed * 11 % 256, 30)
        g.fillOval(100, 80, 300 + seed % 200, 300)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray() +
            Random(seed.toLong()).nextBytes(64)
    }

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun nas(name: String, bytes: ByteArray) {
        val folder = File(dir, "mnt/nas/Shoots").apply { mkdirs() }
        File(folder, name).writeBytes(bytes)
    }

    private fun reindex() {
        val script = File(System.getenv("REVEAL_REPO") ?: "../..", "scripts/sync/local-server.sh")
        val process = ProcessBuilder("bash", script.path, "reindex", dir!!).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "reindex failed: $out" }
    }

    private fun <T : Any> waitFor(what: String, block: () -> T?): T {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            block()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("timed out waiting for $what")
    }

    private fun itemOf(api: SyncApi, fingerprint: String) =
        waitFor("an item for $fingerprint") { api.itemsLookup(listOf(fingerprint)).items.firstOrNull() }

    private fun backUp(api: SyncApi, bytes: ByteArray) {
        val created = api.createUpload(
            UploadCreateRequest(sha256(bytes), bytes.size.toLong(), "original", FileFingerprint.of(bytes)),
        )
        val id = created.uploadId ?: return
        var offset = created.offset
        while (offset < bytes.size) {
            val length = minOf(created.chunkSize.coerceAtLeast(1).toInt(), bytes.size - offset.toInt())
            offset = api.patchUpload(id, offset, length) { it.write(bytes, offset.toInt(), length) }.offset
        }
    }

    @Test
    fun aNasItemABackedUpItemAndAReplacedItemResolve_withTheCullingOfTheServer() {
        val reveal = checkNotNull(PairingLink.parse(links[0])).let {
            val paired = SyncApi(it.url, null).pair(PairRequest(it.token, "Reveal (test)", "android", "test", "reveal", model.manufacturer, model.model))
            TestDevice("Reveal", it.url, paired.apiKey, paired.serverId, now = System.currentTimeMillis(), device = model)
                .also { d -> device = d }
        }
        val hoard = checkNotNull(PairingLink.parse(links[1])).let {
            SyncApi(it.url, null).pair(PairRequest(it.token, "Hoard (test)", "android", "test", "hoard", "samsung", "SM-X916B"))
                .let { paired -> SyncApi(it.url, paired.apiKey) }
        }
        val serverId = reveal.api.server().serverId
        reveal.sync() // declares the device's culling feature, as every real run does

        val seed = (System.nanoTime() % 10_000).toInt()
        // (a) a NAS item.
        val nasBytes = jpeg(seed)
        nas("HA$seed.jpg", nasBytes)
        // (b) a backed-up (uploaded original) item: not on the NAS.
        val upBytes = jpeg(seed + 1)
        backUp(reveal.api, upBytes)
        // (c) a JPEG-only NAS item that a RAW of the same stem then replaces.
        val jpgBytes = jpeg(seed + 2)
        nas("HC$seed.jpg", jpgBytes)
        reindex()
        val nasItem = itemOf(reveal.api, FileFingerprint.of(nasBytes))
        val upItem = itemOf(reveal.api, FileFingerprint.of(upBytes))
        val jpgItem = itemOf(reveal.api, FileFingerprint.of(jpgBytes))
        nas("HC$seed.ARW", Random(seed.toLong()).nextBytes(RAW_BYTES))
        reindex()
        val replaced = waitFor("the JPEG item to be replaced") {
            reveal.api.item(jpgItem.id)?.takeIf { it.deleted && it.replacedBy != null }
        }

        // (d) Another app's culling on the server before the hand-off.
        val now = System.currentTimeMillis()
        hoard.pushCulling(
            listOf(
                CullingChange(FileFingerprint.of(nasBytes), null, "rating", JsonPrimitive(4), 0, now),
                CullingChange(FileFingerprint.of(nasBytes), null, "flag", JsonPrimitive("pick"), 0, now),
            ),
        ).results.forEach { assertThat(it.status).isEqualTo("applied") }

        val context = ApplicationProvider.getApplicationContext<Context>()
        val items = SyncServerItems(
            pairingOf = { ServerPairing(serverId, "Home", reveal.api.server().deviceId) },
            api = { reveal.api },
            pull = {
                val info = reveal.api.server()
                if (info.has("culling")) reveal.engine.culling.run(reveal.api, info)
            },
        )
        val handoffs = HandoffRepository(context.contentResolver, reveal.db, LibraryIndexer(reveal.db), server = items)
        val outcome = runBlocking {
            handoffs.receive(
                HandoffRequest(
                    uris = emptyList(),
                    title = "Real server",
                    server = ServerRefs(
                        serverId,
                        listOf(
                            ServerItemRef(nasItem.id, FileFingerprint.of(nasBytes), name = "HA$seed.jpg"),
                            ServerItemRef(upItem.id, FileFingerprint.of(upBytes)),
                            // The sender still names the JPEG item; the server says a RAW replaced it.
                            ServerItemRef(jpgItem.id, FileFingerprint.of(jpgBytes)),
                        ),
                        initialItem = nasItem.id,
                    ),
                ),
            )
        }

        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.notFound).isEqualTo(0)
        assertThat(outcome.issue).isNull()
        val rows = runBlocking { reveal.db.photoDao().rows(dev.hfps.reveal.data.db.PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS)) }
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.handoffTitle }.distinct()).containsExactly("Real server")
        // (c) the replaced JPEG item is the RAW's photo (named by the RAW), once.
        assertThat(replaced.replacedBy).isNotNull()
        assertThat(rows.map { it.name }).contains("HC$seed.ARW")
        assertThat(rows.map { it.name }).doesNotContain("HC$seed.jpg")
        // (a) and (d): the NAS photo opens first and carries the server's culling already.
        val first = runBlocking { reveal.db.photoDao().photoIdOfVersion(outcome.versionId)!! }
        val photo = checkNotNull(reveal.photo(first))
        assertThat(photo.name).isEqualTo("HA$seed.jpg")
        assertThat(photo.rating).isEqualTo(4)
        assertThat(photo.flag).isEqualTo(PickFlag.PICK)
        // (b) the backed-up item is a Server photo whose original is on the server's backup.
        val cloud = rows.map { runBlocking { reveal.db.syncDao().cloud(it.id) } }
        assertThat(cloud.all { it?.originalOnServer == true }).isTrue()
        assertThat(cloud.map { it?.originalSource }).contains("backup")
        assertThat(cloud.map { it?.originalSource }).contains("nas")
    }

    private companion object {
        const val WAIT_MS = 60_000L
        const val POLL_MS = 500L
        const val RAW_BYTES = 64 * 1024
    }
}
