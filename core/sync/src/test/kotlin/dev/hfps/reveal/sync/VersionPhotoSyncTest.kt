package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.VersionData
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * ADR 0018 "W5": an edit of a photo whose record the server never had (a NAS photo made quietly from the feed, a
 * hand-off) reaches the other devices: the photo goes up before its version, a version finds its photo by the main
 * file's fingerprint, and the one-off repair fixes what 0.8 left on the server.
 */
@RunWith(AndroidJUnit4::class)
class VersionPhotoSyncTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()

    @Before
    fun setUp() {
        url = server.start()
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
    }

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId).also { devices += it }

    private fun bytes(seed: Int, size: Int = 4096) = Random(seed).nextBytes(size)

    private fun nas(name: String, seed: Int): String {
        val raw = bytes(seed)
        val fingerprint = FileFingerprint.of(raw)
        server.addNasFile("f1", "Shoots", "2026/$name", raw, fingerprint)
        return fingerprint
    }

    private fun TestDevice.inbox() = runBlocking { db.syncDao().inbox() }

    private fun TestDevice.record(kind: String, id: String) = runBlocking { db.syncDao().record(kind, id) }

    private fun TestDevice.exposure(photoId: Long) = recipe(version(photoId)).light.exposureEv

    private fun TestDevice.editExposure(photoId: Long, ev: Float) =
        edit(version(photoId)) { it.copy(light = it.light.copy(exposureEv = ev)) }

    @Test
    fun `an edit of an unculled NAS photo reaches the other device`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val fingerprint = nas("DSC1.ARW", 1)
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        val onTablet = tablet.photoByFingerprint(fingerprint)!!
        // Each device made its own Server photo from the NAS feed, with its own id and no record.
        assertThat(onPhone.uuid).isNotEqualTo(onTablet.uuid)
        assertThat(server.record("photo", onPhone.uuid)).isNull()

        phone.editExposure(onPhone.id, 0.8f)
        phone.sync()
        // The photo went up with (before) its version, and the version names it and its main file.
        assertThat(server.record("photo", onPhone.uuid)).isNotNull()
        val version = server.versionRecords().single()
        assertThat(version.rev).isGreaterThan(server.record("photo", onPhone.uuid)!!.rev)
        val data = version.data!!.jsonObject
        assertThat(data["photoId"]!!.jsonPrimitive.content).isEqualTo(onPhone.uuid)
        assertThat(data["photoFingerprint"]!!.jsonPrimitive.content).isEqualTo(fingerprint)

        tablet.sync()
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(0.8f)
        // The tablet's photo took the phone's id (its own was never on the server): one photo, one version.
        assertThat(tablet.photo(onTablet.id).uuid).isEqualTo(onPhone.uuid)
        assertThat(tablet.versions(onTablet.id)).hasSize(1)
        assertThat(tablet.inbox()).isEmpty()
        assertThat(tablet.pending()).isEqualTo(0)
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `an edited hand-off that was never culled reaches the other device's folder photo`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.sync()
        tablet.sync()
        val jpeg = bytes(2)
        val handoff = phone.addHandoffPhoto("IMG2", jpeg)
        // As a hand-off filed quietly leaves it (a server reference, ADR 0011 W3/H): no photo record at all.
        runBlocking { phone.db.syncDao().deleteRecord("photo", phone.photo(handoff).uuid) }
        phone.editExposure(handoff, -0.5f)
        phone.sync()
        assertThat(server.record("photo", phone.photo(handoff).uuid)).isNotNull()

        val inFolder = tablet.addFolderJpeg("IMG2", jpeg)
        tablet.sync()
        assertThat(tablet.exposure(inFolder)).isEqualTo(-0.5f)
        assertThat(tablet.versions(inFolder)).hasSize(1)
        assertThat(tablet.inbox()).isEmpty()
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `the production sequence ends with the edit on the tablet and an empty inbox`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val fingerprint = nas("DSC07676.ARW", 3)
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        val onTablet = tablet.photoByFingerprint(fingerprint)!!

        // 0.8 on the phone: the edit went up alone, naming the phone's own id for the photo.
        phone.editExposure(onPhone.id, 1.2f)
        val versionUuid = phone.legacyPushVersion(onPhone.id)
        // The tablet culls the photo: its own photo record goes up; the phone's version waits in its inbox.
        tablet.now += 1_000
        tablet.rate(onTablet.id, 3)
        tablet.sync()
        assertThat(server.record("photo", onTablet.uuid)).isNotNull()
        assertThat(tablet.inbox().map { it.recordId }).containsExactly(versionUuid)
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(0f)

        // The phone upgrades: it pulls the tablet's photo record and adopts its id, then the repair runs once.
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        assertThat(phone.photo(onPhone.id).uuid).isEqualTo(onTablet.uuid)
        val repaired = server.record("version", versionUuid)!!.data!!.jsonObject
        assertThat(repaired["photoId"]!!.jsonPrimitive.content).isEqualTo(onTablet.uuid)
        assertThat(repaired["photoFingerprint"]!!.jsonPrimitive.content).isEqualTo(fingerprint)
        assertThat(phone.pending()).isEqualTo(0)

        tablet.sync()
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(1.2f)
        assertThat(tablet.versions(onTablet.id).map { it.uuid }).containsExactly(versionUuid)
        // The old revision parked before is dropped: the newer one applied.
        assertThat(tablet.inbox()).isEmpty()
        assertThat(tablet.photo(onTablet.id).rating).isEqualTo(3)
        assertThat(server.copies()).isEmpty()

        // The repair is once: the next run pushes nothing again.
        val rev = server.record("version", versionUuid)!!.rev
        phone.sync()
        assertThat(server.record("version", versionUuid)!!.rev).isEqualTo(rev)
    }

    @Test
    fun `the repair sends a photo the server never had together with its edited version`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val fingerprint = nas("DSC4.ARW", 4)
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        val onTablet = tablet.photoByFingerprint(fingerprint)!!
        phone.editExposure(onPhone.id, -0.3f)
        val versionUuid = phone.legacyPushVersion(onPhone.id)
        tablet.sync()
        assertThat(tablet.inbox().map { it.recordId }).containsExactly(versionUuid)

        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        assertThat(server.record("photo", onPhone.uuid)).isNotNull()

        tablet.sync()
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(-0.3f)
        assertThat(tablet.photo(onTablet.id).uuid).isEqualTo(onPhone.uuid)
        assertThat(tablet.inbox()).isEmpty()
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `a version arriving before its photo record finds the photo by its fingerprint`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(5)
        val onTablet = tablet.addPhoto("DSC5", raw)
        tablet.sync()
        phone.sync()
        // Another device's version naming a photo id nobody knows here (its photo record has not arrived).
        val photoId = "6b1f0000-0000-4000-8000-00000000abcd"
        val data = VersionData(
            photoId = photoId,
            recipe = SyncApi.json.parseToJsonElement(
                RecipeCodec.Default.encode(
                    tablet.recipe(tablet.version(onTablet)).let { it.copy(light = it.light.copy(exposureEv = 0.4f)) },
                ),
            ),
            photoFingerprint = FileFingerprint.of(raw),
        )
        val versionUuid = "7c2e0000-0000-4000-8000-00000000abcd"
        phone.api.push(
            listOf(
                PushChange(
                    "version",
                    versionUuid,
                    0,
                    tablet.now,
                    data = SyncApi.json.encodeToJsonElement(VersionData.serializer(), data),
                ),
            ),
        )

        tablet.sync()
        assertThat(tablet.versions(onTablet).map { it.uuid }).contains(versionUuid)
        val placed = tablet.versions(onTablet).single { it.uuid == versionUuid }
        assertThat(tablet.recipe(placed.id).light.exposureEv).isEqualTo(0.4f)
        assertThat(tablet.inbox()).isEmpty()
        // Later records naming that id find the photo directly.
        assertThat(runBlocking { tablet.db.syncDao().alias(photoId) }).isEqualTo(onTablet)
    }

    @Test
    fun `with several local photos for one main file the version goes to the oldest one that is not hidden`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(6)
        val hidden = tablet.addPhoto("DSC6", raw)
        val second = tablet.addPhoto("DSC6", raw, source = tablet.addFolder("Copies"))
        val third = tablet.addPhoto("DSC6", raw, source = tablet.addFolder("More"))
        runBlocking { tablet.db.syncDao().applyPhotoHidden(hidden, true) }
        tablet.sync()
        val photoId = "6b1f0000-0000-4000-8000-00000000beef"
        val data = VersionData(
            photoId = photoId,
            recipe = SyncApi.json.parseToJsonElement(
                RecipeCodec.Default.encode(
                    tablet.recipe(tablet.version(second)).let { it.copy(light = it.light.copy(exposureEv = 0.9f)) },
                ),
            ),
            photoFingerprint = FileFingerprint.of(raw),
        )
        phone.api.push(
            listOf(
                PushChange(
                    "version",
                    "7c2e0000-0000-4000-8000-00000000beef",
                    0,
                    tablet.now,
                    data = SyncApi.json.encodeToJsonElement(VersionData.serializer(), data),
                ),
            ),
        )
        tablet.sync()
        assertThat(tablet.exposure(second)).isEqualTo(0.9f)
        assertThat(tablet.exposure(hidden)).isEqualTo(0f)
        assertThat(tablet.exposure(third)).isEqualTo(0f)
        assertThat(runBlocking { tablet.db.syncDao().alias(photoId) }).isEqualTo(second)
    }

    @Test
    fun `a version whose photo has no fingerprint yet is held and goes up after its photo`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.sync()
        val raw = bytes(7)
        val photo = phone.addPhoto("DSC7", raw)
        val sql = phone.db.openHelper.writableDatabase
        sql.execSQL("UPDATE photo_files SET fingerprint = NULL WHERE photo_id = ?", arrayOf<Any>(photo))
        phone.editExposure(photo, 0.2f)
        phone.sync()
        val version = phone.versions(photo).single()
        assertThat(server.versionRecords()).isEmpty()
        assertThat(phone.record("version", version.uuid)!!.rev).isEqualTo(SyncRecordEntity.HELD_REV)
        assertThat(phone.pending()).isEqualTo(0)

        // The indexer fills the fingerprint in: the photo goes up, then the held version in the same run.
        sql.execSQL(
            "UPDATE photo_files SET fingerprint = ? WHERE photo_id = ?",
            arrayOf<Any>(FileFingerprint.of(raw), photo),
        )
        phone.sync()
        assertThat(server.record("photo", phone.photo(photo).uuid)).isNotNull()
        assertThat(server.record("version", version.uuid)).isNotNull()
        assertThat(phone.record("version", version.uuid)!!.rev).isGreaterThan(0L)

        val onTablet = tablet.addPhoto("DSC7", raw)
        tablet.sync()
        assertThat(tablet.exposure(onTablet)).isEqualTo(0.2f)
    }

    @Test
    fun `a photo deleted from the server is not brought back by a later edit`() {
        val phone = device("Phone")
        val raw = bytes(8)
        val photo = phone.addPhoto("DSC8", raw)
        phone.sync()
        val uuid = phone.photo(photo).uuid
        // As ServerLibrary.deleteFromServer leaves it: the photo's record a tombstone (pushed).
        runBlocking {
            val record = phone.db.syncDao().record("photo", uuid)!!
            phone.db.syncDao().putRecord(record.copy(deleted = true, dirty = true, modifiedAt = phone.now + 1))
        }
        phone.sync()
        assertThat(server.record("photo", uuid)!!.deleted).isTrue()
        phone.editExposure(photo, 0.1f)
        phone.sync()
        assertThat(phone.record("photo", uuid)!!.deleted).isTrue()
    }
}
