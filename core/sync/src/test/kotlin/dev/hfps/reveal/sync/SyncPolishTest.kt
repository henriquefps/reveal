package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.HistoryOp
import dev.hfps.reveal.data.model.MetadataState
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Optics
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.StepLabel
import dev.hfps.reveal.sync.engine.SyncEngine
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * FIX/W (0.5.4, ADR 0018 amendment), two simulated devices against [FakeSyncServer]: Server photos keep their
 * orientation, the automatic lens profile default never makes a conflict or a second version, and the conflict
 * chooser keeps either side on every device.
 */
@RunWith(AndroidJUnit4::class)
class SyncPolishTest {
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

    private fun bytes(seed: Int, size: Int = 2048) = Random(seed).nextBytes(size)

    private fun photo(device: TestDevice, id: Long): PhotoEntity = runBlocking {
        checkNotNull(device.db.photoDao().get(id))
    }

    /** What the indexer writes after reading the header: a portrait shot of a 6000 x 4000 sensor (EXIF 6). */
    private fun readHeader(device: TestDevice, id: Long, orientation: Int) = runBlocking {
        device.db.photoDao().setMetadata(
            id = id,
            captureTime = device.now,
            offset = null,
            width = SENSOR_W,
            height = SENSOR_H,
            orientation = orientation,
            state = MetadataState.READ,
        )
    }

    // ------------------------------------------------------------------ 1. orientation

    @Test
    fun `a rotated photo reaches the other device's Server source with its orientation`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(1)
        val id = phone.addPhoto("DSC07834", raw)
        readHeader(phone, id, orientation = 6)
        phone.sync()
        tablet.sync()

