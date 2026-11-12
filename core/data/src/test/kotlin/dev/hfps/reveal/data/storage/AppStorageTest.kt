package dev.hfps.reveal.data.storage

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoCloudEntity
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.PhotoFileEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.thumb.UprightSize
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Settings -> Storage (ADR 0018 amendment FIX/W): usage by kind, "Clear cache" and the LRU cache budget. */
@RunWith(AndroidJUnit4::class)
class AppStorageTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private var now = 1_000_000_000L

    /** A cache area over a folder: one file per entry, its mtime the last use. */
    private class FolderArea(override val kind: StorageKind, val dir: File) : CacheArea {
        override suspend fun items() =
            dir.listFiles().orEmpty().map { CacheItem(it.name, it.length(), it.lastModified()) }

        override suspend fun evict(keys: Collection<String>) = keys.forEach { File(dir, it).delete() }

        override suspend fun clear() {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun write(dir: File, name: String, bytes: Int, used: Long): File =
        File(dir.apply { mkdirs() }, name).apply {
            writeBytes(ByteArray(bytes))
            setLastModified(used)
        }

    /** Its background trims never run here (a cancelled scope): the tests call [CacheBudget.trim]. */
    private fun budget() = CacheBudget(null, CoroutineScope(Job().apply { cancel() })) { now }

    @Test
    fun `the budget drops the least recently used entries across every cache`() = runTest {
        val budget = budget()
        val cache = temp.newFolder("cache")
        val thumbs = FolderArea(StorageKind.THUMBNAILS, File(cache, "thumbs"))
        val server = FolderArea(StorageKind.SERVER_FILES, File(cache, "sync-originals"))
        budget.register(thumbs)
        budget.register(server)
        write(thumbs.dir, "t-old", 400, used = 1_000)
        write(server.dir, "s-mid", 400, used = 2_000)
        write(thumbs.dir, "t-new", 400, used = 3_000)
        budget.setMaxBytes(1_000)
        val freed = budget.trim()
        assertThat(freed).isEqualTo(400)
        assertThat(thumbs.dir.list()!!.toList()).containsExactly("t-new")
        assertThat(server.dir.list()!!.toList()).containsExactly("s-mid")
        // Under the cap: nothing to do.
        assertThat(budget.trim()).isEqualTo(0)
    }

    @Test
    fun `usage by kind, and clear keeps edits, recent files and previews that are a photo's only copy`() = runTest {
        val budget = budget()
        val cache = temp.newFolder("cache")
        val thumbs = FolderArea(StorageKind.THUMBNAILS, File(cache, "thumbs"))
        val server = FolderArea(StorageKind.SERVER_FILES, File(cache, "sync-originals"))
        budget.register(thumbs)
        budget.register(server)
        write(thumbs.dir, "a.jpg", 100, used = now)
        write(server.dir, "block", 200, used = now)
        write(File(cache, "export"), "old.part", 50, used = now - AppStorage.RECENT_MS - 1)
        val writing = write(File(cache, "export"), "now.part", 70, used = now)
        val uploads = temp.newFolder("sync-previews")
        write(uploads, "up.rvsp", 30, used = now)
        val dbFile = temp.newFile("reveal.db").apply { writeBytes(ByteArray(1000)) }

        // Hand-off previews: one whose photo is gone, one whose original the server holds, one only copy.
        val previews = LocalPreviews(temp.newFolder("handoff-previews"))
        val source = db.sourceDao().insert(SourceEntity(0, "h", "", "apps", null, null, SourceState.AVAILABLE, true, 0))
        for ((i, fingerprint) in listOf("v1:aa", "v1:bb").withIndex()) {
            val photo = db.photoDao().insertPhoto(
                PhotoEntity(
                    sourceId = source, folder = "", folderDocumentId = "", stemKey = "s$i", name = "s$i.jpg",
                    format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
                ),
            )
            db.photoDao().insertFiles(
                listOf(
                    PhotoFileEntity(
                        photoId = photo, sourceId = source, relativePath = "s$i", documentId = "s$i",
                        name = "s$i.jpg", size = 1, modified = 1, role = FileRole.RENDERED, fingerprint = fingerprint,
                    ),
                ),
            )
            if (i == 0) db.syncDao().putCloud(PhotoCloudEntity(photo, originalOnServer = true))
        }
        write(previews.dir, LocalPreviews.key("v1:aa") + ".jpg", 11, used = now) // on the server
        write(previews.dir, LocalPreviews.key("v1:bb") + ".rvsp", 13, used = now) // the only copy
        write(previews.dir, LocalPreviews.key("v1:cc") + ".jpg", 17, used = now) // photo gone

        val storage = AppStorage(db, budget, previews, cache, uploads, { listOf(dbFile) }) { now }
        val usage = storage.usage()
        assertThat(usage[StorageKind.THUMBNAILS]).isEqualTo(100)
        assertThat(usage[StorageKind.SERVER_FILES]).isEqualTo(200)
        assertThat(usage[StorageKind.OTHER]).isEqualTo(50 + 70 + 30)
        assertThat(usage[StorageKind.HANDOFF_PREVIEWS]).isEqualTo(11 + 13 + 17)
        assertThat(usage.keptPreviews).isEqualTo(13)
        assertThat(usage[StorageKind.LIBRARY]).isEqualTo(1000)

        val freed = storage.clearCache()
        assertThat(freed).isEqualTo(100 + 200 + 50 + 11 + 17)
        assertThat(writing.exists()).isTrue()
        assertThat(dbFile.length()).isEqualTo(1000)
        assertThat(previews.dir.list()!!.toList()).containsExactly(LocalPreviews.key("v1:bb") + ".rvsp")
        assertThat(File(uploads, "up.rvsp").exists()).isTrue()
    }

    @Test
    fun `a stored size whose orientation disagrees with the upright image is turned`() {
        // A portrait shot synced without its orientation: 6000 x 4000 stored, the image decodes 1067 x 1600.
        assertThat(UprightSize.match(6000, 4000, 1067, 1600)).isEqualTo(4000 to 6000)
        // Agreeing, unknown or unrelated shapes stay.
        assertThat(UprightSize.match(6000, 4000, 1600, 1067)).isEqualTo(6000 to 4000)
        assertThat(UprightSize.match(0, 0, 1600, 1067)).isEqualTo(1600 to 1067)
        assertThat(UprightSize.match(6000, 4000, 1000, 1000)).isEqualTo(6000 to 4000)
        // A crop made the developed image portrait, but not with the swapped shape: the full size stays.
        assertThat(UprightSize.match(6000, 4000, 900, 1600)).isEqualTo(6000 to 4000)
    }
}
