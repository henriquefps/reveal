package dev.hfps.reveal.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.ui.gallery.DesignSystemGallery

@Composable
fun RevealNavHost(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = StartRoute, modifier = modifier) {
        // The design-system gallery is the start destination until the library (M1) replaces it.
        composable<StartRoute> {
            DesignSystemGallery(themeMode = themeMode, onThemeModeChange = onThemeModeChange)
        }
    }
}
