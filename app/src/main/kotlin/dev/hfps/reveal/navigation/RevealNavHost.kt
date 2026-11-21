package dev.hfps.reveal.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.hfps.reveal.feature.editor.editorScreen
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.library.LibraryRoute
import dev.hfps.reveal.feature.library.libraryScreens
import dev.hfps.reveal.feature.settings.SettingsLink
import dev.hfps.reveal.feature.settings.SettingsRoute
import dev.hfps.reveal.feature.settings.navigateToSettings
import dev.hfps.reveal.feature.settings.settingsScreens
import dev.hfps.reveal.ui.about.AboutScreen

/** The app's navigation: the library is the start (M1); the editor, Settings (a hub) and About hang off it. */
@Composable
fun RevealNavHost(navController: NavHostController, onEditorBack: () -> Unit, modifier: Modifier = Modifier) {
    NavHost(navController = navController, startDestination = LibraryRoute(), modifier = modifier) {
        libraryScreens(
            navController = navController,
            onEdit = { versionId, strip -> navController.navigateToEditor(versionId, strip) },
            onOpenFile = { uri -> navController.navigateToEditor(uri.toString()) },
            onAbout = { navController.navigate(AboutRoute) },
            onSettings = { navController.navigate(SettingsRoute) },
            onSync = { navController.navigateToSettings(SettingsLink.SYNC_STATUS) },
        )
        composable<AboutRoute> { AboutScreen(onBack = { navController.popBackStack() }) }
        settingsScreens(navController, onAbout = { navController.navigate(AboutRoute) })
        editorScreen(navController = navController, onBack = onEditorBack)
    }
}
