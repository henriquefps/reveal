package dev.hfps.reveal.feature.editor.layout

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The window -> layout rule of docs/adr/0016 ("Docked panel"). */
class EditorLayoutKindTest {
    private fun of(w: Int, h: Int) = EditorLayoutKind.of(w.dp, h.dp)

    @Test
    fun phonesKeepTheirLayouts() {
        assertThat(of(390, 844)).isEqualTo(EditorLayoutKind.PORTRAIT)
        assertThat(of(844, 390)).isEqualTo(EditorLayoutKind.LANDSCAPE)
        // A small tablet in portrait is narrower than the tablet layouts: the phone's portrait.
        assertThat(of(800, 1280)).isEqualTo(EditorLayoutKind.PORTRAIT)
    }

    @Test
    fun tabletsDockThePanelInPortraitOnly() {
        // Galaxy Tab S9 (1495 x 934 dp) and Tab S9 Ultra (1690 x 1056 dp at its default density).
        assertThat(of(1495, 934)).isEqualTo(EditorLayoutKind.TABLET)
        assertThat(of(934, 1495)).isEqualTo(EditorLayoutKind.TABLET_DOCKED)
        assertThat(of(1690, 1056)).isEqualTo(EditorLayoutKind.TABLET)
        assertThat(of(1056, 1690)).isEqualTo(EditorLayoutKind.TABLET_DOCKED)
    }

    @Test
    fun aFoldablesAlmostSquareInnerScreenDocksEitherWay() {
        // A 3:2 photo: 466 x 311 dp docked against 364 x 243 beside the column; turned, 420 x 280 against 395 x 263.
        assertThat(of(852, 883)).isEqualTo(EditorLayoutKind.TABLET_DOCKED)
        assertThat(of(883, 852)).isEqualTo(EditorLayoutKind.TABLET_DOCKED)
        // Clearly wider than tall (from about 1.1 : 1 at 900 dp high) the column wins again.
        assertThat(of(1280, 900)).isEqualTo(EditorLayoutKind.TABLET)
    }

    @Test
    fun theDockedPanelLeavesThePhotoItsMinimumHeight() {
        val range = EditorLayoutKind.dockRange(1495.dp)
        assertThat(range.start).isEqualTo(EditorLayoutKind.DOCK_MIN)
        assertThat(range.endInclusive).isEqualTo(EditorLayoutKind.DOCK_MAX)
        // 883 - 64 - 76 - 72 - 320 = 351 dp
        assertThat(EditorLayoutKind.dockRange(883.dp).endInclusive.value).isWithin(0.5f).of(351f)
        // Never below the minimum, however short the window.
        assertThat(EditorLayoutKind.dockRange(500.dp).endInclusive).isEqualTo(EditorLayoutKind.DOCK_MIN)
    }
}
