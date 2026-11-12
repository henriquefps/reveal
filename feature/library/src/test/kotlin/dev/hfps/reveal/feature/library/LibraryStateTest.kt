package dev.hfps.reveal.feature.library

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.FilterCounts
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.index.IndexPhase
import dev.hfps.reveal.data.index.IndexProgress
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.sync.SyncProblem
import dev.hfps.reveal.sync.SyncStatus
import org.junit.Test

class LibraryStateTest {
    private fun row(id: Long, at: Long?, title: String?, remote: Boolean = false, conflict: Boolean = false) = PhotoRow(
        id = id,
        sourceId = 1,
        name = "p$id.jpg",
        format = PhotoFormat.JPEG,
        sortTime = id * 86_400_000L,
        captureTime = null,
        rating = 0,
        label = null,
        flag = PickFlag.NONE,
        hasEdit = false,
        versionId = id + 100,
        editTime = null,
        handoffAt = at,
        handoffTitle = title,
        remote = remote,
        conflict = conflict,
    )

    private val card = SourceItem(1, "Card", null, true, SourceState.AVAILABLE, true, 12, null)

    @Test
    fun `hand-offs alone get a header per batch and no date headers`() {
        val rows = listOf(row(1, 20, "Culling"), row(2, 20, "Culling"), row(3, 10, null))
        val entries = buildGridEntries(rows, PhotoQuery.FROM_APPS)
        assertThat(entries.map { it::class.simpleName })
            .containsExactly("Group", "Photo", "Photo", "Group", "Photo").inOrder()
        assertThat((entries[0] as GridEntry.Group).title).isEqualTo("Culling")
        assertThat((entries[3] as GridEntry.Group).title).isNull()
        // Combined with a folder they are ordinary photos with date headers.
        val combined =
            buildGridEntries(rows, PhotoQuery(filters = PhotoFilters.FROM_APPS.toggle(SourceFilter.Folder(1))))
        assertThat(combined.filterIsInstance<GridEntry.Header>()).hasSize(3)
    }

    @Test
    fun `the home has date headers, Recent and the name order none`() {
        val rows = listOf(row(3, null, null), row(2, null, null), row(1, null, null))
        assertThat(buildGridEntries(rows, PhotoQuery()).filterIsInstance<GridEntry.Header>()).hasSize(3)
        assertThat(buildGridEntries(rows, PhotoQuery(scope = LibraryScope.Recent)).all { it is GridEntry.Photo })
            .isTrue()
        assertThat(buildGridEntries(rows, PhotoQuery(sort = PhotoSort.NAME)).all { it is GridEntry.Photo }).isTrue()
    }

    @Test
    fun `the grid query is the home's, or Recent without filters`() {
        val filtered = LibraryUiState(query = PhotoQuery(filters = PhotoFilters.folder(1)))
        assertThat(filtered.gridQuery).isEqualTo(filtered.query)
        assertThat(filtered.filtered).isTrue()
        assertThat(filtered.copy(recent = true).gridQuery).isEqualTo(PhotoQuery(scope = LibraryScope.Recent))
        assertThat(filtered.copy(recent = true).filtered).isFalse()
    }

    @Test
    fun `the banner folder is the one the home is narrowed to`() {
        val state = LibraryUiState(sources = listOf(card), query = PhotoQuery(filters = PhotoFilters.folder(1)))
        assertThat(state.currentSource).isEqualTo(card)
        assertThat(
            state.copy(query = PhotoQuery(filters = PhotoFilters.folder(1).toggle(SourceFilter.FromApps)))
                .currentSource,
        ).isNull()
    }

    @Test
    fun `the library is empty only without folders and photos`() {
        val none = LibraryUiState(sourcesLoaded = true, counts = FilterCounts())
        assertThat(none.libraryEmpty).isTrue()
        assertThat(none.copy(counts = FilterCounts(fromApps = 2, total = 2)).libraryEmpty).isFalse()
        assertThat(none.copy(sources = listOf(card)).libraryEmpty).isFalse()
        assertThat(none.copy(counts = null).libraryEmpty).isFalse()
    }

    @Test
    fun `selection actions apply to the selected rows of any filtered grid`() {
        val rows = listOf(row(1, null, null, remote = true), row(2, null, null), row(3, null, null, conflict = true))
        val state = LibraryUiState(entries = buildGridEntries(rows, PhotoQuery()), selected = setOf(1, 3))
        assertThat(state.selectedRemote).containsExactly(1L)
        assertThat(state.selectedConflictCopies).containsExactly(103L)
    }

    @Test
    fun `the sync badge`() {
        assertThat(SyncBadge.of(false, SyncStatus(uploadsPending = 3))).isEqualTo(SyncBadge.NotPaired)
        assertThat(SyncBadge.of(true, SyncStatus())).isEqualTo(SyncBadge.Synced)
        assertThat(SyncBadge.of(true, SyncStatus(running = true))).isEqualTo(SyncBadge.Syncing)
        assertThat(SyncBadge.of(true, SyncStatus(running = true, uploadsPending = 12)))
            .isEqualTo(SyncBadge.Uploading(12))
        assertThat(SyncBadge.of(true, SyncStatus(pending = 4))).isEqualTo(SyncBadge.Waiting(4))
        assertThat(SyncBadge.of(true, SyncStatus(problem = SyncProblem.OFFLINE, uploadsPending = 2)))
            .isEqualTo(SyncBadge.Offline)
        assertThat(SyncBadge.of(true, SyncStatus(problem = SyncProblem.UNAUTHORIZED))).isEqualTo(SyncBadge.Problem)
    }

    @Test
    fun `the sheet's groups and the active chips`() {
        val indexing = card.copy(id = 2, name = "Phone", indexing = IndexProgress(IndexPhase.LISTING, 1, 0))
        val counts = FilterCounts(fromApps = 0, serverOnly = 0, picked = 1)
        val unpaired = filterGroups(PhotoFilters.NONE, listOf(card, indexing), counts, paired = false)
        // No hand-offs, no Server photos, not paired: folders only in Source, and no Sync group.
        assertThat(unpaired.first().options.map { it.token }).containsExactly("f:1", "f:2").inOrder()
        assertThat(unpaired.map { it.title }).doesNotContain(R.string.library_filter_group_sync)
        val paired = filterGroups(PhotoFilters.NONE, listOf(card), counts, paired = true)
        assertThat(paired.first().options.map { it.token }).containsExactly("f:1", "server").inOrder()
        assertThat(paired.last().options.map { it.token }).containsExactly("s:SYNCED", "s:NOT_SYNCED").inOrder()

        val filters = PhotoFilters.folder(1).toggle(SourceFilter.Folder(99)).toggle(CullingFilter.PICKED)
            .toggle(SyncFilter.SYNCED)
        val active = activeOptions(filters, listOf(card), paired = true)
        assertThat(active.map { it.token }).containsExactly("f:99", "f:1", "c:PICKED", "s:SYNCED").inOrder()
        assertThat(active.first { it.token == "c:PICKED" }.toggle(filters).culling).isEmpty()
    }
}
