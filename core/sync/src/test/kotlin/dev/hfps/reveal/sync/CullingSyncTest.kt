package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.CullingPendingEntity
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.sync.account.ClientDevice
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.CullingSync
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Culling through hoard-server's culling records (Hoard ADR 0016 "Client rules", Reveal ADR 0027), two devices and
 * "another app" (Hoard, the web gallery: a write by another device id) against the fake server.
 */
@RunWith(AndroidJUnit4::class)
class CullingSyncTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()
    private val model = ClientDevice("reveal", "samsung", "SM-S928B")

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

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId, device = model).also { devices += it }

    private fun idOf(device: TestDevice) = server.devices.getValue(device.key)

    private fun bytes(seed: Int) = Random(seed).nextBytes(4096)

    private fun fp(bytes: ByteArray) = FileFingerprint.of(bytes)

    private val wall: Long get() = System.currentTimeMillis()

    private fun value(itemId: String, field: String) = server.culling.field(itemId, field)?.value

    private fun ratingOf(bytes: ByteArray) = server.culling.recordOf(fp(bytes))?.fields?.get("rating")?.value
        ?.jsonPrimitive?.intOrNull

    private fun flagOf(bytes: ByteArray) =
        server.culling.recordOf(fp(bytes))?.fields?.get("flag")?.value?.jsonPrimitive?.contentOrNull

    @Test
    fun `the device declares culling before its photo records go up, so the server never bridges them`() {
        val phone = device("Phone")
        val raw = bytes(1)
        val id = phone.addPhoto("DSC1", raw)
        phone.rate(id, 3)

        val report = phone.sync()

        val (who, body) = server.culling.reports.single()
        assertThat(who).isEqualTo(idOf(phone))
        assertThat(body["app"]!!.jsonPrimitive.content).isEqualTo("reveal")
        assertThat(body["model"]!!.jsonPrimitive.content).isEqualTo("SM-S928B")
        assertThat(body["features"].toString()).isEqualTo("[\"culling\"]")
        assertThat(idOf(phone)).isIn(server.culling.aware)
        // The rating went up per field (the decision's own time), not through the bridge (the record's time).
        val rating = server.culling.recordOf(fp(raw))!!.fields.getValue("rating")
        assertThat(rating.value.jsonPrimitive.intOrNull).isEqualTo(3)
        assertThat(rating.deviceId).isEqualTo(idOf(phone))
        assertThat(rating.modifiedAt).isNotEqualTo(phone.now)
        assertThat(report.culling!!.pushed).isEqualTo(1)
        assertThat(phone.queued()).isEqualTo(0)

        // Nothing new: no second report, no push.
        val pushes = server.culling.pushes.size
        phone.sync()
        assertThat(server.culling.reports).hasSize(1)
        assertThat(server.culling.pushes).hasSize(pushes)
    }

    @Test
    fun `a rating in Reveal and a pick in Hoard made at the same time both survive`() {
        val phone = device("Phone")
        val raw = bytes(2)
        val id = phone.addPhoto("DSC2", raw)
        phone.sync()
        val now = wall
        phone.rate(id, 4, at = now)
        server.culling.set(fp(raw), "flag", JsonPrimitive("pick"), now + 5, device = "hoard-device")

        phone.sync()

        assertThat(phone.photo(id).rating).isEqualTo(4)
        assertThat(phone.photo(id).flag).isEqualTo(PickFlag.PICK)
        assertThat(ratingOf(raw)).isEqualTo(4)
        assertThat(flagOf(raw)).isEqualTo("pick")
    }

    @Test
    fun `another device's rating arrives through the culling records, quietly, and is never pushed back`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(3)
        val onPhone = phone.addPhoto("DSC3", raw)
        val onTablet = tablet.addPhoto("DSC3", raw)
        phone.sync()
        tablet.sync()
        phone.rate(onPhone, 4)
        phone.label(onPhone, "Red")
        phone.sync()
        val pushes = server.culling.pushes.size

        tablet.sync()

        assertThat(tablet.photo(onTablet).rating).isEqualTo(4)
        assertThat(tablet.photo(onTablet).label).isEqualTo("Red")
        // Applied quietly: nothing queued, no photo record marked, nothing pushed on the next run.
        assertThat(tablet.queued()).isEqualTo(0)
        assertThat(tablet.pending()).isEqualTo(0)
        tablet.sync()
        assertThat(server.culling.pushes).hasSize(pushes)
        assertThat(server.culling.recordOf(fp(raw))!!.fields.getValue("rating").deviceId).isEqualTo(idOf(phone))
    }

    @Test
    fun `while the server has culling records a photo record's rating is ignored`() {
        val phone = device("Phone")
        val raw = bytes(4)
        val id = phone.addPhoto("DSC4", raw)
        phone.sync()
        val uuid = phone.photo(id).uuid
        // Hoard rated it 2 a minute ago; an older Reveal (not culling-aware) writes the photo record with 5 stars and
        // an older time: the bridge loses against Hoard's newer value, the record keeps its 5.
        server.culling.set(fp(raw), "rating", JsonPrimitive(2), wall, device = "hoard-device")
        val old = SyncApi(url, server.addDevice("Old Reveal"))
        val base = server.record("photo", uuid)!!.rev
        old.push(
            listOf(
                PushChange(
                    kind = "photo",
                    id = uuid,
                    baseRev = base,
                    modifiedAt = wall - 60_000,
                    data = buildJsonObject {
                        put("fingerprint", fp(raw))
                        put(
                            "files",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("fingerprint", fp(raw))
                                        put("role", "RAW")
                                        put("name", "DSC4.ARW")
                                        put("size", 4096)
                                    },
                                )
                            },
                        )
                        put("name", "DSC4.ARW")
                        put("rating", 5)
                        put("flag", "NONE")
                    },
                ),
            ),
        )

        phone.sync()

        assertThat(phone.photo(id).rating).isEqualTo(2)
    }

    @Test
    fun `an offline decision keeps its time, a newer one made elsewhere wins, a later one here wins again`() {
        val phone = device("Phone")
        val raw = bytes(5)
        val id = phone.addPhoto("DSC5", raw)
        phone.sync()
        val now = wall
        phone.rate(id, 2, at = now - 60_000)
        server.culling.set(fp(raw), "rating", JsonPrimitive(5), now - 30_000, device = "hoard-device")

        phone.sync()
        assertThat(ratingOf(raw)).isEqualTo(5)
        assertThat(phone.photo(id).rating).isEqualTo(5)

        // Now the phone saw rev of the 5: its next decision applies whatever the clocks say (baseRev matches).
        phone.rate(id, 1, at = now - 90_000)
        phone.sync()
        assertThat(ratingOf(raw)).isEqualTo(1)
    }

    @Test
    fun `a time far in the future is clipped to the server's clock`() {
        val phone = device("Phone")
        val raw = bytes(6)
        val id = phone.addPhoto("DSC6", raw)
        phone.sync()
        phone.rate(id, 3, at = wall + 24L * 60 * 60 * 1000)

        phone.sync()

        val rating = server.culling.recordOf(fp(raw))!!.fields.getValue("rating")
        assertThat(rating.modifiedAt).isAtMost(wall)
        // A write a second from now (no baseRev) beats it: the clipped time no longer wins for a day.
        assertThat(server.culling.set(fp(raw), "rating", JsonPrimitive(1), wall + 1_000).winner).isEqualTo("incoming")
    }

    @Test
    fun `a NAS shot maps by any member file, so a phone holding only its JPEG gets and sets its rating`() {
        val phone = device("Phone")
        val raw = bytes(7)
        val jpeg = bytes(70)
        val itemId = server.culling.addItem(listOf(fp(raw), fp(jpeg)))
        val id = phone.addFolderJpeg("DSC7", jpeg)
        server.culling.set(fp(raw), "rating", JsonPrimitive(5), wall - 10_000, device = "hoard-device")

        phone.sync()
        assertThat(phone.photo(id).rating).isEqualTo(5)

        phone.rate(id, 2)
        phone.sync()
        assertThat(value(itemId, "rating")!!.jsonPrimitive.intOrNull).isEqualTo(2)
    }

    @Test
    fun `a replaced item's decisions follow the shot to the new item`() {
        val phone = device("Phone")
        val raw = bytes(8)
        val jpeg = bytes(80)
        val jpegItem = server.culling.addItem(listOf(fp(jpeg)))
        server.culling.set(fp(jpeg), "rating", JsonPrimitive(3), wall - 20_000, device = "hoard-device")
        val id = phone.addFolderJpeg("DSC8", jpeg)
        phone.sync()
        assertThat(phone.photo(id).rating).isEqualTo(3)

        // The RAW of the shot appeared on the NAS: a new item, the old one names it in `replacedBy`.
        val rawItem = server.culling.replaceItem(jpegItem, fp(raw), listOf(fp(jpeg)))
        server.culling.set(fp(raw), "flag", JsonPrimitive("pick"), wall - 10_000, device = "hoard-device")
        phone.sync()
        assertThat(phone.photo(id).rating).isEqualTo(3)
        assertThat(phone.photo(id).flag).isEqualTo(PickFlag.PICK)

        phone.rate(id, 4)
        phone.sync()
        assertThat(value(rawItem, "rating")!!.jsonPrimitive.intOrNull).isEqualTo(4)
    }

    @Test
    fun `a shot the server does not index is found by its derived id`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val jpeg = bytes(9)
        val onPhone = phone.addFolderJpeg("IMG9", jpeg)
        phone.sync()
        phone.rate(onPhone, 4)
        phone.sync()
        val record = server.culling.recordOf(fp(jpeg))!!
        assertThat(record.itemId).isEqualTo(CullingSync.derivedItemId(fp(jpeg)))

        // The tablet has the same file but never pushed anything for it: the feed's record has `fingerprints: []`.
        val onTablet = tablet.addFolderJpeg("IMG9", jpeg)
        tablet.sync()

        assertThat(tablet.photo(onTablet).rating).isEqualTo(4)
    }

    @Test
    fun `a label the server cannot take stays local and never blocks the queue`() {
        val phone = device("Phone")
        val raw = bytes(10)
        val id = phone.addPhoto("DSC10", raw)
        phone.sync()
        val long = "A free-text Lightroom label that is much longer than sixty-four characters"
        phone.label(id, long)
        phone.rate(id, 3)

        phone.sync()

        assertThat(ratingOf(raw)).isEqualTo(3)
        assertThat(server.culling.recordOf(fp(raw))!!.fields["label"]).isNull()
        assertThat(server.culling.pushes.flatten().none { it["field"]!!.jsonPrimitive.content == "label" }).isTrue()
        assertThat(phone.photo(id).label).isEqualTo(long)
        assertThat(phone.cullingPending(id).single { it.field == "label" }.state)
            .isEqualTo(CullingPendingEntity.STATE_INVALID)

        // Not sent again; a label set elsewhere later replaces it.
        phone.sync()
        server.culling.set(fp(raw), "label", JsonPrimitive("Red"), wall, device = "hoard-device")
        phone.sync()
        assertThat(phone.photo(id).label).isEqualTo("Red")
        assertThat(phone.cullingPending(id)).isEmpty()
    }

    @Test
    fun `a change the server refuses is dropped from the queue, the others go up`() {
        val phone = device("Phone")
        val raw = bytes(11)
        val id = phone.addPhoto("DSC11", raw)
        phone.sync()
        server.culling.refuse = "flag"
        phone.flag(id, PickFlag.PICK)
        phone.rate(id, 2)

        val report = phone.sync().culling!!

        assertThat(report.refused).isEqualTo(1)
        assertThat(ratingOf(raw)).isEqualTo(2)
        assertThat(flagOf(raw)).isNull()
        assertThat(phone.photo(id).flag).isEqualTo(PickFlag.PICK)
        val pushes = server.culling.pushes.size
        phone.sync()
        assertThat(server.culling.pushes).hasSize(pushes)
    }

    @Test
    fun `a reject keeps its stars on every device`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(12)
        val onPhone = phone.addPhoto("DSC12", raw)
        val onTablet = tablet.addPhoto("DSC12", raw)
        phone.sync()
        tablet.sync()
        phone.rate(onPhone, 4)
        phone.flag(onPhone, PickFlag.REJECT)
        phone.sync()
        tablet.sync()
        assertThat(ratingOf(raw)).isEqualTo(4)
        assertThat(flagOf(raw)).isEqualTo("reject")
        assertThat(tablet.photo(onTablet).rating).isEqualTo(4)
        assertThat(tablet.photo(onTablet).flag).isEqualTo(PickFlag.REJECT)

        phone.flag(onPhone, PickFlag.NONE)
        phone.sync()
        tablet.sync()
        assertThat(tablet.photo(onTablet).flag).isEqualTo(PickFlag.NONE)
        assertThat(tablet.photo(onTablet).rating).isEqualTo(4)
    }

    @Test
    fun `values set before this version go up once, as old decisions a newer one beats`() {
        val phone = device("Phone")
        val older = bytes(13)
        val newer = bytes(14)
        val a = phone.addPhoto("DSC13", older)
        val b = phone.addPhoto("DSC14", newer)
        phone.rate(a, 2)
        phone.rate(b, 3)
        phone.flag(b, PickFlag.PICK)
        // As if rated by Reveal 0.7 (no queue then): the first culling sync seeds them, dated when the photo came.
        runBlocking { phone.db.openHelper.writableDatabase.execSQL("DELETE FROM culling_pending") }
        server.culling.set(fp(older), "rating", JsonPrimitive(5), phone.now + 1_000, device = "hoard-device")

        phone.sync()

        // Hoard's rating is newer than the photo: it stays, and the phone takes it; the other values go up.
        assertThat(ratingOf(older)).isEqualTo(5)
        assertThat(phone.photo(a).rating).isEqualTo(5)
        assertThat(ratingOf(newer)).isEqualTo(3)
        assertThat(flagOf(newer)).isEqualTo("pick")
        assertThat(phone.queued()).isEqualTo(0)
    }

    @Test
    fun `an older server keeps culling inside the photo records`() {
        server.features.clear()
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(15)
        val onPhone = phone.addPhoto("DSC15", raw)
        val onTablet = tablet.addPhoto("DSC15", raw)
        phone.rate(onPhone, 4)

        val report = phone.sync()
        tablet.sync()

        assertThat(report.culling).isNull()
        assertThat(tablet.photo(onTablet).rating).isEqualTo(4)
        assertThat(server.culling.reports).isEmpty()
        assertThat(server.culling.pushes).isEmpty()
    }

    @Test
    fun `labels are checked like the server checks them`() {
        assertThat(CullingSync.validLabel("  Red ")).isEqualTo("Red")
        assertThat(CullingSync.validLabel(null)).isEqualTo("")
        assertThat(CullingSync.validLabel("x".repeat(64))).isNotNull()
        assertThat(CullingSync.validLabel("x".repeat(65))).isNull()
        assertThat(CullingSync.validLabel("Red\u0007")).isNull()
        // Characters, not UTF-16 units: 64 emoji fit.
        assertThat(CullingSync.validLabel("😀".repeat(64))).isNotNull()
    }

    @Test
    fun `the derived item id matches hoard-server's`() {
        // Go: store.ItemID("v1:" + 64 zeros) (sha256("hoard-item\x00" + fp), version 8, RFC variant).
        // The expected value was printed by that Go function (hoard-server `internal/store/culling.go`).
        assertThat(CullingSync.derivedItemId("v1:" + "0".repeat(64))).isEqualTo("99ee7403-00bd-842d-ba1b-6eb53e0b37c7")
    }
}
