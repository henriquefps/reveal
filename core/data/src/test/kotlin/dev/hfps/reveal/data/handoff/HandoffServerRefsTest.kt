package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.CullingFileEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Server references (ADR 0011 "Amendment W3/H"): every way an item resolves, and what the batch reports. */
@RunWith(AndroidJUnit4::class)
class HandoffServerRefsTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** A server the test scripts: items by id, lookups by fingerprint, what was asked, and whether it answers. */
    private class FakeServer(var pairing: ServerPairing? = ServerPairing(SERVER, "Home NAS", "own-device")) :
        ServerItems {
        val items = HashMap<String, ServerItemResult>()
        val lookups = HashMap<String, String>()
        val itemCalls = ArrayList<String>()
        val lookupCalls = ArrayList<List<String>>()
        var pulls = 0
        var up = true

        override fun pairing() = pairing

        override suspend fun item(itemId: String): ServerItemResult {
            itemCalls += itemId
            return if (!up) ServerItemResult.Unreachable else items[itemId] ?: ServerItemResult.Missing
        }

        override suspend fun lookup(fingerprints: List<String>): Map<String, String>? {
            lookupCalls += fingerprints
            return if (up) lookups.filterKeys { it in fingerprints } else null
        }

        override suspend fun pullCulling() {
            pulls++
        }
    }

    private lateinit var db: RevealDatabase
    private lateinit var repo: HandoffRepository
    private val server = FakeServer()
    private val tree = FileUriTree()
    private var now = 10_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        repo = HandoffRepository(context.contentResolver, db, LibraryIndexer(db), tree, server = server) { now }
    }

    @After
    fun tearDown() = db.close()

    private fun id(n: Int) = "00000000-0000-8000-8000-%012d".format(n)

    private fun fp(n: Int) = "v1:" + "%064x".format(n)

    private fun item(
        n: Int,
        name: String = "DSC%05d.ARW".format(n),
        jpeg: Boolean = false,
        source: String = "nas",
        folder: String = "Trip/Day1",
    ) = ServerItemInfo(
        itemId = id(n),
        mainFingerprint = fp(n),
        files = buildList {
            add(ServerFileInfo(fp(n), if (name.endsWith("ARW")) "raw" else "rendered", name, 25_000_000))
            if (jpeg) add(ServerFileInfo(fp(n + 1000), "rendered", name.substringBefore('.') + ".JPG", 8_000_000))
        },
        originalSource = source,
        folder = folder,
        sortTime = 1_700_000_000_000 + n,
        width = 6000,
        height = 4000,
        orientation = 6,
    )

    private fun serve(vararg infos: ServerItemInfo) = infos.forEach {
        server.items[it.itemId] = ServerItemResult.Found(it)
    }

    private fun refs(vararg items: ServerItemRef, initial: String? = null, serverId: String = SERVER) =
        ServerRefs(serverId, items.toList(), initial)

    private fun request(refs: ServerRefs, title: String? = null, uris: List<Uri> = emptyList()) =
        HandoffRequest(uris = uris, server = refs, title = title)

    private suspend fun rows() = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))

    @Test
    fun `an unknown item becomes a Server photo that opens, joins the batch and pulls culling`() = runTest {
        serve(item(1, jpeg = true))
        val outcome = repo.receive(request(refs(ServerItemRef(id(1), fp(1))), title = "Trip"))
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.closeOnBack).isTrue()
        val photo = rows().single()
        assertThat(photo.name).isEqualTo("DSC00001.ARW")
        assertThat(photo.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(photo.handoffTitle).isEqualTo("Trip")
        assertThat(photo.handoffAt).isEqualTo(10_000L)
        assertThat(db.sourceDao().handoff()).isNull() // nothing was filed under the hand-off source
        val source = db.syncDao().serverSource()!!
        assertThat(source.kind).isEqualTo(SourceKind.SERVER)
        val files = db.photoDao().files(photo.id)
        assertThat(files.map { it.fingerprint }).containsExactly(fp(1), fp(1001))
        assertThat(files.all { it.onServer && it.sourceId == source.id }).isTrue()
        assertThat(files.first { it.fingerprint == fp(1) }.documentId).isEqualTo("${fp(1)}/DSC00001.ARW")
        val entity = db.photoDao().get(photo.id)!!
        assertThat(entity.orientation).isEqualTo(6)
        assertThat(entity.folder).isEqualTo("Trip/Day1")
        val cloud = db.syncDao().cloud(photo.id)!!
        assertThat(cloud.originalOnServer).isTrue()
        assertThat(cloud.originalSource).isEqualTo("nas")
        assertThat(server.pulls).isEqualTo(1)
        // Nothing was read through a sender: the only calls are to the server.
        assertThat(server.itemCalls).containsExactly(id(1))
    }

    @Test
    fun `a photo already in the library under a member fingerprint is that photo, without asking the server`() = runTest {
        val dir = temp.newFolder("local")
        val bytes = SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + "local".toByteArray()
        val print = FileFingerprint.of(bytes)
        val uri = Uri.fromFile(File(dir, "IMG_1.JPG").apply { writeBytes(bytes) })
        repo.receive(HandoffRequest(listOf(uri)))
        val before = rows().single()
        now = 20_000
        val outcome = repo.receive(
            request(refs(ServerItemRef(id(5), fingerprint = "v1:other", fingerprints = listOf("v1:other", print)))),
        )
        outcome as HandoffOutcome.OpenEditor
        assertThat(server.itemCalls).isEmpty()
        val after = rows()
        assertThat(after).hasSize(1)
        assertThat(after.single().id).isEqualTo(before.id)
        assertThat(after.single().handoffAt).isEqualTo(20_000)
        assertThat(db.syncDao().serverSource()).isNull()
        assertThat(server.pulls).isEqualTo(1)
    }

    @Test
    fun `the item id wins over a fingerprint that is not one of its members`() = runTest {
        // A local photo of fp(7); the server record of item 2 (mirrored) says its members are fp(2) only.
        serve(item(7))
        repo.receive(request(refs(ServerItemRef(id(7)))))
        val seven = rows().single().id
        db.cullingDao().addFiles(listOf(CullingFileEntity(fp(2), id(2))))
        serve(item(2))
        val outcome = repo.receive(request(refs(ServerItemRef(id(2), fingerprint = fp(7)))))
        outcome as HandoffOutcome.OpenEditor
        val photos = rows()
        assertThat(photos).hasSize(2)
        assertThat(photos.first { it.id != seven }.name).isEqualTo("DSC00002.ARW")
        assertThat(server.itemCalls).contains(id(2))
    }

    @Test
    fun `files the server gives no name get the sender's name, else one made of the fingerprint`() = runTest {
        server.items[id(1)] = ServerItemResult.Found(
            ServerItemInfo(
                itemId = id(1),
                mainFingerprint = fp(1),
                files = listOf(
                    ServerFileInfo(fp(1), "raw", "", 100, "arw"),
                    ServerFileInfo(fp(2), "rendered", "", 50, "jpeg"),
                ),
            ),
        )
        server.items[id(2)] = ServerItemResult.Found(
            ServerItemInfo(id(2), fp(3), listOf(ServerFileInfo(fp(3), "rendered", "", 50, "heif"))),
        )
        repo.receive(request(refs(ServerItemRef(id(1), name = "DSC9.ARW"), ServerItemRef(id(2)))))
        val names = rows().associate { it.id to db.photoDao().files(it.id).map { f -> f.name } }.values.toList()
        assertThat(names.flatten()).containsExactly(
            "DSC9.ARW",
            fp(2).removePrefix("v1:").take(12) + ".jpg",
            fp(3).removePrefix("v1:").take(12) + ".heic",
        )
        assertThat(rows().map { it.format }).containsExactly(PhotoFormat.RAW_PAIR, PhotoFormat.HEIF)
    }

    @Test
    fun `an unknown item id falls back to a fingerprint lookup`() = runTest {
        serve(item(3))
        server.lookups[fp(3)] = id(3)
        val outcome = repo.receive(request(refs(ServerItemRef(id(99), fingerprint = fp(3)))))
        outcome as HandoffOutcome.OpenEditor
        assertThat(server.lookupCalls).containsExactly(listOf(fp(3)))
        assertThat(rows().single().name).isEqualTo("DSC00003.ARW")
    }

    @Test
    fun `a tombstone with a replacement is followed once, one without is skipped`() = runTest {
        serve(item(2))
        server.items[id(1)] = ServerItemResult.Gone(replacedBy = id(2))
        server.items[id(4)] = ServerItemResult.Gone(replacedBy = null)
        val outcome = repo.receive(request(refs(ServerItemRef(id(1)), ServerItemRef(id(4)))))
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.notFound).isEqualTo(1)
        assertThat(rows().single().name).isEqualTo("DSC00002.ARW")
    }

    @Test
    fun `items the server does not have are skipped and counted, the rest of the batch opens`() = runTest {
        serve(item(1), item(2))
        val outcome = repo.receive(
            request(refs(ServerItemRef(id(1)), ServerItemRef(id(8)), ServerItemRef(id(2)), ServerItemRef(id(9)))),
        )
        outcome as HandoffOutcome.OpenLibrary
        assertThat(outcome.notFound).isEqualTo(2)
        assertThat(outcome.photoIds).hasSize(2)
        assertThat(rows()).hasSize(2)
    }

    @Test
    fun `when none exists the outcome says how many were not found`() = runTest {
        val outcome = repo.receive(request(refs(ServerItemRef(id(8)), ServerItemRef(id(9)))))
        assertThat(outcome).isEqualTo(HandoffOutcome.ServerRefs(issue = null, notFound = 2))
        assertThat(server.pulls).isEqualTo(0)
    }

    @Test
    fun `the initial item opens in the editor with the library behind it`() = runTest {
        serve(item(1), item(2), item(3))
        val outcome = repo.receive(
            request(refs(ServerItemRef(id(1)), ServerItemRef(id(2)), ServerItemRef(id(3)), initial = id(2))),
        )
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.libraryBehind).isTrue()
        assertThat(outcome.closeOnBack).isFalse()
        val photoId = db.photoDao().photoIdOfVersion(outcome.versionId)!!
        assertThat(db.photoDao().get(photoId)!!.name).isEqualTo("DSC00002.ARW")
        assertThat(rows()).hasSize(3)
    }

    @Test
    fun `uri items and server items are one batch and the initial item wins over the initial uri`() = runTest {
        serve(item(1))
        val dir = temp.newFolder("mixed")
        val uri = Uri.fromFile(
            File(dir, "IMG_9.JPG").apply {
                writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + "9".toByteArray())
            },
        )
        val outcome = repo.receive(
            HandoffRequest(
                uris = listOf(uri),
                server = refs(ServerItemRef(id(1)), initial = id(1)),
                initial = uri,
                title = "Both",
            ),
        )
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.libraryBehind).isTrue()
        val photos = rows()
        assertThat(photos).hasSize(2)
        assertThat(photos.all { it.handoffTitle == "Both" }).isTrue()
        val opened = db.photoDao().photoIdOfVersion(outcome.versionId)!!
        assertThat(db.photoDao().get(opened)!!.name).isEqualTo("DSC00001.ARW")
    }

    @Test
    fun `not paired keeps the batch, pairing with that server resolves it`() = runTest {
        server.pairing = null
        serve(item(1), item(2))
        val outcome = repo.receive(request(refs(ServerItemRef(id(1)), ServerItemRef(id(2)), initial = id(2)), "Trip"))
        assertThat(outcome).isEqualTo(HandoffOutcome.ServerRefs(ServerIssue.NotPaired(2)))
        assertThat(repo.hasPending()).isTrue()
        assertThat(repo.pendingServerId()).isEqualTo(SERVER)
        assertThat(rows()).isEmpty()
        // Another server's pairing does not resolve it.
        assertThat(repo.resumePending("someone-else")).isNull()
        server.pairing = ServerPairing(SERVER, "Home NAS", "own-device")
        val resumed = repo.resumePending(SERVER)
        resumed as HandoffOutcome.OpenEditor
        assertThat(resumed.libraryBehind).isTrue()
        assertThat(rows().map { it.handoffTitle }).containsExactly("Trip", "Trip")
        assertThat(repo.hasPending()).isFalse()
        assertThat(repo.resumePending(SERVER)).isNull()
    }

    @Test
    fun `cancelling drops the pending batch`() = runTest {
        server.pairing = null
        repo.receive(request(refs(ServerItemRef(id(1)))))
        repo.dropPending()
        assertThat(repo.hasPending()).isFalse()
    }

    @Test
    fun `paired with another server names it and keeps nothing`() = runTest {
        serve(item(1))
        val outcome = repo.receive(request(refs(ServerItemRef(id(1)), serverId = "other-server")))
        assertThat(outcome).isEqualTo(HandoffOutcome.ServerRefs(ServerIssue.OtherServer("Home NAS", 1)))
        assertThat(repo.hasPending()).isFalse()
        assertThat(server.itemCalls).isEmpty()
        assertThat(rows()).isEmpty()
    }

    @Test
    fun `an unreachable server opens what is named in the intent and fills it in when it answers`() = runTest {
        server.up = false
        val outcome = repo.receive(
            request(refs(ServerItemRef(id(1), fp(1), listOf(fp(1)), "DSC00001.ARW"), ServerItemRef(id(2)))),
        )
        outcome as HandoffOutcome.OpenEditor
        assertThat(outcome.issue).isEqualTo(ServerIssue.Unreachable(2))
        // Only the item that came with a name and a fingerprint has a photo to show.
        val photo = rows().single()
        assertThat(photo.name).isEqualTo("DSC00001.ARW")
        assertThat(db.photoDao().files(photo.id).single().onServer).isTrue()
        assertThat(db.photoDao().get(photo.id)!!.width).isNull()

        server.up = true
        serve(item(1, jpeg = true), item(2))
        assertThat(repo.retryUnreachable()).isTrue()
        val filled = rows()
        // Item 2 had no name to show before: it exists now that the server answered.
        assertThat(filled.map { it.name }).containsExactly("DSC00001.ARW", "DSC00002.ARW")
        assertThat(db.photoDao().get(photo.id)!!.format).isEqualTo(PhotoFormat.RAW_PAIR)
        assertThat(db.photoDao().files(photo.id)).hasSize(2)
        assertThat(repo.retryUnreachable()).isFalse()
    }

    @Test
    fun `500 items resolve in one batch`() = runTest {
        val infos = (1..500).map { item(it, folder = "Big") }
        serve(*infos.toTypedArray())
        val outcome = repo.receive(request(refs(*infos.map { ServerItemRef(it.itemId) }.toTypedArray())))
        outcome as HandoffOutcome.OpenLibrary
        assertThat(outcome.photoIds).hasSize(500)
        assertThat(rows()).hasSize(500)
        assertThat(server.pulls).isEqualTo(1)
        // The same batch again finds every photo locally.
        server.itemCalls.clear()
        repo.receive(request(refs(*infos.map { ServerItemRef(it.itemId, it.mainFingerprint) }.toTypedArray())))
        assertThat(server.itemCalls).isEmpty()
    }

    @Test
    fun `a hidden Server photo comes back when the sender hands it over again`() = runTest {
        serve(item(1))
        repo.receive(request(refs(ServerItemRef(id(1), fp(1)))))
        val photo = rows().single()
        db.photoDao().setHidden(listOf(photo.id), true)
        assertThat(rows()).isEmpty()
        repo.receive(request(refs(ServerItemRef(id(1), fp(1)))))
        assertThat(rows().single().id).isEqualTo(photo.id)
    }

    // ------------------------------------------------------------------ CULLING_VERSION 2 on URI items

    private fun uriPhoto(name: String): Uri {
        val dir = temp.newFolder(name)
        return Uri.fromFile(
            File(dir, "$name.JPG").apply {
                writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + name.toByteArray())
            },
        )
    }

    private suspend fun send(uri: Uri, culling: HandoffCulling) =
        repo.receive(HandoffRequest(listOf(uri), culling = mapOf(uri to culling)))

    @Test
    fun `dated culling values are last-writer-wins per field against what Reveal holds`() = runTest {
        val uri = uriPhoto("lww")
        send(uri, HandoffCulling(rating = 3, ratingAt = 2_000, ratingDevice = "dev-m"))
        assertThat(rows().single().rating).isEqualTo(3)
        // Older: loses. Same time, smaller device id than ours ("own-device"): loses. Same time, greater: wins.
        send(uri, HandoffCulling(rating = 5, ratingAt = 1_000, ratingDevice = "zzz"))
        assertThat(rows().single().rating).isEqualTo(3)
        send(uri, HandoffCulling(rating = 5, ratingAt = 2_000, ratingDevice = "aaa"))
        assertThat(rows().single().rating).isEqualTo(3)
        send(uri, HandoffCulling(rating = 4, ratingAt = 2_000, ratingDevice = "zzz"))
        assertThat(rows().single().rating).isEqualTo(4)
        // Newer wins whatever the device; other fields are judged on their own.
        send(uri, HandoffCulling(rating = 1, ratingAt = 3_000, flag = PickFlag.PICK, flagAt = 5))
        val photo = rows().single()
        assertThat(photo.rating).isEqualTo(1)
        assertThat(photo.flag).isEqualTo(PickFlag.PICK)
    }

    @Test
    fun `undated values keep version 1's precedence`() = runTest {
        val uri = uriPhoto("v1")
        send(uri, HandoffCulling(rating = 2, ratingAt = 9_000))
        send(uri, HandoffCulling(rating = 5)) // no date: the values win as before
        assertThat(rows().single().rating).isEqualTo(5)
    }

    @Test
    fun `the one comparator orders the equal-time case`() {
        assertThat(CullingStamp(7, "b").beats(CullingStamp(7, "a"))).isTrue()
        assertThat(CullingStamp(7, "a").beats(CullingStamp(7, "b"))).isFalse()
    }

    private companion object {
        const val SERVER = "5b1c0a52-0000-4000-8000-000000000001"
    }
}
