package dev.hfps.reveal.feature.editor

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Adds the editor destination; [onBack] leaves it. Moving along the filmstrip replaces the editor's entry. */
fun NavGraphBuilder.editorScreen(navController: NavController, onBack: () -> Unit) {
    composable<EditorRoute> {
        EditorScreen(
            onBack = onBack,
            onSwitch = { route ->
                navController.navigate(route) {
                    popUpTo<EditorRoute> { inclusive = true }
                }
            },
        )
    }
}

/** Opens the editor on [uri] (a content URI string from another app or the picker). */
fun NavController.navigateToEditor(uri: String) = navigate(EditorRoute(uri = uri))

/**
 * Opens the editor on a library photo's version; [strip] (a `PhotoListCodec` query) gives the tablet filmstrip its
 * photos.
 */
fun NavController.navigateToEditor(versionId: Long, strip: String? = null) =
    navigate(EditorRoute(versionId = versionId, strip = strip))
