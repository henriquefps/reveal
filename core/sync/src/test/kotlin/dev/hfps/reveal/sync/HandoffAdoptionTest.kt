package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.sync.engine.ServerHeaders
import dev.hfps.reveal.sync.engine.SyncStore
import kotlin.random.Random
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Hand-offs the server holds become Server photos (ADR 0018 "W3/C"), in place, and the Server photos' headers come
 * from the server's items without reading a file.
 */
@RunWith(AndroidJUnit4::class)
class HandoffAdoptionTest {
    private val server = FakeSyncServer()
    private lateinit var url: String
    private lateinit var phone: TestDevice

    @Before
    fun setUp() {
        url = server.start()
        server.hoard(edits = true)
        phone = TestDevice("Phone", url, server.addDevice("Phone"), server.serverId)
    }

    @After
    fun tearDown() {
        phone.close()
        server.shutdown()
    }

    @Test
    fun `a hand-off the server holds becomes a Server photo with its identity, edits, culling and hand-off time`() {
        val jpeg = Random(1).nextBytes(4096)
        val print = FileFingerprint.of(jpeg)
        val photo = phone.addHandoffPhoto("IMG1", jpeg)
        val version = phone.version(photo)
        phone.edit(version) { it.copy(light = it.light.copy(exposureEv = 0.3f)) }
        phone.rate(photo, 4)
        phone.flag(photo, PickFlag.PICK)
        phone.sync()
        val before = phone.photo(photo)
        val pending = phone.pending()
        // Nothing to adopt while the server does not hold the file.
        assertThat(runBlocking { phone.store.adoptHandoffs() }).isEqualTo(0)

        server.addNasFile("nas", "NAS", "2026/IMG1.JPG", jpeg, print)
        phone.sync()
        assertThat(runBlocking { phone.store.adoptHandoffs() }).isEqualTo(1)

        val after = phone.photo(photo)
        val source = runBlocking { phone.db.sourceDao().get(after.sourceId)!! }
        assertThat(source.kind).isEqualTo(SourceKind.SERVER)
        assertThat(after.uuid).isEqualTo(before.uuid)
        assertThat(after.handoffAt).isEqualTo(before.handoffAt)
        assertThat(after.rating).isEqualTo(4)
        assertThat(after.flag).isEqualTo(PickFlag.PICK)
        assertThat(phone.version(photo)).isEqualTo(version)
        assertThat(phone.recipe(version).light.exposureEv).isEqualTo(0.3f)
        val file = runBlocking { phone.db.photoDao().files(photo).single() }
        assertThat(file.documentId).isEqualTo(ServerTree.documentId(print, "IMG1.JPG"))
        assertThat(file.relativePath).startsWith(SyncStore.ADOPTED_PREFIX)
        assertThat(file.onServer).isTrue()
        // Quiet: nothing new for other devices.
        assertThat(phone.pending()).isEqualTo(pending)
        // Still "Opened from other apps".
        val fromApps = runBlocking { phone.db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS)) }
        assertThat(fromApps.map { it.id }).contains(photo)
        assertThat(runBlocking { phone.store.adoptHandoffs() }).isEqualTo(0)

        // The NAS feed applied again (a clean-up re-pull) keeps one photo.
        phone.sync()
        assertThat(runBlocking { phone.db.photoDao().filesByFingerprint(listOf(print)) }).hasSize(1)
    }

    @Test
    fun `the Server photos' headers come from the items`() {
        val raw = Random(2).nextBytes(4096)
        val print = FileFingerprint.of(raw)
        server.addNasFile("nas", "NAS", "2026/DSC1.ARW", raw, print)
        val item = server.culling.addItem(listOf(print))
        server.edits.meta[item] = buildJsonObject {
            put("sortTime", 1_714_557_600_500L)
            put(
                "capture",
                buildJsonObject {
                    put("local", "2024-05-01T10:00:00.500")
                    put("offset", "-03:00")
                },
            )
            put("width", 6000)
            put("height", 4000)
            put("orientation", 6)
        }
        phone.sync()
        val photo = checkNotNull(phone.photoByFingerprint(print))
        val source = runBlocking { phone.db.syncDao().serverSource()!! }
        assertThat(runBlocking { ServerHeaders.read(phone.db, phone.api, source.id, 100) }).isEqualTo(1)
        val read = phone.photo(photo.id)
        assertThat(read.captureTime).isEqualTo(1_714_557_600_500L)
        assertThat(read.captureOffsetMinutes).isEqualTo(-180)
        assertThat(read.width).isEqualTo(6000)
        assertThat(read.orientation).isEqualTo(6)
        assertThat(read.metadataState).isEqualTo(dev.hfps.reveal.data.model.MetadataState.READ)
        // Untracked: the file's metadata is no user's change.
        assertThat(runBlocking { phone.db.syncDao().record(SyncRecordEntity.KIND_PHOTO, read.uuid) }?.dirty ?: false)
            .isFalse()
    }
}
