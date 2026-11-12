package dev.hfps.reveal.feature.library

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import org.junit.Test

class LibraryStateTest {
    private fun row(id: Long, at: Long, title: String?) = PhotoRow(
        id = id,
        sourceId = 1,
        name = "p$id.jpg",
        format = PhotoFormat.JPEG,
        sortTime = 0,
        captureTime = null,
        rating = 0,
        label = null,
        flag = PickFlag.NONE,
        hasEdit = false,
        versionId = id,
        editTime = null,
        handoffAt = at,
        handoffTitle = title,
    )

    @Test
    fun `hand-offs get a header per batch and no date headers`() {
        val rows = listOf(row(1, 20, "Culling"), row(2, 20, "Culling"), row(3, 10, null))
        val entries = buildGridEntries(rows, PhotoQuery(scope = LibraryScope.FromApps))
        assertThat(entries.map { it::class.simpleName })
            .containsExactly("Group", "Photo", "Photo", "Group", "Photo").inOrder()
        assertThat((entries[0] as GridEntry.Group).title).isEqualTo("Culling")
        assertThat((entries[3] as GridEntry.Group).title).isNull()
    }

    @Test
    fun `the hand-off source maps to its own scope`() {
        assertThat(LibrarySelection.Special(SpecialSource.FROM_APPS).scope()).isEqualTo(LibraryScope.FromApps)
    }

    @Test
    fun `a library without folders starts on the photos other apps handed over`() {
        val fromApps = LibrarySelection.Special(SpecialSource.FROM_APPS)
        assertThat(LibraryViewModel.startSelection(null, folders = 0, fromApps = 3)).isEqualTo(fromApps)
        // Nothing handed over: the empty library; a folder: the sources list.
        assertThat(LibraryViewModel.startSelection(null, folders = 0, fromApps = 0)).isNull()
        assertThat(LibraryViewModel.startSelection(null, folders = 1, fromApps = 3)).isNull()
        // The last selection is always reopened.
        val recent = LibrarySelection.Special(SpecialSource.RECENT)
        assertThat(LibraryViewModel.startSelection(recent, folders = 0, fromApps = 3)).isEqualTo(recent)
    }
}
