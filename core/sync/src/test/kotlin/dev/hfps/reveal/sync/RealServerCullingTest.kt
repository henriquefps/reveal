package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.api.CullingChange
import dev.hfps.reveal.sync.api.PairRequest
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.CullingSync
import java.util.UUID
import kotlin.random.Random
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Culling and device identity against the real hoard-server (the pinned image of `scripts/sync/local-server.sh`, on
 * this machine's loopback): `REVEAL_SYNC_LINKS=<link>,<link>,<link>` (three fresh pairing links: two Reveal devices and
 * one "Hoard"). Skipped without them; run with `--no-configuration-cache`:
 *
 * ```
 * scripts/sync/local-server.sh start <work dir> 3473
 * REVEAL_SYNC_LINKS=$(for i in 1 2 3; do scripts/sync/local-server.sh pair <work dir>; done | paste -sd,) \
 *   ./gradlew --no-configuration-cache :core:sync:testDebugUnitTest --tests '*RealServerCullingTest*'
 * ```
 */
@RunWith(AndroidJUnit4::class)
class RealServerCullingTest {
    private val links = System.getenv("REVEAL_SYNC_LINKS").orEmpty().split(',').filter { it.isNotBlank() }
    private val devices = ArrayList<TestDevice>()
    private val model = ClientDevice("reveal", "samsung", "SM-X916B")

    @Before
    fun setUp() = assumeTrue("needs REVEAL_SYNC_LINKS (three pairing links)", links.size >= 3)

    @After
    fun tearDown() = devices.forEach(TestDevice::close)

    /** Pairs link [index] once per test run (a pairing token is single use). */
    private fun pair(index: Int, name: String, app: String): Triple<String, String, String> = keys[index] ?: run {
        val link = checkNotNull(PairingLink.parse(links[index]))
        val response = SyncApi(link.url, null).pair(
            PairRequest(link.token, name, "android", "test", app, model.manufacturer, model.model),
        )
        Triple(link.url, response.apiKey, response.serverId).also { keys[index] = it }
    }

    private fun device(index: Int, name: String): TestDevice {
        val (url, key, serverId) = pair(index, name, "reveal")
        return TestDevice(name, url, key, serverId, now = System.currentTimeMillis(), device = model)
            .also { devices += it }
    }

    /** "Hoard": another app pushing culling with its own key. */
    private fun hoard(): SyncApi = pair(2, "Hoard (test)", "hoard").let { (url, key) -> SyncApi(url, key) }

    private fun unique() = Random(UUID.randomUUID().hashCode()).nextBytes(8192)

    @Test
    fun ratingsPicksAndLabelsTravelPerFieldBetweenRevealAndHoard() {
        val phone = device(0, "Phone")
        val tablet = device(1, "Tablet")
        val raw = unique()
        val fingerprint = FileFingerprint.of(raw)
        val onPhone = phone.addPhoto("DSC1", raw)
        val onTablet = tablet.addPhoto("DSC1", raw)
        phone.sync()
        tablet.sync()

        // The same moment: a rating in Reveal, a pick in Hoard (it never saw the field: baseRev 0).
        val now = System.currentTimeMillis()
        phone.rate(onPhone, 4, at = now)
        phone.label(onPhone, "Red", at = now)
        val pick = hoard().pushCulling(
            listOf(CullingChange(fingerprint, null, "flag", JsonPrimitive("pick"), 0, now + 1)),
        ).results.single()
        assertThat(pick.status).isEqualTo("applied")
        phone.sync()
        tablet.sync()

        for ((device, id) in listOf(phone to onPhone, tablet to onTablet)) {
            val photo = device.photo(id)
            assertThat(photo.rating).isEqualTo(4)
            assertThat(photo.flag).isEqualTo(PickFlag.PICK)
            assertThat(photo.label).isEqualTo("Red")
            assertThat(device.queued()).isEqualTo(0)
        }
        val record = checkNotNull(tablet.api.cullingRecord(pick.itemId!!))
        assertThat(record.rating!!.value.jsonPrimitive.intOrNull).isEqualTo(4)
        assertThat(record.flag!!.value.jsonPrimitive.contentOrNull).isEqualTo("pick")
        // The tablet only applied them: the writers are still the phone and Hoard (no echo).
        val phoneId = phone.api.server().deviceId
        assertThat(record.rating.deviceId).isEqualTo(phoneId)
        assertThat(record.label?.deviceId).isEqualTo(phoneId)
        assertThat(record.flag.deviceId).isEqualTo(hoard().server().deviceId)

        // A reject keeps its stars, through the server.
        tablet.flag(onTablet, PickFlag.REJECT)
        tablet.sync()
        phone.sync()
        assertThat(phone.photo(onPhone).flag).isEqualTo(PickFlag.REJECT)
        assertThat(phone.photo(onPhone).rating).isEqualTo(4)
    }

    @Test
    fun theDeviceDeclaresCullingAndReportsItsModel() {
        val phone = device(0, "Phone")
        phone.sync()
        val view = phone.api.device()
        assertThat(view.features).contains("culling")
        assertThat(view.app).isEqualTo("reveal")
        assertThat(view.model).isEqualTo(model.model)
        assertThat(view.physicalDevice).isNotNull()
    }

    @Test
    fun anInvalidChangeIsAResultAndTheOthersApply() {
        val phone = device(0, "Phone")
        val raw = unique()
        phone.addPhoto("DSC2", raw)
        phone.sync()
        val fingerprint = FileFingerprint.of(raw)
        val now = System.currentTimeMillis()
        val results = phone.api.pushCulling(
            listOf(
                CullingChange(fingerprint, null, "label", JsonPrimitive("x".repeat(80)), 0, now),
                CullingChange(fingerprint, null, "rating", JsonPrimitive(2), 0, now + 24L * 60 * 60 * 1000),
            ),
        ).results
        assertThat(results[0].status).isEqualTo("invalid")
        assertThat(results[0].code).isEqualTo("label_too_long")
        assertThat(results[1].status).isEqualTo("applied")
        assertThat(results[1].clipped).isTrue()
        // A shot only this device has: the record lives under the id derived from its main file.
        assertThat(results[1].itemId).isEqualTo(CullingSync.derivedItemId(fingerprint))
    }

    @Test
    fun aLabelTheServerRefusesStaysOnTheDevice() {
        val phone = device(0, "Phone")
        val raw = unique()
        val id = phone.addPhoto("DSC3", raw)
        phone.sync()
        val long = "A Lightroom free-text label that is longer than the sixty-four characters a label may have"
        phone.label(id, long)
        phone.rate(id, 5)
        val report = phone.sync().culling!!
        assertThat(report.refused).isEqualTo(1)
        assertThat(phone.photo(id).label).isEqualTo(long)
        val record = checkNotNull(phone.api.cullingRecord(CullingSync.derivedItemId(FileFingerprint.of(raw))))
        assertThat(record.rating!!.value.jsonPrimitive.intOrNull).isEqualTo(5)
        assertThat(record.label).isNull()
    }

    private companion object {
        val keys = arrayOfNulls<Triple<String, String, String>>(3)
    }
}
