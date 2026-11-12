package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.data.preset.ImportResult
import dev.hfps.reveal.data.preset.PresetOrigin
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.engine.AuthorshipData
import dev.hfps.reveal.sync.engine.SyncedSetting
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Two simulated devices against the fake server (ADR 0017 semantics): edits, ids, conflicts, NAS, settings. */
@RunWith(AndroidJUnit4::class)
class SyncEngineTest {
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

    private fun device(name: String, settings: List<SyncedSetting> = emptyList()) =
        TestDevice(name, url, server.addDevice(name), server.serverId, settings).also { devices += it }

    private fun bytes(seed: Int, size: Int = 4096) = Random(seed).nextBytes(size)

    @Test
    fun `an edit made on one device reaches the other, which adopts the photo's ids`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(1)
        val onPhone = phone.addPhoto("DSC1", raw)
        val onTablet = tablet.addPhoto("DSC1", raw)
        phone.edit(phone.version(onPhone)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }

        phone.sync()
        tablet.sync()

        val phonePhoto = phone.db.let { runBlocking { it.photoDao().get(onPhone)!! } }
        val tabletPhoto = runBlocking { tablet.db.photoDao().get(onTablet)!! }
        assertThat(tabletPhoto.uuid).isEqualTo(phonePhoto.uuid)
        val tabletVersion = tablet.version(onTablet)
        assertThat(tablet.recipe(tabletVersion).light.exposureEv).isEqualTo(0.7f)
        assertThat(tablet.versions(onTablet)).hasSize(1)
        assertThat(runBlocking { tablet.edits.history(tabletVersion) }.steps).hasSize(1)
        assertThat(tabletPhoto.hasEdit || runBlocking { tablet.db.photoDao().get(onTablet)!!.hasEdit }).isTrue()
        // Applying never marks anything to push back.
        assertThat(tablet.pending()).isEqualTo(0)

