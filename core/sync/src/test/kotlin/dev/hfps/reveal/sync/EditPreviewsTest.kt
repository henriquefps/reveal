package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.edit.CopySource
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.sync.engine.EditPreviews
import dev.hfps.reveal.sync.engine.EditRenderer
import dev.hfps.reveal.sync.engine.EditReport
import dev.hfps.reveal.sync.engine.RenderedEdit
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Edited previews (Hoard ADR 0020 "Client rules (Reveal)", Reveal ADR 0027) against the fake server. */
@RunWith(AndroidJUnit4::class)
class EditPreviewsTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private val devices = ArrayList<TestDevice>()
    private val renders = ArrayList<Long>()
    private val renderer = EditRenderer { _, versionId, maxEdge, _ ->
        renders += versionId
        RenderedEdit(ByteArray(64) { versionId.toByte() }, maxEdge, maxEdge * 2 / 3)
    }

    @Before
    fun setUp() {
        url = server.start()
        server.hoard(edits = true)
    }

    @After
    fun tearDown() {
        devices.forEach(TestDevice::close)
        server.shutdown()
    }

    private fun device(name: String) =
        TestDevice(name, url, server.addDevice(name), server.serverId).also { devices += it }

    private fun TestDevice.previews() = EditPreviews(db, renderer) { now }

    /** Settles the edits (a minute without changes), syncs the records, runs one pass. */
    private fun TestDevice.pass(): EditReport {
        sync()
        now += EditPreviews.SETTLE_MS + 1_000
        return runBlocking { previews().run(api, maxRenders = 10) }
    }

    private fun TestDevice.exposure(versionId: Long, ev: Float) =
        edit(versionId) { it.copy(light = it.light.copy(exposureEv = ev)) }

    private fun TestDevice.uuid(versionId: Long) = versions(versionOwner(versionId)).single { it.id == versionId }.uuid

    private fun TestDevice.versionOwner(versionId: Long) =
        runBlocking { checkNotNull(db.photoDao().photoIdOfVersion(versionId)) }

    private fun TestDevice.copies() = VersionRepository(db, edits) { now }

    private fun shot(device: TestDevice, seed: Int): Pair<Long, String> {
        val raw = Random(seed).nextBytes(4096)
        val itemId = server.culling.addItem(listOf(FileFingerprint.of(raw)))
        return device.addPhoto("DSC$seed", raw) to itemId
    }

    @Test
    fun `an edited version goes up once, and an unchanged edit costs nothing`() {
        val phone = device("Phone")
        val (photo, itemId) = shot(phone, 1)
        val version = phone.version(photo)
        phone.exposure(version, 0.7f)

        assertThat(phone.pass().uploaded).isEqualTo(1)

        val edit = server.edits.edits.getValue(phone.uuid(version))
        assertThat(edit.itemId).isEqualTo(itemId)
        assertThat(edit.primary).isTrue()
        assertThat(edit.virtualCopy).isFalse()
        assertThat(edit.label).isEmpty()
        assertThat(edit.digest).startsWith("r")
        val puts = server.edits.puts.size
        assertThat(phone.pass()).isEqualTo(EditReport())
        assertThat(server.edits.puts).hasSize(puts)
        assertThat(renders).hasSize(1)

        // A new edit: a new digest, the image again.
        phone.exposure(version, 1.2f)
        assertThat(phone.pass().uploaded).isEqualTo(1)
        assertThat(server.edits.edits.getValue(phone.uuid(version)).digest).isNotEqualTo(edit.digest)
    }

    @Test
    fun `nothing goes up while the edit is still changing`() {
        val phone = device("Phone")
        val (photo, _) = shot(phone, 2)
        phone.exposure(phone.version(photo), 0.4f)
        phone.sync()
        assertThat(runBlocking { phone.previews().run(phone.api, 10) }).isEqualTo(EditReport())
        assertThat(server.edits.puts).isEmpty()
    }

    @Test
    fun `virtual copies go up as copies, a rename is a header-only change, the primary follows the grid`() {
        val phone = device("Phone")
        val (photo, itemId) = shot(phone, 3)
        val main = phone.version(photo)
        val copy = checkNotNull(runBlocking { phone.copies().createCopy(main, CopySource.ORIGINAL) { "Copy $it" } })
        phone.exposure(copy, -0.5f)

        assertThat(phone.pass().uploaded).isEqualTo(1)
        // The main version is unedited: the copy is the item's primary.
        with(server.edits.edits.getValue(phone.uuid(copy))) {
            assertThat(virtualCopy).isTrue()
            assertThat(label).isEqualTo("Copy 1")
            assertThat(primary).isTrue()
        }

        runBlocking { phone.copies().rename(copy, "Cópia azul") }
        phone.exposure(main, 0.3f)
        val report = phone.pass()
        assertThat(report.uploaded).isEqualTo(1)
        assertThat(report.updated).isEqualTo(1)
        // The rename went without an image; the main version is the primary now.
        assertThat(server.edits.puts.last { it.first == phone.uuid(copy) }.second).isFalse()
        with(server.edits.edits.getValue(phone.uuid(copy))) {
            assertThat(label).isEqualTo("Cópia azul")
            assertThat(primary).isFalse()
        }
        assertThat(server.edits.edits.getValue(phone.uuid(main)).primary).isTrue()
        assertThat(server.edits.of(itemId)).hasSize(2)
    }

    @Test
    fun `a reset or deleted version is deleted on the server, a conflict copy never goes up`() {
        val phone = device("Phone")
        val (photo, itemId) = shot(phone, 4)
        val main = phone.version(photo)
        val copy = checkNotNull(runBlocking { phone.copies().createCopy(main, CopySource.ORIGINAL) { "Copy $it" } })
        phone.exposure(main, 0.3f)
        phone.exposure(copy, 0.9f)
        phone.pass()
        assertThat(server.edits.of(itemId)).hasSize(2)
        val copyUuid = phone.uuid(copy)
        val mainUuid = phone.uuid(main)

        runBlocking { phone.copies().delete(copy) }
        runBlocking {
            val before = phone.edits.recipe(main)
            phone.edits.commit(main, Recipe(), RecipeDiff.between(before, Recipe()))
        }
        val report = phone.pass()

        assertThat(report.deleted).isEqualTo(2)
        assertThat(server.edits.deletes).containsExactly(copyUuid, mainUuid)
        assertThat(server.edits.of(itemId)).isEmpty()
    }

    @Test
    fun `an older edit than another device's is not sent again until it changes`() {
        val phone = device("Phone")
        val (photo, _) = shot(phone, 5)
        val version = phone.version(photo)
        phone.exposure(version, 0.2f)
        server.edits.failNext = Triple(409, "conflict", "older")

        assertThat(phone.pass().refused).isEqualTo(1)
        val puts = server.edits.puts.size
        phone.pass()
        assertThat(server.edits.puts).hasSize(puts)

        phone.exposure(version, 0.6f)
        assertThat(phone.pass().uploaded).isEqualTo(1)
    }

    @Test
    fun `an edit another device uploaded is not rendered again`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = Random(6).nextBytes(4096)
        server.culling.addItem(listOf(FileFingerprint.of(raw)))
        val onPhone = phone.addPhoto("DSC6", raw)
        val onTablet = tablet.addPhoto("DSC6", raw)
        phone.sync()
        tablet.sync()
        phone.exposure(phone.version(onPhone), 0.8f)
        phone.pass()
        val before = renders.size

        tablet.sync()
        tablet.pass()

        assertThat(tablet.recipe(tablet.version(onTablet)).light.exposureEv).isEqualTo(0.8f)
        assertThat(renders).hasSize(before)
    }

    @Test
    fun `a photo the server does not index, or an older server, sends nothing`() {
        val phone = device("Phone")
        val photo = phone.addPhoto("PHONE7", Random(7).nextBytes(4096))
        phone.exposure(phone.version(photo), 0.5f)
        assertThat(phone.pass()).isEqualTo(EditReport())

        server.hoard(edits = false)
        val (other, _) = shot(phone, 8)
        phone.exposure(phone.version(other), 0.5f)
        assertThat(phone.pass()).isEqualTo(EditReport())
        assertThat(server.edits.puts).isEmpty()
    }

    @Test
    fun `the digest follows the recipe and the pipeline`() {
        val a = """{"v":1,"light":{"exposure":0.7}}"""
        assertThat(EditPreviews.digest(a)).isEqualTo(EditPreviews.digest(a))
        assertThat(EditPreviews.digest(a)).isNotEqualTo(EditPreviews.digest("""{"v":1,"light":{"exposure":0.8}}"""))
        assertThat(EditPreviews.digest(a)).matches("r[0-9]+\\.[0-9]+:[0-9a-f]{64}")
        assertThat(EditPreviews.digest(a).length).isAtMost(128)
    }
}
