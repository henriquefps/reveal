package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.edit.CopySource
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.data.export.ExportPresetRepository
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Virtual copies, snapshots and export presets between two devices (docs/adr/0025): copies travel like versions and
 * stay apart from conflict copies, snapshots ride in their version's record, export presets are `setting` records.
 */
@RunWith(AndroidJUnit4::class)
class VersionsSyncTest {
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

    private fun device(name: String) = TestDevice(name, url, server.addDevice(name), server.serverId).also {
        devices += it
    }

    private fun TestDevice.versionsRepo() = VersionRepository(db, edits) { now }

    private fun TestDevice.exportPresets() = ExportPresetRepository(db) { now }

    private fun bytes(seed: Int) = Random(seed).nextBytes(4096)

    private val names: (Int) -> String = { "Copy $it" }

    @Test
    fun `a virtual copy reaches the other device as its own cell, even unedited`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(1)
        val p = phone.addPhoto("DSC1", raw)
        val t = tablet.addPhoto("DSC1", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.5f)) }
        val colour = runBlocking { phone.versionsRepo().createCopy(phone.version(p), CopySource.CURRENT, names)!! }
        phone.edit(colour) { it.copy(profile = it.profile.copy(treatment = Treatment.BLACK_WHITE)) }
        runBlocking { phone.versionsRepo().createCopy(phone.version(p), CopySource.ORIGINAL, names) }

        phone.sync()
        tablet.sync()

        val versions = tablet.versions(t)
        assertThat(versions.map { it.name }).containsExactly(null, "Copy 1", "Copy 2").inOrder()
        assertThat(versions.map { it.copyOf }).containsExactly(null, null, null)
        assertThat(tablet.recipe(versions[0].id).light.exposureEv).isEqualTo(0.5f)
        assertThat(tablet.recipe(versions[1].id).profile.treatment).isEqualTo(Treatment.BLACK_WHITE)
        assertThat(tablet.recipe(versions[2].id)).isEqualTo(Recipe())
        val rows = runBlocking { tablet.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())) }
        assertThat(rows.filter { it.id == t }.map { it.conflict }).containsExactly(false, false, false)
        assertThat(rows.filter { it.id == t }.map { it.versionName }).containsExactly(null, "Copy 1", "Copy 2")
            .inOrder()
        assertThat(server.copies()).isEmpty()
        assertThat(tablet.pending()).isEqualTo(0)

        // A rename and a delete follow.
        tablet.now += 1_000
        runBlocking {
            tablet.versionsRepo().rename(versions[1].id, "B&W")
            tablet.versionsRepo().delete(versions[2].id)
        }
        tablet.sync()
        phone.sync()
        assertThat(phone.versions(p).map { it.name }).containsExactly(null, "B&W").inOrder()
    }

    @Test
    fun `copies made on both devices at the same place stay two copies, not one adopted by the other`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(2)
        val p = phone.addPhoto("DSC2", raw)
        val t = tablet.addPhoto("DSC2", raw)
        runBlocking { phone.versionsRepo().createCopy(phone.version(p), CopySource.ORIGINAL, names) }
        runBlocking { tablet.versionsRepo().createCopy(tablet.version(t), CopySource.ORIGINAL, names) }
        phone.sync()
        tablet.sync()
        phone.sync()
        for ((device, photo) in listOf(phone to p, tablet to t)) {
            val versions = device.versions(photo)
            assertThat(versions).hasSize(3)
            assertThat(versions.map { it.uuid }).containsNoDuplicates()
            assertThat(versions.map { it.copyOf }).containsExactly(null, null, null)
        }
        // The two copies are the same two on both devices (an unedited first version is never pushed, FIX/W).
        fun copies(device: TestDevice, photo: Long) = device.versions(photo).filter { it.position > 0 }.map { it.uuid }
        assertThat(copies(phone, p).toSet()).isEqualTo(copies(tablet, t).toSet())
    }

    @Test
    fun `snapshots ride in their version's record, renames and deletes included`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(3)
        val p = phone.addPhoto("DSC3", raw)
        val t = tablet.addPhoto("DSC3", raw)
        val edited = Recipe(light = Light(exposureEv = 1f))
        phone.edit(phone.version(p)) { edited }
        val snap = runBlocking { phone.versionsRepo().createSnapshot(phone.version(p), "Before the crop", edited) }
        phone.sync()
        tablet.sync()
        val onTablet = runBlocking { tablet.versionsRepo().snapshots(tablet.version(t)) }
        assertThat(onTablet.map { it.name }).containsExactly("Before the crop")
        val snapshotRecipe = runBlocking { tablet.versionsRepo().snapshotRecipe(onTablet.single().id)!!.second }
        assertThat(snapshotRecipe).isEqualTo(edited)
        assertThat(onTablet.single().uuid).isEqualTo(
            runBlocking {
                phone.versionsRepo().snapshots(phone.version(p))
            }.single().uuid,
        )

        phone.now += 1_000
        runBlocking { phone.versionsRepo().renameSnapshot(snap, "Colour") }
        phone.sync()
        tablet.sync()
        assertThat(runBlocking { tablet.versionsRepo().snapshots(tablet.version(t)) }.map { it.name })
            .containsExactly("Colour")

        phone.now += 1_000
        runBlocking { phone.versionsRepo().deleteSnapshot(snap) }
        phone.sync()
        tablet.sync()
        assertThat(runBlocking { tablet.versionsRepo().snapshots(tablet.version(t)) }).isEmpty()
        assertThat(tablet.pending()).isEqualTo(0)
    }

    @Test
    fun `export presets sync one by one, deletes included`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val web = runBlocking { phone.exportPresets().create("Web small", """{"quality":80}""") }
        runBlocking { phone.exportPresets().create("Prints", """{"quality":100}""") }
        phone.sync()
        tablet.sync()
        val onTablet = runBlocking { tablet.exportPresets().all() }
        assertThat(onTablet.map { it.name }).containsExactly("Web small", "Prints").inOrder()
        assertThat(onTablet.first().optionsJson).contains("80")

        // The tablet adds one while the phone renames and deletes: both changes land.
        tablet.now += 1_000
        runBlocking { tablet.exportPresets().create("Phone screen", """{"quality":70}""") }
        phone.now += 1_000
        runBlocking {
            phone.exportPresets().update(web, name = "Web")
            phone.exportPresets().delete(phone.exportPresets().all().single { it.name == "Prints" }.uuid)
        }
        tablet.sync()
        phone.sync()
        tablet.sync()
        for (device in listOf(phone, tablet)) {
            assertThat(runBlocking { device.exportPresets().all() }.map { it.name })
                .containsExactly("Web", "Phone screen")
        }
        assertThat(phone.pending()).isEqualTo(0)
        assertThat(tablet.pending()).isEqualTo(0)
    }
}
