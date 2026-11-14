package dev.hfps.reveal.data.db

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.EditFilter
import dev.hfps.reveal.data.model.FileRole
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.data.model.TypeFilter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library home's query (FIX/L, ADR 0006 "Filters"): every filter group, OR within a group and AND across groups,
 * the Server photos that open listed once, the counts, and query plans that use the indexes.
 */
@RunWith(AndroidJUnit4::class)
class PhotoQuerySqlTest {
    private lateinit var db: RevealDatabase
    private var cardId = 0L
    private var phoneId = 0L
    private var handoffId = 0L
    private var serverId = 0L

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = runTest {
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        cardId = source("card", SourceKind.FOLDER)
        phoneId = source("phone", SourceKind.FOLDER)
        handoffId = source("hand-offs", SourceKind.HANDOFF)
        serverId = source("server", SourceKind.SERVER)
        // card: a picked RAW pair backed up; a 3-star edited JPEG; a rejected RAW with an upload waiting.
        photo("C1.ARW", cardId, raw = true, flag = PickFlag.PICK, time = 10, cloudOriginal = true)
        photo("C2.JPG", cardId, rating = 3, edited = true, time = 20)
        photo("C3.ARW", cardId, raw = true, flag = PickFlag.REJECT, time = 30, uploading = true)
        // phone: a JPEG whose second file is on the server (FIX/V: per file); a folder photo a hand-off opened.
        photo("P1.JPG", phoneId, time = 40, fileOnServer = true)
        photo("P2.JPG", phoneId, time = 50, handoffAt = 500, rating = 1)
        // hand-offs: one photo another app sent.
        photo("H1.JPG", handoffId, time = 60, handoffAt = 600)
        // server: one that opens (original), one with only a preview, one record that opens nothing (unlisted).
        photo("S1.ARW", serverId, raw = true, time = 70, cloudOriginal = true, edited = true)
        photo("S2.JPG", serverId, time = 80, cloudPreview = true)
        photo("S3.JPG", serverId, time = 90)
        // hidden: never listed.
        photo("X1.JPG", cardId, time = 100, hidden = true)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun source(name: String, kind: SourceKind) = db.sourceDao().insert(
        SourceEntity(
            treeUri = "content://test/tree/$name",
            rootDocumentId = "",
            displayName = name,
            volumeId = null,
            volumeName = null,
            state = SourceState.AVAILABLE,
            writable = true,
            addedAt = 0,
            kind = kind,
        ),
    )

    @Suppress("LongParameterList")
    private suspend fun photo(
        name: String,
        sourceId: Long,
        time: Long,
        raw: Boolean = false,
        rating: Int = 0,
        flag: PickFlag = PickFlag.NONE,
        edited: Boolean = false,
        handoffAt: Long? = null,
        cloudOriginal: Boolean = false,
        cloudPreview: Boolean = false,
        fileOnServer: Boolean = false,
        uploading: Boolean = false,
        hidden: Boolean = false,
    ) {
        val dao = db.photoDao()
        val id = dao.insertPhoto(
            PhotoEntity(
                sourceId = sourceId,
                folder = "",
                folderDocumentId = "",
                stemKey = name.substringBefore('.').lowercase(),
                name = name,
                format = if (raw) PhotoFormat.RAW else PhotoFormat.JPEG,
                hasRaw = raw,
                sortTime = time,
                rating = rating,
                flag = flag,
                hasEdit = edited,
                addedAt = 0,
                handoffAt = handoffAt,
                hidden = hidden,
            ),
        )
        val versionId = dao.insertVersion(VersionEntity(photoId = id, createdAt = 0))
        // "Edited" is per version (docs/adr/0025): a non-default recipe.
        if (edited) db.editDao().putRecipe(RecipeEntity(versionId, "{}", isDefault = false, updatedAt = 1))
        dao.insertFiles(
            listOf(
                PhotoFileEntity(
                    photoId = id, sourceId = sourceId, relativePath = name, documentId = name, name = name,
                    size = 1, modified = 0, role = if (raw) FileRole.RAW else FileRole.RENDERED,
                    fingerprint = "fp-$name",
                ),
                PhotoFileEntity(
                    photoId = id, sourceId = sourceId, relativePath = "$name.xmp2", documentId = "$name.2",
                    name = "$name.2", size = 1, modified = 0, role = FileRole.OTHER, onServer = fileOnServer,
                ),
            ),
        )
        if (cloudOriginal || cloudPreview) {
            db.syncDao().putCloud(
                PhotoCloudEntity(
                    photoId = id,
                    originalOnServer = cloudOriginal,
                    previewSha = if (cloudPreview) "sha-$name" else null,
                ),
            )
        }
        if (uploading) {
            db.syncDao().insertUpload(
                SyncUploadEntity(
                    photoId = id,
                    kind = SyncUploadEntity.KIND_ORIGINAL,
                    sha256 = "s",
                    size = 1,
                    updatedAt = 0,
                ),
            )
        }
    }

    private suspend fun names(filters: PhotoFilters = PhotoFilters.NONE, scope: LibraryScope = LibraryScope.All) =
        db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(scope = scope, filters = filters))).map { it.name }

    @Test
    fun `the home lists every photo here and the Server photos that open, newest first`() = runTest {
        assertThat(names()).containsExactly(
            "S2.JPG",
            "S1.ARW",
            "H1.JPG",
            "P2.JPG",
            "P1.JPG",
            "C3.ARW",
            "C2.JPG",
            "C1.ARW",
        ).inOrder()
        val rows = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery()))
        assertThat(rows.filter { it.remote }.map { it.name }).containsExactly("S1.ARW", "S2.JPG")
        assertThat(rows.single { it.name == "C3.ARW" }.uploading).isTrue()
        assertThat(rows.count { it.uploading }).isEqualTo(1)
    }

    @Test
    fun `the source group`() = runTest {
        assertThat(names(PhotoFilters.folder(cardId))).containsExactly("C3.ARW", "C2.JPG", "C1.ARW")
        // Folder photos a hand-off opened count as "Opened from other apps", in hand-off order.
        assertThat(names(PhotoFilters.FROM_APPS)).containsExactly("H1.JPG", "P2.JPG").inOrder()
        assertThat(names(PhotoFilters.SERVER_ONLY)).containsExactly("S2.JPG", "S1.ARW")
        // OR within the group.
        val two = PhotoFilters.folder(phoneId).toggle(SourceFilter.Folder(cardId))
        assertThat(names(two)).hasSize(5)
        assertThat(names(PhotoFilters.folder(cardId).toggle(SourceFilter.ServerOnly)))
            .containsExactly("S2.JPG", "S1.ARW", "C3.ARW", "C2.JPG", "C1.ARW")
        assertThat(names(PhotoFilters.FROM_APPS.toggle(SourceFilter.Folder(cardId)))).hasSize(5)
    }

    @Test
    fun `the culling, edits and type groups`() = runTest {
        fun f(vararg c: CullingFilter) = PhotoFilters(culling = c.toSet())
        assertThat(names(f(CullingFilter.PICKED))).containsExactly("C1.ARW")
        assertThat(names(f(CullingFilter.STARRED))).containsExactly("C2.JPG", "P2.JPG")
        assertThat(names(f(CullingFilter.REJECTED))).containsExactly("C3.ARW")
        assertThat(names(f(CullingFilter.PICKED, CullingFilter.REJECTED))).containsExactly("C1.ARW", "C3.ARW")
        assertThat(names(PhotoFilters(edits = setOf(EditFilter.EDITED)))).containsExactly("C2.JPG", "S1.ARW")
        assertThat(names(PhotoFilters(edits = setOf(EditFilter.NOT_EDITED)))).hasSize(6)
        assertThat(names(PhotoFilters(edits = EditFilter.entries.toSet()))).hasSize(8)
        assertThat(names(PhotoFilters(types = setOf(TypeFilter.RAW)))).containsExactly("C1.ARW", "C3.ARW", "S1.ARW")
        assertThat(names(PhotoFilters(types = setOf(TypeFilter.JPEG)))).hasSize(5)
    }

    @Test
    fun `the sync group goes by the server's originals, per file, and counts Server photos`() = runTest {
        assertThat(names(PhotoFilters(sync = setOf(SyncFilter.SYNCED))))
            .containsExactly("S2.JPG", "S1.ARW", "P1.JPG", "C1.ARW")
        // An upload still waiting is not synced.
        assertThat(names(PhotoFilters(sync = setOf(SyncFilter.NOT_SYNCED))))
            .containsExactly("H1.JPG", "P2.JPG", "C3.ARW", "C2.JPG")
        assertThat(names(PhotoFilters(sync = SyncFilter.entries.toSet()))).hasSize(8)
    }

    @Test
    fun `groups combine with AND`() = runTest {
        val raw = PhotoFilters(types = setOf(TypeFilter.RAW))
        assertThat(names(raw.toggle(SourceFilter.Folder(cardId)))).containsExactly("C3.ARW", "C1.ARW")
        assertThat(names(raw.toggle(SourceFilter.Folder(cardId)).toggle(CullingFilter.PICKED)))
            .containsExactly("C1.ARW")
        assertThat(names(raw.toggle(SyncFilter.SYNCED))).containsExactly("S1.ARW", "C1.ARW")
        assertThat(names(raw.toggle(EditFilter.EDITED).toggle(SourceFilter.ServerOnly))).containsExactly("S1.ARW")
        assertThat(names(PhotoFilters.FROM_APPS.toggle(CullingFilter.STARRED))).containsExactly("P2.JPG")
        assertThat(names(raw.toggle(CullingFilter.STARRED))).isEmpty()
        // Search narrows on top.
        val rows = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(filters = raw, search = "c")))
        assertThat(rows.map { it.name }).containsExactly("C3.ARW", "C1.ARW")
    }

    @Test
    fun `recent is its own view and the sort orders apply to the home`() = runTest {
        assertThat(names(scope = LibraryScope.Recent)).isEmpty()
        val byName = db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery(sort = PhotoSort.NAME))).map { it.name }
        assertThat(byName.first()).isEqualTo("C1.ARW")
        assertThat(byName.last()).isEqualTo("S2.JPG")
    }

    @Test
    fun `the arguments are bound in order`() {
        val (sql, args) = PhotoQuerySql.build(
            PhotoQuery(
                filters = PhotoFilters.folder(9).toggle(SourceFilter.Folder(4)).toggle(CullingFilter.PICKED),
                search = "x",
            ),
        )
        assertThat(args).containsExactly(4L, 9L, "%x%").inOrder()
        assertThat(sql).contains("p.source_id IN (?, ?)")
        assertThat(sql.count { it == '?' }).isEqualTo(3)
    }

    @Test
    fun `the counts per choice`() = runTest {
        val counts = db.photoDao().observeFilterCounts().first()
        assertThat(counts).isEqualTo(
            FilterCounts(
                fromApps = 2,
                serverOnly = 2,
                picked = 1,
                starred = 2,
                rejected = 1,
                edited = 2,
                notEdited = 6,
                raw = 3,
                jpeg = 5,
                synced = 4,
                total = 8,
            ),
        )
        assertThat(counts.notSynced).isEqualTo(4)
    }

    /**
     * EXPLAIN QUERY PLAN: no table is read in full except `sources` (a handful of rows): the photos through an index,
     * and every per-row subquery through its `photo_id` index, whatever the filters.
     */
    @Test
    fun `the query plans use the indexes`() {
        val queries = listOf(
            PhotoQuery(),
            PhotoQuery(filters = PhotoFilters.folder(cardId)),
            PhotoQuery(filters = PhotoFilters(culling = setOf(CullingFilter.PICKED), types = setOf(TypeFilter.RAW))),
            PhotoQuery(filters = PhotoFilters(sync = setOf(SyncFilter.NOT_SYNCED))),
            PhotoQuery(filters = PhotoFilters.FROM_APPS.toggle(SourceFilter.ServerOnly), search = "c"),
            PhotoQuery(scope = LibraryScope.Recent),
        )
        for (query in queries) {
            val lines = plan(query).lines().map { it.trim() }.filter { it.isNotEmpty() }
            for (line in lines.filter { it.startsWith("SCAN ") }) {
                assertWithMessage(line).that(line == "SCAN sources" || " USING " in line).isTrue()
            }
            for (line in lines.filter { it.matches(Regex("SEARCH (uf|sf|su) .*")) }) {
                assertWithMessage(line).that(line).contains("photo_id")
            }
        }
    }

    private fun plan(query: PhotoQuery): String {
        val (sql, args) = PhotoQuerySql.build(query)
        val out = StringBuilder()
        db.openHelper.readableDatabase.query("EXPLAIN QUERY PLAN $sql", args.toTypedArray()).use { c ->
            while (c.moveToNext()) out.append(c.getString(c.columnCount - 1)).append('\n')
        }
        return out.toString()
    }
}
