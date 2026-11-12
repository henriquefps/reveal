package dev.hfps.reveal.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.editor.editorScreen
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.library.LibraryRoute
import dev.hfps.reveal.feature.library.libraryScreens
import dev.hfps.reveal.feature.settings.SettingsRoute
import dev.hfps.reveal.feature.settings.settingsScreen
import dev.hfps.reveal.ui.about.AboutScreen
import dev.hfps.reveal.ui.gallery.DesignSystemGallery

/** The app's navigation: the library is the start (M1); the editor, the gallery and About hang off it. */
@Composable
fun RevealNavHost(
    navController: NavHostController,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onEditorBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavHost(navController = navController, startDestination = LibraryRoute(), modifier = modifier) {
        libraryScreens(
            navController = navController,
            onEdit = { versionId, strip -> navController.navigateToEditor(versionId, strip) },
            onOpenFile = { uri -> navController.navigateToEditor(uri.toString()) },
            onGallery = { navController.navigate(GalleryRoute) },
            onAbout = { navController.navigate(AboutRoute) },
            onSettings = { navController.navigate(SettingsRoute) },
        )
        composable<GalleryRoute> {
            DesignSystemGallery(
                themeMode = themeMode,
                onThemeModeChange = onThemeModeChange,
                onBack = { navController.popBackStack() },
            )
        }
        composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
        settingsScreen(onBack = { navController.popBackStack() })
        editorScreen(navController = navController, onBack = onEditorBack)
    }
}
