package dev.hfps.reveal.data.library

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.ExportPresetEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.model.SourceState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** ADR 0026 "Export XMP": the grant of a picked folder lives as long as the job, unless something else uses it. */
@RunWith(AndroidJUnit4::class)
class XmpGrantsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: RevealDatabase
    private val taken = mutableListOf<Pair<String, Int>>()
    private val released = mutableListOf<Pair<String, Int>>()
    private var exportFolder: String? = null

    private val resolver = object : ContentResolver(context) {
        override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
            taken += uri.toString() to modeFlags
        }

        override fun releasePersistableUriPermission(uri: Uri, modeFlags: Int) {
            released += uri.toString() to modeFlags
        }
    }

    private val grants get() = XmpGrants(resolver, db) { exportFolder }

    @Before
    fun setUp() {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun folder(treeUri: String, writable: Boolean, root: String = "primary:DCIM/Shoot") = SourceEntity(
        treeUri = treeUri,
        rootDocumentId = root,
        displayName = "Shoot",
        volumeId = null,
        volumeName = null,
        state = SourceState.AVAILABLE,
        writable = writable,
        addedAt = 0,
    ).let { it.copy(id = db.sourceDao().insert(it)) }

    @Test
    fun `a picked folder is granted read and write, and released when the job ends`() = runTest {
        val out = "content://com.android.externalstorage.documents/tree/primary%3AExports"
        assertThat(grants.take(out)).isTrue()
        assertThat(taken.single()).isEqualTo(out to READ_WRITE)
        grants.release(out)
        assertThat(released.single()).isEqualTo(out to READ_WRITE)
    }

    @Test
    fun `a grant still used elsewhere is kept`() = runTest {
        val out = "content://com.android.externalstorage.documents/tree/primary%3AExports"
        // Another export job waiting on it.
        grants.release(out, inUse = setOf(out))
        // The image export's remembered folder.
        exportFolder = out
        grants.release(out)
        exportFolder = null
        // An export preset that writes there.
        db.versionDao().insertExportPreset(
            ExportPresetEntity(
                name = "Exports",
                optionsJson = """{"destination":{"type":"folder","treeUri":"$out"}}""",
                position = 0,
                createdAt = 0,
                updatedAt = 0,
            ),
        )
        grants.release(out)
        assertThat(released).isEmpty()
    }

    @Test
    fun `a library folder keeps its read grant, and its write grant when it had one`() = runTest {
        val readOnly = "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FShoot"
        folder(readOnly, writable = false)
        grants.release(readOnly)
        assertThat(released.single()).isEqualTo(readOnly to Intent.FLAG_GRANT_WRITE_URI_PERMISSION)

        released.clear()
        val writable = "content://com.android.externalstorage.documents/tree/primary%3APictures"
        folder(writable, writable = true, root = "primary:Pictures")
        grants.release(writable)
        assertThat(released).isEmpty()
    }

    @Test
    fun `next to the photos needs the picked tree to be, or contain, the photos' folder`() = runTest {
        val source = folder(
            "content://com.android.externalstorage.documents/tree/primary%3ADCIM%2FShoot",
            writable = false,
        )
        fun tree(id: String) = "content://com.android.externalstorage.documents/tree/" + Uri.encode(id)
        assertThat(XmpGrants.covers(tree("primary:DCIM/Shoot"), source)).isTrue()
        assertThat(XmpGrants.covers(tree("primary:DCIM"), source)).isTrue()
        assertThat(XmpGrants.covers(tree("primary:"), source)).isTrue()
        assertThat(XmpGrants.covers(tree("primary:DCIM/Shoot 2"), source)).isFalse()
        assertThat(XmpGrants.covers(tree("primary:DCIM/Shoot/Sub"), source)).isFalse()
        assertThat(XmpGrants.covers(tree("primary:Pictures"), source)).isFalse()
        assertThat(XmpGrants.covers(tree("1234-5678:DCIM"), source)).isFalse()
        assertThat(XmpGrants.covers("content://other.provider/tree/" + Uri.encode("primary:DCIM"), source)).isFalse()
        // Ids that are not paths: the provider is asked.
        assertThat(XmpGrants.covers(tree("opaque-7"), source) { _, root -> root == "primary:DCIM/Shoot" }).isTrue()
    }

    private companion object {
        const val READ_WRITE = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}