        // And back: the tablet edits, the phone sees it.
        tablet.now += 1000
        tablet.edit(tabletVersion) { it.copy(light = it.light.copy(contrast = 25f)) }
        tablet.sync()
        phone.sync()
        val back = phone.recipe(phone.version(onPhone))
        assertThat(back.light.exposureEv).isEqualTo(0.7f)
        assertThat(back.light.contrast).isEqualTo(25f)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `concurrent edits keep the loser as a conflict copy on both devices`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(2)
        val p = phone.addPhoto("DSC2", raw)
        val t = tablet.addPhoto("DSC2", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.3f)) }
        phone.sync()
        tablet.sync()
        // Both edit the same version offline; the tablet's change is later and wins.
        phone.now += 1_000
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = -1f)) }
        tablet.now += 2_000
        tablet.edit(tablet.version(t)) { it.copy(light = it.light.copy(exposureEv = 1.5f)) }
        tablet.sync()
        phone.sync()
        tablet.sync()

        assertThat(server.copies()).hasSize(1)
        for ((device, photo) in listOf(phone to p, tablet to t)) {
            val versions = device.versions(photo)
            assertThat(versions).hasSize(2)
            val original = versions.single { it.copyOf == null }
            val copy = versions.single { it.copyOf != null }
            assertThat(copy.copyOf).isEqualTo(original.uuid)
            assertThat(copy.copyDevice).isEqualTo("Phone")
            assertThat(device.recipe(original.id).light.exposureEv).isEqualTo(1.5f)
            assertThat(device.recipe(copy.id).light.exposureEv).isEqualTo(-1f)
            // The grid shows both, the copy flagged.
            val rows = runBlocking { device.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())) }
            assertThat(rows.filter { it.id == photo }.map { it.conflict }).containsExactly(false, true)
        }
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `deleting a conflict copy removes it on the other device`() {
        `concurrent edits keep the loser as a conflict copy on both devices`()
        val phone = devices[0]
        val tablet = devices[1]
        val photo = phone.photoByFingerprint(FileFingerprint.of(bytes(2)))!!
        val copy = phone.versions(photo.id).single { it.copyOf != null }
        phone.now += 5_000
        runBlocking { phone.store.deleteVersion(copy.id) }
        phone.sync()
        tablet.sync()
        val onTablet = tablet.photoByFingerprint(FileFingerprint.of(bytes(2)))!!
        assertThat(tablet.versions(onTablet.id).map { it.copyOf }).containsExactly(null)
    }

    @Test
    fun `a photo only another device has appears in the Server source with its edit and rating`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(3)
        val p = phone.addPhoto("DSC3", raw, jpeg = bytes(33))
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.4f)) }
        runBlocking {
            phone.db.photoDao().setRating(listOf(p), 4)
            phone.db.photoDao().setFlag(listOf(p), PickFlag.PICK)
        }
        phone.sync()
        tablet.sync()
        // Only described so far (its original has not reached the server): in the database, not in the Server scope.
        fun serverScope() = runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.SERVER_ONLY)) }
        assertThat(tablet.photoByFingerprint(FileFingerprint.of(raw))).isNotNull()
        assertThat(serverScope()).isEmpty()
        // The phone's backup lands: it pushes the record again and the tablet asks about the shot at once.
        server.putOriginal(raw)
        runBlocking { phone.store.originalLanded(p, FileFingerprint.of(raw)) }
        phone.sync()
        tablet.sync()

        val remote = tablet.photoByFingerprint(FileFingerprint.of(raw))!!
        assertThat(remote.rating).isEqualTo(4)
        assertThat(remote.flag).isEqualTo(PickFlag.PICK)
        assertThat(remote.hasRaw).isTrue()
        assertThat(runBlocking { tablet.db.photoDao().files(remote.id) }).hasSize(2)
        assertThat(tablet.recipe(tablet.version(remote.id)).light.exposureEv).isEqualTo(0.4f)
        val all = runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.All))) }
        val onServer = serverScope()
        // The library home lists it once, like the Server-only filter (FIX/L).
        assertThat(all.map { it.id }).containsExactly(remote.id)
        assertThat(onServer.map { it.id }).containsExactly(remote.id)
        assertThat(onServer.single().remote).isTrue()

        // A rating on the tablet goes back to the phone.
        tablet.now += 1_000
        runBlocking { tablet.db.photoDao().setRating(listOf(remote.id), 2) }
        tablet.sync()
        phone.sync()
        assertThat(runBlocking { phone.db.photoDao().get(p)!!.rating }).isEqualTo(2)
    }

    @Test
    fun `NAS files become server photos, paired by stem, and a local copy is marked as in the cloud`() {
        val tablet = device("Tablet")
        val raw = bytes(4)
        val jpg = bytes(44)
        server.addNasFile("f1", "Shoots", "2026/Trip/DSC4.ARW", raw, FileFingerprint.of(raw))
        server.addNasFile("f1", "Shoots", "2026/Trip/DSC4.JPG", jpg, FileFingerprint.of(jpg))
        val other = bytes(5)
        server.addNasFile("f1", "Shoots", "2026/Trip/DSC5.ARW", other, FileFingerprint.of(other))
        val local = tablet.addPhoto("DSC5", other)

        tablet.sync()

        val nas = tablet.photoByFingerprint(FileFingerprint.of(raw))!!
        assertThat(nas.folder).isEqualTo("Shoots/2026/Trip")
        assertThat(
            runBlocking {
                tablet.db.photoDao().files(nas.id)
            }.map { it.name },
        ).containsExactly("DSC4.ARW", "DSC4.JPG")
        assertThat(nas.format.name).isEqualTo("RAW_PAIR")
        val rows =
            runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = LibraryScope.All))) }
        val localRow = rows.single { it.id == local }
        assertThat(localRow.cloudOriginal).isTrue()
        assertThat(localRow.remote).isFalse()
        // Nothing to push: NAS photos are the server's own.
        assertThat(tablet.pending()).isEqualTo(0)
        assertThat(server.records.keys.map { it.first }.toSet()).containsExactly("photo")
    }

    @Test
    fun `a local photo the server already has is looked up, not uploaded`() {
        val phone = device("Phone")
        val raw = bytes(6)
        server.addNasFile("f1", "Shoots", "DSC6.ARW", raw, FileFingerprint.of(raw))
        val missing = phone.addPhoto("DSC7", bytes(7))
        phone.sync()
        val cloud = runBlocking { phone.db.syncDao().cloud(missing) }!!
        assertThat(cloud.originalOnServer).isFalse()
        assertThat(cloud.checkedAt).isGreaterThan(0L)
        assertThat(runBlocking { phone.db.syncDao().backupCandidates(10) }.map { it.photoId }).containsExactly(missing)
    }

    @Test
    fun `settings follow the owner`() {
        var phoneValue = AuthorshipData("Ana", "© Ana")
        var tabletValue = AuthorshipData()
        fun setting(get: () -> AuthorshipData, set: (AuthorshipData) -> Unit) = object : SyncedSetting {
            override val key = "export.authorship"
            override fun read(): JsonElement = SyncApi.json.encodeToJsonElement(AuthorshipData.serializer(), get())
            override fun write(value: JsonElement) =
                set(SyncApi.json.decodeFromJsonElement(AuthorshipData.serializer(), value))
        }
        val phone = device("Phone", listOf(setting({ phoneValue }, { phoneValue = it })))
        phone.sync()
        val tablet = device("Tablet", listOf(setting({ tabletValue }, { tabletValue = it })))
        tablet.sync()
        assertThat(tabletValue).isEqualTo(AuthorshipData("Ana", "© Ana"))
        // Applying it does not push it back; a change afterwards does.
        val revBefore = server.record("setting", "export.authorship")!!.rev
        tablet.sync()
        assertThat(server.record("setting", "export.authorship")!!.rev).isEqualTo(revBefore)
        tabletValue = AuthorshipData("Ana B.", "© Ana B.")
        tablet.sync()
        phone.sync()
        assertThat(phoneValue.author).isEqualTo("Ana B.")
    }

    @Test
    fun `a pull from a reset server starts again from the beginning`() {
        val phone = device("Phone")
        val raw = bytes(8)
        val p = phone.addPhoto("DSC8", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.2f)) }
        phone.sync()
        runBlocking {
            phone.db.metaDao().put(dev.hfps.reveal.data.db.MetaEntity("sync.cursor", "${server.serverId.take(8)}:999"))
        }
        phone.sync()
        assertThat(runBlocking { phone.db.metaDao().get("sync.cursor") }).isNotEqualTo("${server.serverId.take(8)}:999")
    }

    @Test
    fun `a photo without a printed file waits, and an unedited version is never pushed`() {
        val phone = device("Phone")
        val p = phone.addPhoto("DSC9", bytes(9))
        runBlocking { phone.db.openHelper.writableDatabase.execSQL("UPDATE photo_files SET fingerprint = NULL") }
        phone.sync()
        assertThat(server.records).isEmpty()
        runBlocking {
            val file = phone.db.photoDao().files(p).single()
            phone.db.photoDao().setFingerprint(file.id, file.size, file.modified, FileFingerprint.of(bytes(9)))
        }
        phone.sync()
        assertThat(server.records.keys.map { it.first }).containsExactly("photo")
    }

    @Test
    fun `a thousand edited photos sync in one run and the other device gets them all`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val ids = (0 until 1000).map { i ->
            val p = phone.addPhoto("IMG$i", bytes(10_000 + i, 256))
            phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = (i % 10) / 10f)) }
            p
        }
        val start = System.nanoTime()
        val report = phone.sync()
        val push = (System.nanoTime() - start) / 1_000_000
        val pullStart = System.nanoTime()
        tablet.sync()
        val pull = (System.nanoTime() - pullStart) / 1_000_000
        println("SYNC_JVM push=${push}ms pull=${pull}ms pushed=${report.pushed}")
        assertThat(report.pushed).isEqualTo(1900)
        assertThat(ids).hasSize(1000)
        val server = runBlocking { checkNotNull(tablet.db.syncDao().serverSource()).id }
        val rows = runBlocking {
            tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.ofSource(server).copy(withUnopenable = true)))
        }
        assertThat(rows).hasSize(1000)
        assertThat(rows.count { it.hasEdit }).isEqualTo(900)
    }

    @Test
    fun `presets follow the owner with their group and favourite, renames and deletes too`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val patch = PresetPatch.fromSections(
            Recipe(light = Light(contrast = 30f)),
            listOf(PresetSection.CONTRAST),
        )
        val uuid = runBlocking {
            val group = phone.presets.createGroup("Travel")
            phone.presets.create("Crisp", patch, group).also { phone.presets.setFavourite(it, true) }
        }
        phone.sync()
        tablet.sync()
        val onTablet = runBlocking { tablet.presets.library().first() }
        val synced = onTablet[uuid]!!
        assertThat(synced.preset.patch).isEqualTo(patch)
        assertThat(synced.favourite).isTrue()
        assertThat(onTablet.groups.single { it.uuid == synced.groupUuid }.name).isEqualTo("Travel")
        assertThat(tablet.pending()).isEqualTo(0)

        tablet.now += 1000
        runBlocking { tablet.presets.rename(uuid, "Crisp travel") }
        tablet.sync()
        phone.sync()
        assertThat(runBlocking { phone.presets.library().first() }[uuid]!!.name).isEqualTo("Crisp travel")

        phone.now += 2000
        runBlocking { phone.presets.delete(uuid) }
        phone.sync()
        tablet.sync()
        val after = runBlocking { tablet.presets.library().first() }
        assertThat(after[uuid]).isNull()
        assertThat(after.groups.none { it.name == "Travel" }).isTrue()
        assertThat(server.record("preset", uuid)!!.deleted).isTrue()
    }

    @Test
    fun `a pack on trial stays on the device until it is kept`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val group = PackGroup("1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d", "Golden")
        val preset = Preset(
            "2b3c4d5e-6f70-4182-9a3b-4c5d6e7f8091",
            "Warm",
            PresetPatch.fromSections(Recipe(colour = Colour(vibrance = 20f)), listOf(PresetSection.VIBRANCE)),
            group = group.name,
        )
        val pack = PresetPack(
            id = "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d", name = "Pack", author = "Ana Example", version = 1,
            licenceName = "Personal use", licenceText = "Yours.", link = null, groups = listOf(group),
            presets = listOf(preset), groupOf = mapOf(preset.id to group.id), cover = null,
        )
        val bytes = java.io.ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()
        val added = runBlocking {
            phone.presets.importPack(java.io.ByteArrayInputStream(bytes))
        } as ImportResult.PackAdded
        phone.sync()
        assertThat(server.record("preset", preset.id)).isNull()
        runBlocking { phone.presets.keepPack(added.packRowId) }
        phone.sync()
        tablet.sync()
        val onTablet = runBlocking { tablet.presets.library().first() }
        val group2 = onTablet.groups.single { it.kind == GroupKind.PACK }
        assertThat(group2.pack!!.name).isEqualTo("Pack")
        assertThat(group2.pack!!.trial).isFalse()
        assertThat(group2.presets.single().origin).isEqualTo(PresetOrigin.PACK)
    }
}
