package dev.hfps.reveal.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration

/** Entry point to the hfps tokens: `RevealTheme.colors`, `.typography`, `.shapes`. */
object RevealTheme {
    val colors: RevealColors
        @Composable
        @ReadOnlyComposable
        get() = LocalRevealColors.current

    val typography: RevealTypography
        @Composable
        @ReadOnlyComposable
        get() = LocalRevealTypography.current

    val shapes: RevealShapes
        @Composable
        @ReadOnlyComposable
        get() = LocalRevealShapes.current
}

/**
 * The Reveal theme: the hfps tokens as a Material 3 `ColorScheme` plus [RevealColors], Inter typography and hfps
 * shapes. Library and settings screens use this; the editor uses [EditorTheme].
 */
@Composable
fun RevealTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val wideGamut = LocalConfiguration.current.isScreenWideColorGamut
    val colors = remember(darkTheme, wideGamut) {
        if (darkTheme) darkRevealColors(wideGamut) else lightRevealColors(wideGamut)
    }
    RevealThemeContent(colors = colors, content = content)
}

/**
 * The darkroom: always dark whatever the theme setting says, neutral grey (zero chroma) surround and panel, blue
 * accent from the dark tokens. See [EditorNeutrals] for every value.
 */
@Composable
fun EditorTheme(content: @Composable () -> Unit) {
    val wideGamut = LocalConfiguration.current.isScreenWideColorGamut
    val colors = remember(wideGamut) { editorRevealColors(wideGamut) }
    RevealThemeContent(colors = colors, content = content)
}

@Composable
private fun RevealThemeContent(colors: RevealColors, content: @Composable () -> Unit) {
    val typography = remember { revealTypography() }
    val shapes = remember { RevealShapes() }
    val materialTypography = remember(typography) { typography.toMaterial() }
    CompositionLocalProvider(
        LocalRevealColors provides colors,
        LocalRevealTypography provides typography,
        LocalRevealShapes provides shapes,
    ) {
        MaterialTheme(
            colorScheme = remember(colors) { colors.toMaterial() },
            typography = materialTypography,
            shapes = MaterialShapes,
            content = content,
        )
    }
}

private fun RevealColors.toMaterial(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = primary,
        onPrimary = primaryForeground,
        primaryContainer = accent,
        onPrimaryContainer = accentForeground,
        secondary = secondary,
        onSecondary = secondaryForeground,
        secondaryContainer = secondary,
        onSecondaryContainer = secondaryForeground,
        tertiary = primary,
        onTertiary = primaryForeground,
        background = background,
        onBackground = foreground,
        surface = card,
        onSurface = cardForeground,
        surfaceVariant = muted,
        onSurfaceVariant = mutedForeground,
        surfaceTint = primary,
        inverseSurface = foreground,
        inverseOnSurface = background,
        error = destructive,
        onError = primaryForeground,
        errorContainer = muted,
        onErrorContainer = destructive,
        outline = input,
        outlineVariant = border,
        scrim = background,
        surfaceBright = card,
        surfaceDim = background,
        surfaceContainerLowest = card,
        surfaceContainerLow = card,
        surfaceContainer = card,
        surfaceContainerHigh = card,
        surfaceContainerHighest = muted,
    )
}
