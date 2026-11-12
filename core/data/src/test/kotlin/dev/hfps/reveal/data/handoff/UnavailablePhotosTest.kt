package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoAvailability
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * "Unavailable" (FIX/U): a photo whose file is unreadable AND that has no server original, no server preview and no
 * local preview. The SQL (`PhotoQuerySql.UNAVAILABLE_SQL`), its Kotlin mirror (`PhotoAvailability`) and the
 * repository's local-preview check must agree.
 */
@RunWith(AndroidJUnit4::class)
class UnavailablePhotosTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val tree = FileUriTree()
    private lateinit var previews: LocalPreviews
    private var now = 1_000L
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        previews = LocalPreviews(temp.newFolder("previews"))
    }

    @After
    fun tearDown() = db.close()

    private fun handoffs() = HandoffRepository(context.contentResolver, db, LibraryIndexer(db) { now }, tree) { now }

    private fun library() = LibraryRepository(
        context.contentResolver,
        db,
        null,
        { tree },
        SidecarSync(db, { tree }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        serverAuthority = ServerTree.authority(context.packageName),
        previews = previews,
    )

    private class Shared(val uri: Uri, val file: File, val photoId: Long) {
        val print: String get() = FileFingerprint.of(file.readBytes())
    }

    private suspend fun share(name: String): Shared {
        val file = File(temp.newFolder(), name).apply {
            writeBytes(
                SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(600, 400), SyntheticExif()) + name.toByteArray(),
            )
        }
        val uri = Uri.parse("content://media/external/images/media/${name.hashCode() and 0xffff}")
        tree.aliases[uri.toString()] = file
        handoffs().receive(HandoffRequest(listOf(uri)))
        val row = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))
            .first { it.name == name }
        return Shared(uri, file, row.id)
    }

    private suspend fun rows(unavailable: Boolean = false) =
        library().photoList(PhotoQuery.FROM_APPS.copy(unavailableOnly = unavailable))

    private suspend fun cloud(photo: Shared, original: Boolean = false, preview: Boolean = false) =
        db.syncDao().putCloud(
            PhotoCloudEntity(
                photo.photoId,
                originalOnServer = original,
                previewSha = if (preview) "ab".repeat(32) else null,
            ),
        )

    private suspend fun markUnreadable(photo: Shared) {
        tree.refused += photo.uri.toString()
        library().recheckAccess(force = true)
        db.photoDao().setUnreadable(photo.uri.toString(), now)
    }

    @Test
    fun `the Kotlin mirror is true only when nothing stands in for the unreadable file`() {
        assertThat(PhotoAvailability.isUnavailable(true, false, false, false)).isTrue()
        assertThat(PhotoAvailability.isUnavailable(false, false, false, false)).isFalse()
        assertThat(PhotoAvailability.isUnavailable(true, true, false, false)).isFalse()
        assertThat(PhotoAvailability.isUnavailable(true, false, true, false)).isFalse()
        assertThat(PhotoAvailability.isUnavailable(true, false, false, true)).isFalse()
    }

    @Test
    fun `the SQL and the mirror agree for every combination`() = runTest {
        val cases = listOf(
            Triple(false, false, false),
            Triple(true, false, false),
            Triple(true, true, false),
            Triple(true, false, true),
        )
        for ((i, case) in cases.withIndex()) {
            val photo = share("20261112_1030$i.jpg")
            val (unreadable, original, preview) = case
            previews.displayFile(photo.print).delete()
            previews.smartFile(photo.print).delete()
            if (original || preview) cloud(photo, original, preview)
            if (unreadable) markUnreadable(photo)
            val row = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery.FROM_APPS))
                .first { it.id == photo.photoId }
            assertThat(row.unavailable)
                .isEqualTo(PhotoAvailability.isUnavailable(unreadable, original, preview, localPreview = false))
        }
    }

    @Test
    fun `a local preview keeps an unreadable photo out of the filter and the count`() = runTest {
        val photo = share("20261112_103100.jpg")
        previews.displayFile(photo.print).writeBytes(byteArrayOf(1))
        markUnreadable(photo)
        assertThat(rows().single().unreadable).isTrue()
        assertThat(rows().single().unavailable).isFalse()
        assertThat(rows(unavailable = true)).isEmpty()
        assertThat(library().unavailableCount(PhotoFilters.FROM_APPS).first()).isEqualTo(0)

        // The preview is gone (cache trimmed): now nothing stands in for the file.
        previews.displayFile(photo.print).delete()
        previews.smartFile(photo.print).delete()
        assertThat(rows(unavailable = true).map { it.id }).containsExactly(photo.photoId)
        assertThat(library().unavailableCount(PhotoFilters.FROM_APPS).first()).isEqualTo(1)
    }

    @Test
    fun `readable photos are not counted, each scope counts its own`() = runTest {
        val gone = share("20261112_103200.jpg")
        share("20261112_103300.jpg")
        previews.displayFile(gone.print).delete()
        previews.smartFile(gone.print).delete()
        markUnreadable(gone)
        assertThat(rows(unavailable = true).map { it.name }).containsExactly("20261112_103200.jpg")
        // "All" holds hand-off photos too; the Server scope never does.
        assertThat(library().unavailableCount().first()).isEqualTo(1)
        assertThat(library().unavailableCount(PhotoFilters.SERVER_ONLY).first()).isEqualTo(0)
    }

    @Test
    fun `re-checking clears the mark of a file that is readable again, and is rate limited`() = runTest {
        val photo = share("20261112_103400.jpg")
        previews.displayFile(photo.print).delete()
        previews.smartFile(photo.print).delete()
        markUnreadable(photo)
        assertThat(rows(unavailable = true)).hasSize(1)

        // Still refused: stays marked. Inside the rate limit nothing is even tried.
        assertThat(library().recheckAccess(force = true)).isEqualTo(1)
        tree.refused -= photo.uri.toString()
        assertThat(library().recheckAccess()).isEqualTo(-1)
        assertThat(rows(unavailable = true)).hasSize(1)

        now += 60_000
        assertThat(library().recheckAccess()).isEqualTo(0)
        assertThat(rows(unavailable = true)).isEmpty()
        assertThat(rows().single().unreadable).isFalse()
    }

    @Test
    fun `try again narrows to the chosen photos`() = runTest {
        val a = share("20261112_103500.jpg")
        val b = share("20261112_103600.jpg")
        markUnreadable(a)
        markUnreadable(b)
        tree.refused.clear()
        assertThat(library().recheckAccess(listOf(a.photoId), force = true)).isEqualTo(0)
        assertThat(rows().first { it.id == a.photoId }.unreadable).isFalse()
        assertThat(rows().first { it.id == b.photoId }.unreadable).isTrue()
    }

    @Test
    fun `removing a hand-off forgets it with its edits and previews, and counts the edits`() = runTest {
        val photo = share("20261112_103700.jpg")
        db.photoDao().setHasEdit(photo.photoId, true)
        previews.displayFile(photo.print).writeBytes(byteArrayOf(1))
        assertThat(library().editedCount(listOf(photo.photoId))).isEqualTo(1)
        val print = photo.print

        library().forget(listOf(photo.photoId))

        assertThat(rows()).isEmpty()
        assertThat(db.photoDao().get(photo.photoId)).isNull()
        assertThat(previews.display(print)).isNull()
    }
}
