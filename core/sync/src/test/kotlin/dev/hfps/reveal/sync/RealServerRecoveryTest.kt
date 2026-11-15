package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.EditMeta
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0018 "W5 recovery" against the real hoard-server (the pinned image of `scripts/sync/local-server.sh`, loopback):
 * the production history of 2026-11-15, one orphan per device. On the old build each device edits a NAS photo (the
 * version alone, naming its random photo id) and uploads its edited preview; the other device pushes its photo record;
 * both pair again forgetting everything (the edited Server photos included) and park both versions; the admin revokes
 * the old ids. This build applies both on both devices through the server's edited previews and pushes nothing; once
 * each device is claimed into its old physical device, each pushes its own version again, without a conflict copy.
 * `REVEAL_RECOVERY_DIR` = the server's work dir (the test adds NAS files, pairs and revokes through the script).
 * Skipped without it; run with `--no-configuration-cache`:
 *
 * ```
 * D=$(mktemp -d) && scripts/sync/local-server.sh start $D 3476 app/src/androidTest/assets/faces/studio-trio.jpg
 * REVEAL_RECOVERY_DIR=$D ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest \
 *   --tests '*RealServerRecoveryTest*'
 * scripts/sync/local-server.sh stop $D
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealServerRecoveryTest {
    private val dir = System.getenv("REVEAL_RECOVERY_DIR")
    private val model = ClientDevice("reveal", "samsung", "SM-S928B")
    private val devices = ArrayList<TestDevice>()

    @Before
    fun setUp() = assumeTrue("needs REVEAL_RECOVERY_DIR", !dir.isNullOrBlank())

    @After
    fun tearDown() = devices.forEach(TestDevice::close)

    private fun script(vararg args: String): String {
        val script = File(System.getenv("REVEAL_REPO") ?: "../..", "scripts/sync/local-server.sh")
        val process = ProcessBuilder(listOf("bash", script.path) + args).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "local-server.sh ${args.first()} failed: $out" }
        return out.trim()
    }

    private data class Paired(val url: String, val key: String, val serverId: String, val deviceId: String)

    /** A fresh pairing. */
    private fun pair(name: String): Paired {
        val link = checkNotNull(PairingLink.parse(script("pair", dir!!).lines().last()))
        val response = SyncApi(link.url, null).pair(
            PairRequest(link.token, name, "android", "test", "reveal", model.manufacturer, model.model),
        )
        return Paired(link.url, response.apiKey, response.serverId, response.deviceId)
    }

    private fun device(name: String): Pair<TestDevice, String> {
        val paired = pair(name)
        val device = TestDevice(
            name,
            paired.url,
            paired.key,
            paired.serverId,
            now = System.currentTimeMillis(),
            device = model,
        )
        devices += device
        return device to paired.deviceId
    }

    private fun nas(name: String): String {
        val bytes = Random(UUID.randomUUID().hashCode()).nextBytes(RAW_BYTES)
        File(File(dir, "mnt/nas/Shoots").apply { mkdirs() }, name).writeBytes(bytes)
        script("reindex", dir!!)
        return FileFingerprint.of(bytes)
    }

    private fun nasPhoto(device: TestDevice, fingerprint: String): PhotoEntity {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            device.sync()
            device.photoByFingerprint(fingerprint)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("the NAS feed never listed $fingerprint")
    }

    private fun jpeg(seed: Int): ByteArray {
        val image = BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.color = Color(seed % 256, 120, 200)
        g.fillRect(0, 0, 600, 400)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    private fun TestDevice.exposure(fingerprint: String) =
        recipe(version(checkNotNull(photoByFingerprint(fingerprint)).id)).light.exposureEv

    /** The whole change feed, every page. */
    private fun TestDevice.feed(): List<dev.hfps.reveal.sync.api.FeedRecord> {
        val all = ArrayList<dev.hfps.reveal.sync.api.FeedRecord>()
        var cursor = ""
        do {
            val page = api.pull(cursor, PAGE)
            all += page.changes
            cursor = page.cursor
        } while (page.hasMore)
        return all
    }

    private fun TestDevice.inbox() = runBlocking { db.syncDao().inbox() }.map { it.recordId }

    private fun TestDevice.setBuild(old: Boolean) = runBlocking {
        engine.parkedRecovery = !old
        if (old) {
            db.metaDao().put(MetaEntity(SyncEngine.KEY_VERSION_PHOTOS, "1"))
        } else {
            db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS)
        }
    }

    /** The old build's re-pair: forget everything, the Server source included, then a first sync. Returns the id. */
    private fun TestDevice.oldRePair(): String {
        runBlocking {
            store.forget()
            db.metaDao().delete(SyncStore.KEY_KEPT_EDITS)
            // The old build never recorded its own device ids.
            db.metaDao().delete(SyncStore.KEY_OWN_DEVICES)
            db.syncDao().deleteServerSource()
            engine.resetCursors()
        }
        val paired = pair(name)
        rekey(paired.key)
        sync()
        runBlocking { db.metaDao().put(MetaEntity(SyncEngine.KEY_VERSION_PHOTOS, "1")) }
        return paired.deviceId
    }

    @Suppress("LongMethod")
    @Test
    fun orphansOfOldRePairsAreRecoveredThroughEditedPreviewsAndPushedAgainByTheirAuthors() {
        val (phone, oldPhone) = device("Phone")
        val (tablet, oldTablet) = device("Tablet")
        phone.setBuild(old = true)
        tablet.setBuild(old = true)
        val fingerprints = listOf(nas("W5R${System.nanoTime()}a.ARW"), nas("W5R${System.nanoTime()}b.ARW"))
        fingerprints.forEach {
            nasPhoto(phone, it)
            nasPhoto(tablet, it)
        }
        val authors = listOf(phone, tablet)
        val evs = listOf(0.6f, -0.4f)

        val uuids = fingerprints.mapIndexed { i, fp ->
            val author = authors[i]
            val photo = checkNotNull(author.photoByFingerprint(fp))
            author.edit(author.version(photo.id)) { it.copy(light = it.light.copy(exposureEv = evs[i])) }
            val uuid = author.legacyPushVersion(photo.id)
            // Its edited preview (Hoard ADR 0020), as EditPreviews uploads it.
            val itemId = author.api.itemsLookup(listOf(fp)).matches.single { it.fingerprint == fp }.itemId
            val digest = "r1.1:" + "%064x".format(i + 1)
            author.api.putEdit(itemId, uuid, EditMeta(digest, author.now, "", false, true), jpeg(i * 70))
            uuid
        }
        // The other device pushes its own photo record for each.
        fingerprints.forEachIndexed { i, fp ->
            val other = authors[1 - i]
            runBlocking {
                other.db.syncDao().putRecord(
                    SyncRecordEntity(
                        "photo",
                        checkNotNull(other.photoByFingerprint(fp)).uuid,
                        0,
                        dirty = true,
                        modifiedAt = System.currentTimeMillis(),
                    ),
                )
            }
            other.sync()
        }

        val newPhone = phone.oldRePair()
        val newTablet = tablet.oldRePair()
        phone.sync()
        for (device in authors) {
            assertThat(device.inbox()).containsAtLeastElementsIn(uuids)
            fingerprints.forEach { assertThat(device.exposure(it)).isEqualTo(0f) }
        }
        script("revoke", dir!!, oldPhone)
        script("revoke", dir, oldTablet)
        val feedNow = phone.feed()
        val revs = uuids.map { u -> feedNow.last { it.id == u }.rev }

        // This build, twice on each: applied locally, nothing pushed.
        phone.setBuild(old = false)
        tablet.setBuild(old = false)
        repeat(2) {
            phone.sync()
            tablet.sync()
        }
        for (device in authors) {
            fingerprints.forEachIndexed { i, fp -> assertThat(device.exposure(fp)).isEqualTo(evs[i]) }
            assertThat(device.inbox()).containsNoneIn(uuids)
        }
        val feed = phone.feed()
        assertThat(uuids.map { u -> feed.last { it.id == u }.rev }).isEqualTo(revs)

        // Claimed into their old physical devices: each pushes its own again.
        phone.api.claim(oldPhone)
        tablet.api.claim(oldTablet)
        phone.sync()
        tablet.sync()
        phone.sync()
        val after = phone.feed()
        uuids.forEachIndexed { i, uuid ->
            val record = after.last { it.id == uuid }
            assertThat(record.deviceId).isEqualTo(if (i == 0) newPhone else newTablet)
            assertThat(record.rev).isGreaterThan(revs[i])
            val data = checkNotNull(record.data).jsonObject
            assertThat(data["photoFingerprint"]!!.jsonPrimitive.content).isEqualTo(fingerprints[i])
            assertThat(data["photoId"]!!.jsonPrimitive.content)
                .isEqualTo(checkNotNull(authors[i].photoByFingerprint(fingerprints[i])).uuid)
        }
        assertThat(after.filter { it.copyOf in uuids }).isEmpty()
        for (device in authors) {
            fingerprints.forEachIndexed { i, fp -> assertThat(device.exposure(fp)).isEqualTo(evs[i]) }
            assertThat(device.inbox()).containsNoneIn(uuids)
            assertThat(device.pending()).isEqualTo(0)
        }
    }

    private companion object {
        const val RAW_BYTES = 64 * 1024
        const val WAIT_MS = 30_000L
        const val POLL_MS = 500L
        const val PAGE = 200
    }
}