        val onTablet = checkNotNull(tablet.photoByFingerprint(FileFingerprint.of(raw)))
        assertThat(onTablet.width).isEqualTo(SENSOR_W)
        assertThat(onTablet.height).isEqualTo(SENSOR_H)
        assertThat(onTablet.orientation).isEqualTo(6)
        assertThat(onTablet.metadataState).isEqualTo(MetadataState.READ)
    }

    @Test
    fun `a record without orientation is read again by range, and a later record fixes it`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(2)
        val id = phone.addPhoto("DSC07835", raw)
        // Pushed before its header was read: no orientation in the record (as 0.5.3 sent every record).
        phone.sync()
        tablet.sync()
        var onTablet = checkNotNull(tablet.photoByFingerprint(FileFingerprint.of(raw)))
        assertThat(onTablet.metadataState).isEqualTo(MetadataState.PENDING)

        phone.now += 1_000
        readHeader(phone, id, orientation = 8)
        phone.sync()
        tablet.sync()
        onTablet = checkNotNull(tablet.photoByFingerprint(FileFingerprint.of(raw)))
        assertThat(onTablet.orientation).isEqualTo(8)
        assertThat(onTablet.width).isEqualTo(SENSOR_W)
    }

    @Test
    fun `photos pushed by older versions go up once more with their orientation`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(3)
        val id = phone.addPhoto("DSC07836", raw)
        readHeader(phone, id, orientation = 1)
        phone.sync()
        tablet.sync()
        // 0.5.3: the photo's orientation was known locally but never sent; the one-off has not run yet.
        phone.db.openHelper.writableDatabase.execSQL("UPDATE photos SET orientation = 6 WHERE id = $id")
        runBlocking { phone.db.metaDao().delete(SyncEngine.KEY_ORIENTATION) }

        phone.now += 1_000
        phone.sync()
        tablet.sync()
        assertThat(checkNotNull(tablet.photoByFingerprint(FileFingerprint.of(raw))).orientation).isEqualTo(6)
        // Once only.
        assertThat(runBlocking { phone.db.metaDao().get(SyncEngine.KEY_ORIENTATION) })
            .isEqualTo(SyncEngine.ORIENTATION_VERSION)
        assertThat(phone.pending()).isEqualTo(0)
    }

    // ------------------------------------------------------------------ 2. the lens profile default

    /** What the editor writes when it opens a new photo with a lens profile (ADR 0015 "New photos"). */
    private fun openInEditor(device: TestDevice, versionId: Long) = runBlocking {
        val before = device.edits.recipe(versionId)
        val after = before.copy(optics = Optics.forNewPhoto(hasProfile = true, profileHasCa = true, isRaw = true))
        val label = StepLabel(RecipeDiff.between(before, after), HistoryAction.LENS_PROFILE)
        device.edits.apply(listOf(HistoryOp.Commit(versionId, after, label, onlyIfEmpty = true)))
    }

    @Test
    fun `an edit on one device and the photo opened on the other makes no conflict and no second version`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(4)
        val p = phone.addPhoto("DSC4", raw)
        val t = tablet.addPhoto("DSC4", raw)
        phone.sync()
        tablet.sync()
        // The phone opens and edits the photo; the tablet opens it before the edit arrives.
        openInEditor(phone, phone.version(p))
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.7f)) }
        tablet.now += 1_000
        openInEditor(tablet, tablet.version(t))
        phone.sync()
        tablet.sync()
        phone.sync()

        assertThat(server.copies()).isEmpty()
        for ((device, photo) in listOf(phone to p, tablet to t)) {
            val versions = device.versions(photo)
            assertThat(versions).hasSize(1)
            val recipe = device.recipe(versions.single().id)
            assertThat(recipe.light.exposureEv).isEqualTo(0.7f)
            assertThat(recipe.optics.lensCorrections).isTrue()
        }
        assertThat(tablet.pending()).isEqualTo(0)
    }

    @Test
    fun `a photo only opened on both devices pushes nothing, and a later edit is adopted`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(5)
        val p = phone.addPhoto("DSC5", raw)
        val t = tablet.addPhoto("DSC5", raw)
        openInEditor(phone, phone.version(p))
        openInEditor(tablet, tablet.version(t))
        phone.sync()
        tablet.sync()
        phone.sync()
        assertThat(server.versionRecords()).isEmpty()
        assertThat(phone.versions(p)).hasSize(1)
        assertThat(tablet.versions(t)).hasSize(1)

        tablet.now += 1_000
        tablet.edit(tablet.version(t)) { it.copy(light = it.light.copy(contrast = 20f)) }
        tablet.sync()
        phone.sync()
        assertThat(phone.versions(p)).hasSize(1)
        assertThat(phone.recipe(phone.version(p)).light.contrast).isEqualTo(20f)
        assertThat(server.copies()).isEmpty()
    }

    @Test
    fun `the default lands only on an empty history (an edit synced meanwhile wins)`() {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(6)
        val p = phone.addPhoto("DSC6", raw)
        val t = tablet.addPhoto("DSC6", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = -0.3f)) }
        phone.sync()
        // The tablet's editor read an empty history, then sync applied the phone's edit before the default was written.
        tablet.sync()
        openInEditor(tablet, tablet.version(t))
        val history = runBlocking { tablet.edits.history(tablet.version(t)) }
        assertThat(history.steps).hasSize(1)
        assertThat(tablet.recipe(tablet.version(t)).light.exposureEv).isEqualTo(-0.3f)
        assertThat(tablet.pending()).isEqualTo(0)
    }

    // ------------------------------------------------------------------ 3. the conflict chooser

    /** Both devices edit the same version offline; the tablet's later change wins, the phone's is the copy. */
    private fun conflict(seed: Int): Triple<TestDevice, TestDevice, Pair<Long, Long>> {
        val phone = device("Phone")
        val tablet = device("Tablet")
        val raw = bytes(seed)
        val p = phone.addPhoto("DSC$seed", raw)
        val t = tablet.addPhoto("DSC$seed", raw)
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = 0.3f)) }
        phone.sync()
        tablet.sync()
        phone.now += 1_000
        phone.edit(phone.version(p)) { it.copy(light = it.light.copy(exposureEv = -1f)) }
        // The sync triggers stamp changes with the wall clock: the tablet's edit must be later for it to win.
        Thread.sleep(CLOCK_STEP_MS)
        tablet.now += 2_000
        tablet.edit(tablet.version(t)) { it.copy(light = it.light.copy(exposureEv = 1.5f)) }
        tablet.sync()
        phone.sync()
        tablet.sync()
        assertThat(server.copies()).hasSize(1)
        return Triple(phone, tablet, p to t)
    }

    @Test
    fun `the chooser shows both sides with their device and time`() {
        val (phone, _, ids) = conflict(7)
        val copy = phone.versions(ids.first).single { it.copyOf != null }
        val main = phone.versions(ids.first).single { it.copyOf == null }
        val pair = checkNotNull(runBlocking { phone.store.conflict(copy.id) })
        assertThat(pair.main.versionId).isEqualTo(main.id)
        assertThat(pair.copy.versionId).isEqualTo(copy.id)
        assertThat(pair.main.device).isEqualTo("Tablet")
        assertThat(pair.copy.device).isEqualTo("Phone")
        assertThat(pair.main.editedAt).isNotNull()
        assertThat(pair.main.editTime).isNotNull()
        // From the main version too.
        assertThat(runBlocking { phone.store.conflict(main.id) }).isEqualTo(pair)
    }

    @Test
    fun `keeping the main version deletes the copy on every device`() {
        val (phone, tablet, ids) = conflict(8)
        val copy = phone.versions(ids.first).single { it.copyOf != null }
        val pair = checkNotNull(runBlocking { phone.store.conflict(copy.id) })
        phone.now += 5_000
        runBlocking { phone.store.keep(pair, keepCopy = false) }
        phone.sync()
        tablet.sync()
        for ((device, photo) in listOf(phone to ids.first, tablet to ids.second)) {
            assertThat(device.versions(photo).map { it.copyOf }).containsExactly(null)
            assertThat(device.recipe(device.version(photo)).light.exposureEv).isEqualTo(1.5f)
        }
    }

    @Test
    fun `keeping the copy makes it the main version on every device`() {
        val (phone, tablet, ids) = conflict(9)
        val copy = tablet.versions(ids.second).single { it.copyOf != null }
        val pair = checkNotNull(runBlocking { tablet.store.conflict(copy.id) })
        val kept = tablet.recipe(copy.id).light.exposureEv
        assertThat(kept).isEqualTo(-1f)
        tablet.now += 5_000
        runBlocking { tablet.store.keep(pair, keepCopy = true) }
        tablet.sync()
        phone.sync()
        tablet.sync()
        assertThat(server.copies()).hasSize(1) // the server's history of copies; none live:
        for ((device, photo) in listOf(phone to ids.first, tablet to ids.second)) {
            val versions = device.versions(photo)
            assertThat(versions.map { it.copyOf }).containsExactly(null)
            assertThat(device.recipe(versions.single().id).light.exposureEv).isEqualTo(kept)
        }
        // The kept edit's history came along (undo works), and it is now this device's change.
        val main = tablet.versions(ids.second).single()
        assertThat(runBlocking { tablet.edits.history(main.id) }.steps).isNotEmpty()
        assertThat(main.editDevice).isEqualTo(EditRepository.THIS_DEVICE)
        assertThat(tablet.pending()).isEqualTo(0)
        assertThat(phone.pending()).isEqualTo(0)
    }

    @Test
    fun `a photo without a conflict has nothing to choose`() {
        val phone = device("Phone")
        val p = phone.addPhoto("DSC10", bytes(10))
        assertThat(runBlocking { phone.store.conflict(phone.version(p)) }).isNull()
    }

    private companion object {
        const val SENSOR_W = 6000
        const val SENSOR_H = 4000
        const val CLOCK_STEP_MS = 20L
    }
}
