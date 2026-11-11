package dev.hfps.reveal.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.editor.editorScreen
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.ui.about.AboutScreen
import dev.hfps.reveal.ui.gallery.DesignSystemGallery
import dev.hfps.reveal.ui.start.StartScreen

@Composable
fun RevealNavHost(
    navController: NavHostController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onEditorBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = StartRoute, modifier = modifier) {
        // A temporary start screen ("Open photo") until the library (M1) replaces it.
        composable<StartRoute> {
            StartScreen(
                onOpenPhoto = { navController.navigateToEditor(it.toString()) },
                onGallery = { navController.navigate(GalleryRoute) },
                onAbout = { navController.navigate(AboutRoute) },
            )
        }
        composable<GalleryRoute> {
            DesignSystemGallery(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
        editorScreen(onBack = onEditorBack)
    }
}
