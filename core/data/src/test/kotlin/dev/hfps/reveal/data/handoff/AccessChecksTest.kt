package dev.hfps.reveal.data.handoff

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * The background access check of hand-offs (ADR 0018 "W3/C", ADR 0011): never at start, one paced pass that skips the
 * files the server holds and those checked lately, trusts a grant the process holds without opening, and leaves a
 * busy provider's document as it was (Hoard ADR 0010).
 */
@RunWith(AndroidJUnit4::class)
class AccessChecksTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val tree = FileUriTree()
    private var now = 1_000_000L
    private val granted = HashSet<String>()
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun library() = LibraryRepository(
        context.contentResolver,
        db,
        null,
        { tree },
        SidecarSync(db, { tree }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        clock = { now },
        grants = { it.toString() in granted },
    )

    private suspend fun handOver(vararg names: String): List<Uri> {
        val uris = names.map { name ->
            val file = File(temp.newFolder(), name).apply {
                writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(60, 40), SyntheticExif()) + name.toByteArray())
            }
            Uri.parse("content://dev.hfps.hoard.stream/h/${name.hashCode() and 0xffff}/$name").also {
                tree.aliases[it.toString()] = file
            }
        }
        HandoffRepository(context.contentResolver, db, LibraryIndexer(db) { now }, tree) { now }
            .receive(HandoffRequest(uris))
        tree.opens.clear()
        return uris
    }

    private suspend fun file(uri: Uri): PhotoFileEntity =
        db.photoDao().handoffFiles().single { it.documentId == uri.toString() }

    @Test
    fun `files the server holds and files checked lately are not opened`() = runTest {
        val (onServer, recent, other) = handOver("A.jpg", "B.jpg", "C.jpg")
        db.syncDao().setOnServer(file(onServer).fingerprint!!, true)
        db.photoDao().setChecked(recent.toString(), now - 60_000)

        val pass = library().backgroundAccessCheck()
        assertThat(tree.opens).containsExactly(other.toString())
        assertThat(pass.skippedServer).isEqualTo(1)
        assertThat(pass.skippedRecent).isEqualTo(1)
        assertThat(pass.opened).isEqualTo(1)
        assertThat(file(other).checkedAt).isEqualTo(now)
        assertThat(pass.summary()).contains("opened=1")

        // Checked now: the next pass in a while opens nothing.
        now += 60_000
        library().backgroundAccessCheck()
        assertThat(tree.opens).hasSize(1)
    }

    @Test
    fun `a grant the process holds counts as readable without an open`() = runTest {
        val (uri) = handOver("D.jpg")
        granted += uri.toString()
        val pass = library().backgroundAccessCheck()
        assertThat(pass.granted).isEqualTo(1)
        assertThat(tree.opens).isEmpty()
        assertThat(file(uri).unreadableAt).isNull()
    }

    @Test
    fun `a busy provider never marks a hand-off unreadable, a refusal does`() = runTest {
        val (busy, refused) = handOver("E.jpg", "F.jpg")
        tree.busy[busy.toString()] = Int.MAX_VALUE
        tree.refused += refused.toString()
        val pass = library().backgroundAccessCheck()
        assertThat(pass.busy).isEqualTo(1)
        assertThat(pass.unreadable).isEqualTo(1)
        assertThat(file(busy).unreadableAt).isNull()
        assertThat(file(refused).unreadableAt).isEqualTo(now)

        // The explicit check (a gallery permission changed) has the same rule.
        assertThat(library().checkHandoffs()).isEqualTo(1)
        assertThat(file(busy).unreadableAt).isNull()
    }

    @Test
    fun `again reopens the unreadable files even when checked lately`() = runTest {
        val (uri) = handOver("G.jpg")
        tree.refused += uri.toString()
        library().backgroundAccessCheck()
        assertThat(file(uri).unreadableAt).isNotNull()
        tree.refused -= uri.toString()
        now += 60_000
        library().backgroundAccessCheck()
        assertThat(file(uri).unreadableAt).isNotNull()
        library().backgroundAccessCheck(again = true)
        assertThat(file(uri).unreadableAt).isNull()
    }
}
