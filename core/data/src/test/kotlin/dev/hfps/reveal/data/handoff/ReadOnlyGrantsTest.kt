package dev.hfps.reveal.data.handoff

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
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

/** W4 (ADR 0026 "Amendment W4"): Reveal takes READ grants only, for new folders and for hand-offs. */
@RunWith(AndroidJUnit4::class)
class ReadOnlyGrantsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val taken = mutableListOf<Pair<Uri, Int>>()
    private val persisted = mutableListOf<UriPermission>()

    private val resolver = object : ContentResolver(context) {
        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
            taken += uri to modeFlags
        }

        override fun getPersistedUriPermissions(): List<UriPermission> = persisted
    }

    /** `UriPermission` has no public constructor. */
    private fun permission(uri: Uri, flags: Int): UriPermission =
        UriPermission::class.java.getDeclaredConstructor(Uri::class.java, Int::class.java, Long::class.java)
            .apply { isAccessible = true }
            .newInstance(uri, flags, 1L)

    private fun library(tree: FileUriTree = FileUriTree(), grantFree: Set<String> = emptySet()) = LibraryRepository(
        resolver,
        db,
        null,
        { tree },
        SidecarSync(db, { tree }, "Reveal test"),
        CoroutineScope(Dispatchers.Unconfined),
        grantFreeAuthorities = grantFree,
    )

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `adding a folder takes a read grant and never asks for write`() = runTest {
        val tree = FileUriTree()
        val library = LibraryRepository(
            resolver,
            db,
            null,
            { tree },
            SidecarSync(db, { tree }, "Reveal test"),
            CoroutineScope(Dispatchers.Unconfined),
        )
        // The provider is not reachable in this test, so the folder is "unreadable": the grant was taken before that.
        library.addSource(Uri.parse("content://com.example.docs/tree/primary%3ADCIM"))
        assertThat(taken).hasSize(1)
        assertThat(taken.single().second).isEqualTo(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    @Test
    fun `a folder is writable only when a write grant really is persisted, or for the named test provider`() {
        val docs = Uri.parse("content://com.example.docs/tree/primary%3ADCIM")
        // No grant row at all (a provider without persistable grants): read only.
        assertThat(library().persist(docs)).isFalse()
        // The read grant just taken: read only.
        persisted += permission(docs, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertThat(library().persist(docs)).isFalse()
        // A write grant an earlier version took: writable.
        persisted.clear()
        persisted += permission(docs, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        assertThat(library().persist(docs)).isTrue()
        // Another tree's write grant does not count.
        assertThat(library().persist(Uri.parse("content://com.example.docs/tree/primary%3APictures"))).isFalse()
        // The debug test provider, named explicitly.
        val synthetic = Uri.parse("content://dev.hfps.reveal.debug.synthetic/tree/root")
        assertThat(library().persist(synthetic)).isFalse()
        assertThat(library(grantFree = setOf("dev.hfps.reveal.debug.synthetic")).persist(synthetic)).isTrue()
    }

    @Test
    fun `a hand-off keeps a read grant only, even when the sender offered write`() = runTest {
        val file = File(temp.newFolder(), "DSC00001.JPG").apply {
            writeBytes(SyntheticShoot.jpeg(SyntheticShoot.markerOnlyJpeg(60, 40), SyntheticExif()))
        }
        val uri = Uri.parse("content://dev.hfps.hoard.stream/h/1/DSC00001.JPG")
        val tree = FileUriTree().also { it.aliases[uri.toString()] = file }
        HandoffRepository(resolver, db, LibraryIndexer(db), tree) { 1_000L }
            .receive(HandoffRequest(uris = listOf(uri), persistable = true))
        assertThat(taken).isNotEmpty()
        assertThat(taken.map { it.second }.toSet()).containsExactly(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
