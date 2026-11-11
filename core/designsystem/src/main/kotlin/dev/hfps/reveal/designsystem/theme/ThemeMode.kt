package dev.hfps.reveal.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable

/** The user's theme choice for the library and settings. The editor ignores it (always dark). */
enum class ThemeMode {
    System,
    Light,
    Dark,
}

/** Whether this mode renders dark, given the system setting. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

@Composable
fun ThemeMode.isDark(): Boolean = isDark(systemDark = isSystemInDarkTheme())
