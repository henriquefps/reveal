package dev.hfps.reveal.designsystem

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.EditorNeutrals
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.designsystem.theme.isDark
import org.junit.Test

class EditorNeutralsTest {
    @Test
    fun `every editor neutral has zero chroma`() {
        EditorNeutrals.Opaque.forEach { c ->
            assertThat(c.red).isEqualTo(c.green)
            assertThat(c.green).isEqualTo(c.blue)
        }
        assertThat(EditorNeutrals.Hairline.red).isEqualTo(EditorNeutrals.Hairline.blue)
    }

    @Test
    fun `panel is slightly lighter than the surround`() {
        assertThat(EditorNeutrals.Panel.luminance()).isGreaterThan(EditorNeutrals.Surround.luminance())
    }

    @Test
    fun `text passes WCAG AA on surround and panel`() {
        listOf(EditorNeutrals.Surround, EditorNeutrals.Panel, EditorNeutrals.Raised).forEach { bg ->
            assertThat(contrast(EditorNeutrals.Text, bg)).isAtLeast(4.5)
            assertThat(contrast(EditorNeutrals.TextMuted, bg)).isAtLeast(4.5)
        }
    }

    private fun contrast(a: Color, b: Color): Double {
        val l1 = a.luminance().toDouble()
        val l2 = b.luminance().toDouble()
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }
}

class ThemeModeTest {
    @Test
    fun `system follows the OS, light and dark override it`() {
        assertThat(ThemeMode.System.isDark(systemDark = true)).isTrue()
        assertThat(ThemeMode.System.isDark(systemDark = false)).isFalse()
        assertThat(ThemeMode.Light.isDark(systemDark = true)).isFalse()
        assertThat(ThemeMode.Dark.isDark(systemDark = false)).isTrue()
    }
}

class PhosphorIconsTest {
    @Test
    fun `icons are 256 unit vectors in both weights`() {
        listOf(PhosphorRegular.SlidersHorizontal, PhosphorFill.SlidersHorizontal).forEach {
            assertThat(it.viewportWidth).isEqualTo(256f)
            assertThat(it.defaultWidth.value).isEqualTo(24f)
        }
    }
}
