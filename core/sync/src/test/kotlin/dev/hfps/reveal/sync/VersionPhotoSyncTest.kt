package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.sync.api.PushChange
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
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
@Suppress("LargeClass")
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

    private fun TestDevice.held(): List<String> = runBlocking {
        db.metaDao().get(SyncStore.KEY_HELD_VERSIONS)?.split(',')?.filter { it.isNotEmpty() }.orEmpty()
    }

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
        assertThat(phone.record("version", version.uuid)!!.rev).isEqualTo(0L)
        assertThat(phone.held()).containsExactly(version.uuid)
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
    fun `a held version the server has already goes up on its real revision without a conflict copy`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.sync()
        val raw = bytes(12)
        val photo = phone.addPhoto("DSC12", raw)
        val sql = phone.db.openHelper.writableDatabase
        sql.execSQL("UPDATE photo_files SET fingerprint = NULL WHERE photo_id = ?", arrayOf<Any>(photo))
        phone.editExposure(photo, 0.6f)
        // What 0.8 left: the version on the server (rev > 0) naming the photo without a fingerprint, no photo record.
        val version = phone.versions(photo).single()
        val local = phone.record("version", version.uuid)!!
        val data = VersionData(
            photoId = phone.photo(photo).uuid,
            recipe = SyncApi.json.parseToJsonElement(RecipeCodec.Default.encode(phone.recipe(version.id))),
        )
        val pushed = phone.api.push(
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
        runBlocking { phone.db.syncDao().putRecord(local.copy(rev = pushed.rev, dirty = false)) }
        // The W5 repair marks it again (same edit time, same device) while the photo still has no fingerprint: held.
        runBlocking { phone.db.syncDao().putRecord(local.copy(rev = pushed.rev, dirty = true)) }
        phone.sync()
        assertThat(phone.record("version", version.uuid)!!.rev).isEqualTo(pushed.rev)
        assertThat(phone.held()).containsExactly(version.uuid)
        assertThat(phone.pending()).isEqualTo(0)

        // The fingerprint arrives: the photo goes up, then the version on top of the server's revision.
        sql.execSQL(
            "UPDATE photo_files SET fingerprint = ? WHERE photo_id = ?",
            arrayOf<Any>(FileFingerprint.of(raw), photo),
        )
        phone.sync()
        val record = server.record("version", version.uuid)!!
        assertThat(record.rev).isGreaterThan(pushed.rev)
        assertThat(
            record.data!!.jsonObject["photoFingerprint"]!!.jsonPrimitive.content,
        ).isEqualTo(FileFingerprint.of(raw))
        assertThat(server.copies()).isEmpty()
        assertThat(phone.held()).isEmpty()
        assertThat(phone.pending()).isEqualTo(0)

        val onTablet = tablet.addPhoto("DSC12", raw)
        tablet.sync()
        assertThat(tablet.exposure(onTablet)).isEqualTo(0.6f)
        assertThat(tablet.versions(onTablet)).hasSize(1)
    }

    @Test
    fun `a pulled version that finds a photo by its fingerprint releases that photo's held versions`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.sync()
        val raw = bytes(13)
        val photo = phone.addPhoto("DSC13", raw)
        val sql = phone.db.openHelper.writableDatabase
        sql.execSQL("UPDATE photo_files SET fingerprint = NULL WHERE photo_id = ?", arrayOf<Any>(photo))
        phone.editExposure(photo, 0.3f)
        phone.sync()
        val held = phone.versions(photo).single()
        assertThat(phone.held()).containsExactly(held.uuid)
        // The fingerprint is filled in but the photo's own record is not pushed this time (e.g. still to be marked).
        sql.execSQL(
            "UPDATE photo_files SET fingerprint = ? WHERE photo_id = ?",
            arrayOf<Any>(FileFingerprint.of(raw), photo),
        )
        val uuid = phone.photo(photo).uuid
        runBlocking { phone.db.syncDao().deleteRecord("photo", uuid) }

        // Another device's version names the photo by an id unknown here and by its main file.
        val photoId = "6b1f0000-0000-4000-8000-00000000d013"
        val onTablet = tablet.addPhoto("DSC13", raw)
        tablet.api.push(
            listOf(
                PushChange(
                    "version",
                    "7c2e0000-0000-4000-8000-00000000d013",
                    0,
                    tablet.now,
                    data = SyncApi.json.encodeToJsonElement(
                        VersionData.serializer(),
                        VersionData(
                            photoId = photoId,
                            recipe = exposureRecipe(tablet, onTablet, 0.9f),
                            photoFingerprint = FileFingerprint.of(raw),
                        ),
                    ),
                ),
            ),
        )
        phone.sync()
        // The photo took the id the version names; its held version is released at once, not on its photo's record.
        assertThat(phone.photo(photo).uuid).isEqualTo(photoId)
        assertThat(phone.held()).isEmpty()
        phone.sync()
        val record = server.record("version", held.uuid)!!
        assertThat(record.data!!.jsonObject["photoId"]!!.jsonPrimitive.content).isEqualTo(photoId)
        assertThat(server.record("photo", photoId)).isNotNull()
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `a photo tombstone the server never had is not brought back by a later edit`() {
        val phone = device("Phone")
        val raw = bytes(8)
        val photo = phone.addPhoto("DSC8", raw)
        phone.sync()
        val uuid = phone.photo(photo).uuid
        // A tombstone this device keeps for an id the server holds no record of (rev 0, nothing left to push).
        runBlocking {
            server.records.remove("photo" to uuid)
            phone.db.syncDao().putRecord(
                SyncRecordEntity("photo", uuid, 0, dirty = false, deleted = true, modifiedAt = 1),
            )
        }
        phone.editExposure(photo, 0.1f)
        phone.sync()
        assertThat(server.record("photo", uuid)).isNull()
        assertThat(phone.record("photo", uuid)!!.deleted).isTrue()
        assertThat(server.versionRecords()).hasSize(1)
    }

    @Test
    fun `a preset applied to 300 unculled NAS photos goes up within the server's push limit`() {
        val phone = device("Phone")
        val fingerprints = (1..300).map { nas("DSC${1000 + it}.ARW", 1000 + it) }
        phone.sync()
        val photos = fingerprints.map { phone.photoByFingerprint(it)!!.id }
        for (photo in photos) phone.editExposure(photo, 0.3f)
        // Each version brings its photo: 600 changes, more than one push of 500 (hoard-server refuses the whole push).
        phone.sync()
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(server.versionRecords()).hasSize(300)
        for (photo in photos) assertThat(server.record("photo", phone.photo(photo).uuid)).isNotNull()
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `a record naming a photo's id from before it adopted another device's still finds it`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val fingerprint = nas("DSC10.ARW", 10)
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        val onTablet = tablet.photoByFingerprint(fingerprint)!!
        tablet.rate(onTablet.id, 2)
        tablet.sync()
        phone.sync()
        // The phone's photo took the tablet's id; its old id stays an alias.
        assertThat(phone.photo(onPhone.id).uuid).isEqualTo(onTablet.uuid)
        assertThat(runBlocking { phone.db.syncDao().alias(onPhone.uuid) }).isEqualTo(onPhone.id)

        // A version naming the old id, without a fingerprint (what 0.8 pushed), applies on the phone.
        val versionUuid = "7c2e0000-0000-4000-8000-00000000c143"
        tablet.api.push(
            listOf(
                PushChange(
                    "version",
                    versionUuid,
                    0,
                    tablet.now,
                    data = SyncApi.json.encodeToJsonElement(
                        VersionData.serializer(),
                        VersionData(photoId = onPhone.uuid, recipe = exposureRecipe(phone, onPhone.id, 0.6f)),
                    ),
                ),
            ),
        )
        phone.sync()
        assertThat(phone.versions(onPhone.id).map { it.uuid }).contains(versionUuid)
        assertThat(phone.inbox()).isEmpty()
    }

    @Test
    fun `a version naming a photo the server never had gives its id to the matching photo`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val fingerprint = nas("DSC11.ARW", 11)
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        val onTablet = tablet.photoByFingerprint(fingerprint)!!
        // A version naming the phone's id and the main file, the phone's photo never pushed (no record anywhere).
        val versionUuid = "7c2e0000-0000-4000-8000-00000000c144"
        phone.api.push(
            listOf(
                PushChange(
                    "version",
                    versionUuid,
                    0,
                    phone.now,
                    data = SyncApi.json.encodeToJsonElement(
                        VersionData.serializer(),
                        VersionData(
                            photoId = onPhone.uuid,
                            recipe = exposureRecipe(phone, onPhone.id, 0.4f),
                            photoFingerprint = fingerprint,
                        ),
                    ),
                ),
            ),
        )
        tablet.sync()
        // The tablet's photo had no record: it takes the id the version names (both devices name it the same).
        assertThat(tablet.photo(onTablet.id).uuid).isEqualTo(onPhone.uuid)
        assertThat(runBlocking { tablet.db.syncDao().alias(onTablet.uuid) }).isEqualTo(onTablet.id)
        assertThat(tablet.exposure(onTablet.id)).isEqualTo(0.4f)
        assertThat(tablet.inbox()).isEmpty()
    }

    @Test
    fun `the repair pushes an edit of unknown author only after a pull showed nobody changed it`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(12)
        val photo = phone.addPhoto("DSC12", raw)
        phone.editExposure(photo, 0.5f)
        phone.sync()
        val version = phone.versions(photo).single()
        forgetFingerprint(version.uuid)
        val rev = server.record("version", version.uuid)!!.rev
        // As 0.5.3 and older left a version (author unknown, one history step as a pull writes it).
        phone.db.openHelper.writableDatabase.execSQL(
            "UPDATE versions SET edit_device = NULL WHERE id = ?",
            arrayOf<Any>(version.id),
        )
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        // Not pushed blindly: listed for a check after the next pull.
        assertThat(server.record("version", version.uuid)!!.rev).isEqualTo(rev)
        assertThat(runBlocking { phone.db.metaDao().get(SyncStore.KEY_REPAIR_CHECKS) }).isNotNull()
        phone.sync()
        assertThat(server.record("version", version.uuid)!!.rev).isGreaterThan(rev)
        assertThat(fingerprintOf(version.uuid)).isEqualTo(FileFingerprint.of(raw))
        assertThat(runBlocking { phone.db.metaDao().get(SyncStore.KEY_REPAIR_CHECKS) }).isNull()
        assertThat(server.copies()).isEmpty()

        // Another device changes such a version between the repair and the check: it is left alone.
        val onTablet = tablet.addPhoto("DSC12", raw)
        tablet.sync()
        phone.db.openHelper.writableDatabase.execSQL(
            "UPDATE versions SET edit_device = NULL WHERE id = ?",
            arrayOf<Any>(version.id),
        )
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        tablet.now += 1_000
        tablet.editExposure(onTablet, 0.9f)
        tablet.sync()
        val tabletRev = server.record("version", version.uuid)!!.rev
        phone.sync()
        assertThat(server.record("version", version.uuid)!!.rev).isEqualTo(tabletRev)
        assertThat(phone.exposure(photo)).isEqualTo(0.9f)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `the repair pushes at once an edit of unknown author whose history was made here`() {
        val phone = device("Phone")
        val photo = phone.addPhoto("DSC13", bytes(13))
        phone.editExposure(photo, 0.2f)
        phone.now += 5_000
        phone.editExposure(photo, 0.4f)
        phone.sync()
        val version = phone.versions(photo).single()
        forgetFingerprint(version.uuid)
        val rev = server.record("version", version.uuid)!!.rev
        phone.db.openHelper.writableDatabase.execSQL(
            "UPDATE versions SET edit_device = NULL WHERE id = ?",
            arrayOf<Any>(version.id),
        )
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
        phone.sync()
        assertThat(server.record("version", version.uuid)!!.rev).isGreaterThan(rev)
        assertThat(fingerprintOf(version.uuid)).isNotNull()
    }

    /** A build before the W5 repair and recovery: neither runs. */
    private fun TestDevice.oldBuild() {
        engine.parkedRecovery = false
        runBlocking { db.metaDao().put(dev.hfps.reveal.data.db.MetaEntity(SyncEngine.KEY_VERSION_PHOTOS, "1")) }
    }

    /** This build: the recovery on, the one-off repair not run yet. */
    private fun TestDevice.newBuild() {
        engine.parkedRecovery = true
        runBlocking { db.metaDao().delete(SyncEngine.KEY_VERSION_PHOTOS) }
    }

    /**
     * Pairing again on a build before this one: everything the old pairing knew is forgotten, the Server source with
     * its edited photos included (store.forget() and the cursors, then the old deleteServerSource), then a first sync
     * under a new device id. Returns the new id.
     */
    private fun TestDevice.oldRePair(): String {
        runBlocking {
            store.forget()
            db.metaDao().delete(SyncStore.KEY_KEPT_EDITS)
            // The old build never recorded its own device ids.
            db.metaDao().delete(SyncStore.KEY_OWN_DEVICES)
            db.syncDao().deleteServerSource()
            engine.resetCursors()
        }
        rekey(server.addDevice(name))
        now += 1_000
        sync()
        runBlocking { db.metaDao().put(dev.hfps.reveal.data.db.MetaEntity(SyncEngine.KEY_VERSION_PHOTOS, "1")) }
        return server.devices.getValue(key)
    }

    private fun TestDevice.markPhotoDirty(photoId: Long) = runBlocking {
        db.syncDao().putRecord(SyncRecordEntity("photo", photo(photoId).uuid, 0, dirty = true, modifiedAt = now))
    }

    /** The edited preview [device] uploaded for [versionUuid] (hoard-server 0.9.0, Hoard ADR 0020). */
    private fun putEdit(versionUuid: String, itemId: String, deviceId: String, at: Long) {
        server.edits.edits[versionUuid] =
            FakeEdits.Edit(itemId, "r1.1:$versionUuid", "", true, false, at, ByteArray(1), deviceId)
    }

    /**
     * The production history of 2026-11-15 (four orphans): on the old build, the phone (86f9c052) edited two unculled
     * NAS photos and the tablet (36f8f112) two others, each version going up alone and naming the author's random
     * photo id; each device's edited previews went up; the other device pushed its own photo records. Both paired
     * again on the old build (forgetting everything, the edited Server photos included) and parked all four versions;
     * the admin revoked the old ids. This build recovers all four on both devices through the edited previews, and
     * once each device is claimed into its old physical device, each pushes again the two it made.
     */
    @Suppress("LongMethod")
    @Test
    fun `four versions orphaned by old re-pairs are recovered on both devices and pushed again by their authors`() {
        server.hoard(edits = true)
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.oldBuild()
        tablet.oldBuild()
        val oldPhone = server.devices.getValue(phone.key)
        val oldTablet = server.devices.getValue(tablet.key)
        val fingerprints = (1..4).map { nas("DSC0900$it.ARW", 40 + it) }
        val items = fingerprints.map { server.culling.addItem(listOf(it)) }
        phone.sync()
        tablet.sync()

        // Phone: versions 0 and 1; tablet: 2 and 3. Exposure i + 1 tenths.
        val authors = listOf(phone, phone, tablet, tablet)
        val uuids = fingerprints.mapIndexed { i, fp ->
            val author = authors[i]
            val photo = author.photoByFingerprint(fp)!!
            author.now += 1_000
            author.editExposure(photo.id, (i + 1) / 10f)
            author.legacyPushVersion(photo.id).also {
                putEdit(it, items[i], server.devices.getValue(author.key), author.now)
            }
        }
        for (i in uuids.indices) {
            assertThat(
                server.record("photo", authors[i].photoByFingerprint(fingerprints[i])!!.uuid),
            ).isNull()
        }
        // The other device pushes its own photo record for each (a cull, a hand-off: anything that marks it).
        fingerprints.forEachIndexed { i, fp ->
            val other = if (authors[i] === phone) tablet else phone
            other.markPhotoDirty(other.photoByFingerprint(fp)!!.id)
        }
        phone.sync()
        tablet.sync()
        phone.sync()
        assertThat(phone.inbox().map { it.recordId }).containsExactly(uuids[2], uuids[3])
        assertThat(tablet.inbox().map { it.recordId }).containsExactly(uuids[0], uuids[1])

        // Both pair again on the old build: all four parked on both, no edit shown.
        val newPhone = phone.oldRePair()
        val newTablet = tablet.oldRePair()
        phone.sync()
        for (device in listOf(phone, tablet)) {
            assertThat(device.inbox().map { it.recordId }).containsExactlyElementsIn(uuids)
            for (fp in fingerprints) assertThat(device.exposure(device.photoByFingerprint(fp)!!.id)).isEqualTo(0f)
        }
        server.revoke(oldPhone)
        server.revoke(oldTablet)
        val revs = uuids.map { server.record("version", it)!!.rev }

        // This build, first and second sync on each: all four applied locally, nothing pushed (no proof yet).
        phone.newBuild()
        tablet.newBuild()
        repeat(2) {
            phone.now += 60_000
            phone.sync()
            tablet.now += 60_000
            tablet.sync()
        }
        for (device in listOf(phone, tablet)) {
            fingerprints.forEachIndexed { i, fp ->
                assertThat(device.exposure(device.photoByFingerprint(fp)!!.id)).isEqualTo((i + 1) / 10f)
            }
            assertThat(device.inbox()).isEmpty()
            assertThat(device.pending()).isEqualTo(0)
        }
        assertThat(uuids.map { server.record("version", it)!!.rev }).isEqualTo(revs)
        assertThat(uuids.map { server.record("version", it)!!.deviceId })
            .containsExactly(oldPhone, oldPhone, oldTablet, oldTablet).inOrder()
        assertThat(server.copies()).isEmpty()

        // "Same phone?" / "same tablet?": each new device joins its old physical device (Settings > Sync > This
        // device).
        server.culling.physical[newPhone] = oldPhone
        server.culling.physical[newTablet] = oldTablet
        phone.now += 60_000
        phone.sync()
        tablet.now += 60_000
        tablet.sync()
        phone.now += 60_000
        phone.sync()
        uuids.forEachIndexed { i, uuid ->
            val record = server.record("version", uuid)!!
            val author = authors[i]
            assertThat(record.deviceId).isEqualTo(if (author === phone) newPhone else newTablet)
            assertThat(record.rev).isGreaterThan(revs[i])
            val data = record.data!!.jsonObject
            assertThat(data["photoId"]!!.jsonPrimitive.content)
                .isEqualTo(author.photoByFingerprint(fingerprints[i])!!.uuid)
            assertThat(data["photoFingerprint"]!!.jsonPrimitive.content).isEqualTo(fingerprints[i])
        }
        for (device in listOf(phone, tablet)) {
            fingerprints.forEachIndexed { i, fp ->
                assertThat(device.exposure(device.photoByFingerprint(fp)!!.id)).isEqualTo((i + 1) / 10f)
                assertThat(
                    device.versions(device.photoByFingerprint(fp)!!.id).map {
                        it.uuid
                    },
                ).containsExactly(uuids[i])
            }
            assertThat(device.inbox()).isEmpty()
            assertThat(device.pending()).isEqualTo(0)
        }
        assertThat(server.copies()).isEmpty()
        // Settled: nothing goes up again.
        val settled = uuids.map { server.record("version", it)!!.rev }
        phone.sync()
        tablet.sync()
        assertThat(uuids.map { server.record("version", it)!!.rev }).isEqualTo(settled)
    }

    @Test
    fun `a parked version no edited preview names stays parked and is looked for once a day at most`() {
        server.hoard(edits = true)
        val phone = device("Phone")
        val tablet = device("Tablet")
        phone.oldBuild()
        val fingerprint = nas("DSC09101.ARW", 51)
        server.culling.addItem(listOf(fingerprint))
        phone.sync()
        tablet.sync()
        val onPhone = phone.photoByFingerprint(fingerprint)!!
        phone.editExposure(onPhone.id, 0.3f)
        val uuid = phone.legacyPushVersion(onPhone.id)
        // No preview went up for it.
        tablet.sync()
        assertThat(tablet.inbox().map { it.recordId }).containsExactly(uuid)
        val afterFirst = server.itemLookups
        assertThat(afterFirst).isGreaterThan(0)
        assertThat(runBlocking { tablet.db.metaDao().get(SyncStore.KEY_RECOVERY_MISSES) }).isNotNull()

        // Not again the same day.
        repeat(3) {
            tablet.now += 60 * 60 * 1000L
            tablet.sync()
        }
        assertThat(server.itemLookups).isEqualTo(afterFirst)
        // A day after the miss: one pass again; then two days, then four.
        tablet.now += 24 * 60 * 60 * 1000L
        tablet.sync()
        val afterSecond = server.itemLookups
        assertThat(afterSecond).isGreaterThan(afterFirst)
        tablet.now += 24 * 60 * 60 * 1000L
        tablet.sync()
        assertThat(server.itemLookups).isEqualTo(afterSecond)
        assertThat(tablet.inbox().map { it.recordId }).containsExactly(uuid)
        assertThat(tablet.exposure(tablet.photoByFingerprint(fingerprint)!!.id)).isEqualTo(0f)

        // The preview shows up later: found on the next due pass, applied, not pushed (another device made it).
        putEdit(uuid, server.culling.items.keys.single(), server.devices.getValue(phone.key), phone.now)
        val rev = server.record("version", uuid)!!.rev
        tablet.now += 2 * 24 * 60 * 60 * 1000L
        tablet.sync()
        assertThat(tablet.exposure(tablet.photoByFingerprint(fingerprint)!!.id)).isEqualTo(0.3f)
        assertThat(tablet.inbox()).isEmpty()
        assertThat(server.record("version", uuid)!!.rev).isEqualTo(rev)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `unpairing keeps edited Server photos and the next pairing pushes what the server did not hold`() {
        val phone = device("Phone")
        val synced = nas("DSC09201.ARW", 61)
        val unsynced = nas("DSC09202.ARW", 62)
        val plain = nas("DSC09203.ARW", 63)
        phone.sync()
        phone.editExposure(phone.photoByFingerprint(synced)!!.id, 0.5f)
        phone.sync()
        val syncedUuid = phone.versions(phone.photoByFingerprint(synced)!!.id).single().uuid
        assertThat(server.record("version", syncedUuid)).isNotNull()
        phone.editExposure(phone.photoByFingerprint(unsynced)!!.id, -0.5f)
        val unsyncedUuid = phone.versions(phone.photoByFingerprint(unsynced)!!.id).single().uuid

        runBlocking {
            phone.store.forget()
            phone.engine.resetCursors()
        }
        // Edited Server photos stay with their edits; the others go.
        assertThat(phone.exposure(phone.photoByFingerprint(synced)!!.id)).isEqualTo(0.5f)
        assertThat(phone.exposure(phone.photoByFingerprint(unsynced)!!.id)).isEqualTo(-0.5f)
        assertThat(phone.photoByFingerprint(plain)).isNull()

        phone.rekey(server.addDevice("Phone"))
        phone.now += 1_000
        phone.sync()
        assertThat(server.record("version", unsyncedUuid)).isNotNull()
        assertThat(server.copies()).isEmpty()
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(phone.exposure(phone.photoByFingerprint(synced)!!.id)).isEqualTo(0.5f)

        val tablet = device("Tablet")
        tablet.sync()
        assertThat(tablet.exposure(tablet.photoByFingerprint(synced)!!.id)).isEqualTo(0.5f)
        assertThat(tablet.exposure(tablet.photoByFingerprint(unsynced)!!.id)).isEqualTo(-0.5f)
        assertThat(tablet.inbox()).isEmpty()
        assertThat(server.copies()).isEmpty()
    }

    /** The server's record of [versionUuid] as 0.8 left it: without the photo's main-file fingerprint. */
    private fun forgetFingerprint(versionUuid: String) {
        val record = server.record("version", versionUuid)!!
        record.data = kotlinx.serialization.json.JsonObject(record.data!!.jsonObject - "photoFingerprint")
    }

    private fun fingerprintOf(versionUuid: String) =
        server.record("version", versionUuid)!!.data!!.jsonObject["photoFingerprint"]?.jsonPrimitive?.content

    @Test
    fun `a refused photo record lets its held versions go up on their own`() {
        val phone = device("Phone")
        phone.sync()
        val photo = phone.addPhoto("DSC21", bytes(21))
        val sql = phone.db.openHelper.writableDatabase
        sql.execSQL("UPDATE photo_files SET fingerprint = NULL WHERE photo_id = ?", arrayOf<Any>(photo))
        phone.editExposure(photo, 0.4f)
        phone.sync()
        val version = phone.versions(photo).single()
        assertThat(phone.held()).containsExactly(version.uuid)

        // A fingerprint the server refuses (`invalid_change`): the photo record never comes, the version goes alone.
        sql.execSQL("UPDATE photo_files SET fingerprint = 'v0:refused' WHERE photo_id = ?", arrayOf<Any>(photo))
        phone.sync()
        assertThat(server.record("photo", phone.photo(photo).uuid)).isNull()
        assertThat(server.record("version", version.uuid)).isNotNull()
        assertThat(phone.held()).isEmpty()
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `held versions that no longer exist are dropped from the list when it is read`() {
        val phone = device("Phone")
        phone.sync()
        val sql = phone.db.openHelper.writableDatabase
        val first = phone.addPhoto("DSC22", bytes(22))
        val second = phone.addPhoto("DSC23", bytes(23))
        for (photo in listOf(first, second)) {
            sql.execSQL("UPDATE photo_files SET fingerprint = NULL WHERE photo_id = ?", arrayOf<Any>(photo))
            phone.editExposure(photo, 0.1f)
        }
        phone.sync()
        val gone = phone.versions(first).single().uuid
        val kept = phone.versions(second).single().uuid
        assertThat(phone.held()).containsExactly(gone, kept)

        // The first photo is forgotten (its versions with it); the next read of the list drops its version.
        sql.execSQL("DELETE FROM photos WHERE id = ?", arrayOf<Any>(first))
        phone.editExposure(second, 0.2f)
        phone.sync()
        assertThat(phone.held()).containsExactly(kept)
    }

    private fun exposureRecipe(device: TestDevice, photoId: Long, ev: Float) = SyncApi.json.parseToJsonElement(
        RecipeCodec.Default.encode(
            device.recipe(device.version(photoId)).let { it.copy(light = it.light.copy(exposureEv = ev)) },
        ),
    )
}
