package dev.hfps.reveal.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.db.SyncRecordEntity
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
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

        // The sender granted a sidecar.
        phone.db.openHelper.writableDatabase.execSQL(
            "UPDATE photos SET xmp_document_id = 'content://dev.hfps.hoard.stream/h/1/IMG1.xmp' WHERE id = $photo",
        )
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
        // No sidecar for a Server photo (ADR 0011 W3/H): the sender's URI is never opened again.
        assertThat(after.xmpDocumentId).isNull()
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

    private fun handoffsOnServer(vararg stems: String, same: ByteArray? = null): List<Long> {
        val bytes = same ?: Random(stems.hashCode()).nextBytes(2048)
        val ids = stems.mapIndexed { i, stem ->
            phone.addHandoffPhoto(stem, bytes, uri = "content://hoard.$i/h/$stem.JPG")
        }
        server.addNasFile("nas", "NAS", "2026/${stems.first()}.JPG", bytes, FileFingerprint.of(bytes))
        phone.sync()
        return ids
    }

    @Test
    fun `two hand-offs of one file and name are both adopted under unique paths, none breaks the sync`() {
        val ids = handoffsOnServer("DUP", "DUP")
        val moved = runBlocking { phone.store.adoptNext() }
        assertThat(moved.failed).isEqualTo(0)
        assertThat(moved.moved).isEqualTo(2)
        val paths = ids.map { runBlocking { phone.db.photoDao().files(it).single().relativePath } }
        assertThat(paths.toSet()).hasSize(2)
        // Nothing left; the sync's own pass is clean.
        assertThat(runBlocking { phone.store.adoptNext() }).isEqualTo(SyncStore.Adopted(0, 0))
    }

    @Test
    fun `a photo whose adoption fails is marked and skipped, the others still move`() {
        val ids = handoffsOnServer("A1", "A2", "A3", same = null)
        // Distinct files so each photo is its own: give them their own prints.
        val bad = ids[1]
        phone.db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER no_adopt BEFORE UPDATE OF source_id ON photos WHEN NEW.id = $bad " +
                "BEGIN SELECT RAISE(ABORT, 'adoption refused'); END",
        )
        val first = runBlocking { phone.store.adoptNext() }
        assertThat(first.failed).isEqualTo(1)
        assertThat(first.moved).isEqualTo(2)
        // The failed one is skipped now (no endless retry of the same batch), the rest is done.
        assertThat(runBlocking { phone.store.adoptNext() }).isEqualTo(SyncStore.Adopted(0, 0))
        val source = runBlocking { phone.db.sourceDao().get(phone.photo(bad).sourceId)!! }
        assertThat(source.kind).isEqualTo(SourceKind.HANDOFF)
        // Once the clock passes the retry time it is tried again.
        phone.db.openHelper.writableDatabase.execSQL("DROP TRIGGER no_adopt")
        phone.now += SyncStore.ADOPT_RETRY_MS + 1
        assertThat(runBlocking { phone.store.adoptNext() }.moved).isEqualTo(1)
    }

    @Test
    fun `only the files the server holds move, a RAW+JPEG hand-off keeps its JPEG as it was`() {
        val raw = Random(7).nextBytes(4096)
        val jpeg = Random(8).nextBytes(2048)
        val photo = phone.addHandoffPhoto("PAIR", jpeg, uri = "content://hoard.1/h/PAIR.JPG")
        runBlocking {
            phone.db.photoDao().insertFiles(
                listOf(
                    dev.hfps.reveal.data.db.PhotoFileEntity(
                        photoId = photo, sourceId = phone.photo(photo).sourceId,
                        relativePath = "content://hoard.1/h/PAIR.ARW", documentId = "content://hoard.1/h/PAIR.ARW",
                        name = "PAIR.ARW", size = raw.size.toLong(), modified = 1,
                        role = dev.hfps.reveal.data.model.FileRole.RAW, fingerprint = FileFingerprint.of(raw),
                    ),
                ),
            )
        }
        server.addNasFile("nas", "NAS", "2026/PAIR.ARW", raw, FileFingerprint.of(raw))
        phone.sync()
        assertThat(runBlocking { phone.store.adoptNext() }.moved).isEqualTo(1)
        val files = runBlocking { phone.db.photoDao().files(photo) }.associateBy { it.name }
        assertThat(files.getValue("PAIR.ARW").documentId)
            .isEqualTo(ServerTree.documentId(FileFingerprint.of(raw), "PAIR.ARW"))
        // The JPEG the server cannot serve is still the sender's, with its URI.
        assertThat(files.getValue("PAIR.JPG").documentId).isEqualTo("content://hoard.1/h/PAIR.JPG")
        assertThat(files.getValue("PAIR.JPG").relativePath).isEqualTo("content://hoard.1/h/PAIR.JPG")
        assertThat(files).hasSize(2)
    }

    @Test
    fun `a photo record deleted on the server never deletes an adopted hand-off, and the NAS feed cannot empty it`() {
        val (photo) = handoffsOnServer("KEEP")
        phone.rate(photo, 3)
        phone.sync()
        assertThat(runBlocking { phone.store.adoptNext() }.moved).isEqualTo(1)
        val uuid = phone.photo(photo).uuid
        val version = phone.version(photo)

        // Another device (or the web gallery) deletes the photo record.
        server.record("photo", uuid)!!.deleted = true
        server.bump("photo", uuid)
        phone.sync()
        assertThat(phone.photo(photo).rating).isEqualTo(3)
        assertThat(phone.version(photo)).isEqualTo(version)

        // The NAS renames/removes the file: the adopted file row stays, so does the photo (and its culling).
        server.removeNasFile("nas", "2026/KEEP.JPG")
        phone.sync()
        assertThat(runBlocking { phone.db.photoDao().files(photo) }).hasSize(1)
        assertThat(phone.photo(photo).rating).isEqualTo(3)
        // And an unedited, rated adopted photo survives the empty sweep even with no file at all.
        runBlocking { phone.db.photoDao().deleteFiles(phone.db.photoDao().files(photo).map { it.id }) }
        runBlocking { phone.store.applyNas(emptyList()) }
        assertThat(runBlocking { phone.db.photoDao().get(photo) }).isNotNull()
    }

    @Test
    fun `a missed item lookup leaves the header pending and asks again after the backoff`() {
        val raw = Random(3).nextBytes(4096)
        val print = FileFingerprint.of(raw)
        server.addNasFile("nas", "NAS", "2026/MISS.ARW", raw, print)
        phone.sync()
        val photo = checkNotNull(phone.photoByFingerprint(print)).id
        val source = runBlocking { phone.db.syncDao().serverSource()!! }
        // No item on the server for it yet.
        val t0 = 1_000_000L
        assertThat(runBlocking { ServerHeaders.read(phone.db, phone.api, source.id, 100, now = t0) }).isEqualTo(0)
        assertThat(phone.photo(photo).metadataState).isEqualTo(dev.hfps.reveal.data.model.MetadataState.PENDING)
        // Within the backoff it is not asked again (it must not starve the others), after it it is.
        assertThat(
            runBlocking {
                phone.db.syncDao().serverHeaderCandidates(source.id, 10, t0 + 1_000 - LibraryIndexer.MISS_BACKOFF_MS)
            },
        ).isEmpty()
        assertThat(
            runBlocking {
                phone.db.syncDao().serverHeaderCandidates(source.id, 10, t0 + LibraryIndexer.MISS_BACKOFF_MS)
            },
        ).containsExactly(photo)
        // The item appears: the header is read.
        val item = server.culling.addItem(listOf(print))
        server.edits.meta[item] = buildJsonObject {
            put("sortTime", 1_714_557_600_500L)
            put("width", 6000)
            put("height", 4000)
            put("orientation", 6)
        }
        val later = t0 + LibraryIndexer.MISS_BACKOFF_MS + 1
        assertThat(runBlocking { ServerHeaders.read(phone.db, phone.api, source.id, 100, now = later) }).isEqualTo(1)
        assertThat(phone.photo(photo).orientation).isEqualTo(6)
    }
}
