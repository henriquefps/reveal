package dev.hfps.reveal.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The hfps tokens that Material 3's `ColorScheme` has no slot for, plus the ones screens use by their hfps names.
 * Read with [RevealTheme.colors]. All values are tokens from [RevealTokens] (or [EditorNeutrals]); never put a raw
 * colour in a screen.
 */
@Immutable
data class RevealColors(
    /** Chrome behind the top bar and around the card (`--background`). */
    val background: Color,
    val foreground: Color,
    /** The one raised content surface (`--card`). */
    val card: Color,
    val cardForeground: Color,
    val primary: Color,
    val primaryForeground: Color,
    val secondary: Color,
    val secondaryForeground: Color,
    val muted: Color,
    /** Secondary text; passes WCAG AA on background, card and muted. */
    val mutedForeground: Color,
    val accent: Color,
    val accentForeground: Color,
    /** Failed / destructive status. */
    val destructive: Color,
    /** Attention status: clipping, high usage, paused. */
    val warning: Color,
    /** The hfps hairline border (`--border`). */
    val border: Color,
    /** Same value as [border]; the name to use for a 1dp card or divider line. */
    val hairline: Color,
    val input: Color,
    /** Focus ring. */
    val ring: Color,
    val isDark: Boolean,
)

@Suppress("CompositionLocalAllowlist")
internal val LocalRevealColors = staticCompositionLocalOf<RevealColors> {
    error("RevealTheme (or EditorTheme) is missing: no RevealColors provided")
}

internal fun lightRevealColors(wideGamut: Boolean): RevealColors = with(RevealTokens.Light) {
    RevealColors(
        background = background,
        foreground = foreground,
        card = card,
        cardForeground = cardForeground,
        primary = if (wideGamut) primaryP3 else primary,
        primaryForeground = primaryForeground,
        secondary = secondary,
        secondaryForeground = secondaryForeground,
        muted = muted,
        mutedForeground = mutedForeground,
        accent = accent,
        accentForeground = accentForeground,
        destructive = destructive,
        warning = if (wideGamut) warningP3 else warning,
        border = border,
        hairline = border,
        input = input,
        ring = if (wideGamut) ringP3 else ring,
        isDark = false,
    )
}

internal fun darkRevealColors(wideGamut: Boolean): RevealColors = with(RevealTokens.Dark) {
    RevealColors(
        background = background,
        foreground = foreground,
        card = card,
        cardForeground = cardForeground,
        primary = if (wideGamut) primaryP3 else primary,
        primaryForeground = primaryForeground,
        secondary = secondary,
        secondaryForeground = secondaryForeground,
        muted = muted,
        mutedForeground = mutedForeground,
        accent = accent,
        accentForeground = accentForeground,
        destructive = destructive,
        warning = warning,
        border = border,
        hairline = border,
        input = input,
        ring = if (wideGamut) ringP3 else ring,
        isDark = true,
    )
}

/**
 * The editor darkroom: always dark, and every neutral has **zero chroma** (R = G = B) so the surround never biases
 * colour judgement. Only the accent (blue, from the dark tokens) and the status colours carry hue. Contrast of
 * text on the panel card is checked in `EditorNeutralsTest`.
 *
 * | Role | Value |
 * | --- | --- |
 * | Photo surround (`background`) | `#1C1C1C` |
 * | Panel card (`card`) | `#262626` |
 * | Raised control fill (`secondary`, `muted`, `accent`) | `#333333` |
 * | Text (`foreground`) | `#F2F2F2` |
 * | Secondary text (`mutedForeground`) | `#A6A6A6` |
 * | Hairline border | white at 10 % |
 * | Input border | white at 15 % |
 */
object EditorNeutrals {
    val Surround = Color(0xFF1C1C1C)
    val Panel = Color(0xFF262626)
    val Raised = Color(0xFF333333)
    val Text = Color(0xFFF2F2F2)
    val TextMuted = Color(0xFFA6A6A6)
    val Hairline = Color.White.copy(alpha = 0.10f)
    val InputBorder = Color.White.copy(alpha = 0.15f)

    /** The opaque neutrals, for the zero-chroma test. */
    val Opaque: List<Color> = listOf(Surround, Panel, Raised, Text, TextMuted)
}

internal fun editorRevealColors(wideGamut: Boolean): RevealColors {
    val dark = darkRevealColors(wideGamut)
    return dark.copy(
        background = EditorNeutrals.Surround,
        foreground = EditorNeutrals.Text,
        card = EditorNeutrals.Panel,
        cardForeground = EditorNeutrals.Text,
        secondary = EditorNeutrals.Raised,
        secondaryForeground = EditorNeutrals.Text,
        muted = EditorNeutrals.Raised,
        mutedForeground = EditorNeutrals.TextMuted,
        accent = EditorNeutrals.Raised,
        accentForeground = EditorNeutrals.Text,
        border = EditorNeutrals.Hairline,
        hairline = EditorNeutrals.Hairline,
        input = EditorNeutrals.InputBorder,
    )
}
