package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0018 "W5" against the real hoard-server (the pinned image of `scripts/sync/local-server.sh`, loopback): an
 * edit of a NAS photo nobody culled reaches the other device, and the production sequence of 0.8 (the version alone on
 * the server, the other device's photo record, adoption) ends with the edit there after the one-off repair.
 * `REVEAL_VERSION_DIR` = the server's work dir (the test adds NAS files and runs `local-server.sh reindex`),
 * `REVEAL_VERSION_LINKS` = two fresh pairing links. Skipped without them; run with `--no-configuration-cache`:
 *
 * ```
 * D=$(mktemp -d) && scripts/sync/local-server.sh start $D 3475 app/src/androidTest/assets/faces/studio-trio.jpg
 * L=$(for i in 1 2; do scripts/sync/local-server.sh pair $D; done | paste -sd,)
 * REVEAL_VERSION_DIR=$D REVEAL_VERSION_LINKS=$L \
 *   ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest --tests '*RealServerVersionPhotoTest*'
 * scripts/sync/local-server.sh stop $D
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealServerVersionPhotoTest {
    private val dir = System.getenv("REVEAL_VERSION_DIR")
    private val links = System.getenv("REVEAL_VERSION_LINKS").orEmpty().split(',').filter { it.isNotBlank() }
    private val model = ClientDevice("reveal", "samsung", "SM-S928B")
    private val devices = ArrayList<TestDevice>()

    @Before
    fun setUp() = assumeTrue(
        "needs REVEAL_VERSION_DIR and two REVEAL_VERSION_LINKS",
        !dir.isNullOrBlank() && links.size >= 2,
    )

    @After
    fun tearDown() = devices.forEach(TestDevice::close)

    private fun device(index: Int, name: String): TestDevice {
        val (url, key, serverId) = keys[index] ?: run {
            val link = checkNotNull(PairingLink.parse(links[index]))
            val response = SyncApi(link.url, null).pair(
                PairRequest(link.token, name, "android", "test", "reveal", model.manufacturer, model.model),
            )
            Triple(link.url, response.apiKey, response.serverId).also { keys[index] = it }
        }
        return TestDevice(name, url, key, serverId, now = System.currentTimeMillis(), device = model)
            .also { devices += it }
    }

    /** A NAS file the server indexes (a RAW-named file: the feed lists it, the devices make a Server photo of it). */
    private fun nas(name: String): String {
        val bytes = Random(UUID.randomUUID().hashCode()).nextBytes(RAW_BYTES)
        File(File(dir, "mnt/nas/Shoots").apply { mkdirs() }, name).writeBytes(bytes)
        val script = File(System.getenv("REVEAL_REPO") ?: "../..", "scripts/sync/local-server.sh")
        val process = ProcessBuilder("bash", script.path, "reindex", dir!!).redirectErrorStream(true).start()
        val out = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "reindex failed: $out" }
        return FileFingerprint.of(bytes)
    }

    /** Syncs [device] until its NAS feed brought the file of [fingerprint] (the server indexes in the background). */
    private fun nasPhoto(device: TestDevice, fingerprint: String): PhotoEntity {
        val until = System.currentTimeMillis() + WAIT_MS
        while (System.currentTimeMillis() < until) {
            device.sync()
            device.photoByFingerprint(fingerprint)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        error("the NAS feed never listed $fingerprint")
    }

    private fun TestDevice.exposure(photoId: Long) = recipe(version(photoId)).light.exposureEv

    @Test
    fun anEditOfAnUnculledNasPhotoReachesTheOtherDevice() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val fingerprint = nas("W5A${System.nanoTime()}.ARW")
        val onPhone = nasPhoto(phone, fingerprint)
        val onTablet = nasPhoto(tablet, fingerprint)

        phone.edit(phone.version(onPhone.id)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        phone.sync()
        tablet.sync()

        assertThat(tablet.exposure(onTablet.id)).isEqualTo(0.7f)
        assertThat(tablet.photo(onTablet.id).uuid).isEqualTo(onPhone.uuid)
        assertThat(runBlocking { tablet.db.syncDao().inbox() }).isEmpty()
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun theProductionSequenceEndsWithTheEditOnTheTablet() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val fingerprint = nas("W5B${System.nanoTime()}.ARW")
        val onPhone = nasPhoto(phone, fingerprint)
        val onTablet = nasPhoto(tablet, fingerprint)

        // 0.8 on the phone: the version alone, naming the phone's own id for the photo.
        phone.edit(phone.version(onPhone.id)) { it.copy(light = it.light.copy(exposureEv = -0.6f)) }
        val versionUuid = phone.legacyPushVersion(onPhone.id)
        // The tablet culls it: its own photo record goes up, the phone's version waits in its inbox.
        tablet.rate(onTablet.id, 2)
        tablet.sync()
        assertThat(runBlocking { tablet.db.syncDao().inbox() }.map { it.recordId }).contains(versionUuid)

        // The phone upgrades: adopts the tablet's id from the pull, then the one-off repair pushes the version again.
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        assertThat(phone.photo(onPhone.id).uuid).isEqualTo(onTablet.uuid)

        tablet.sync()
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(-0.6f)
        assertThat(tablet.versions(onTablet.id).map { it.uuid }).containsExactly(versionUuid)
        assertThat(runBlocking { tablet.db.syncDao().inbox() }.map { it.recordId }).doesNotContain(versionUuid)
    }

    private companion object {
        const val RAW_BYTES = 64 * 1024
        const val WAIT_MS = 30_000L
        const val POLL_MS = 500L
        val keys = arrayOfNulls<Triple<String, String, String>>(2)
    }
}
