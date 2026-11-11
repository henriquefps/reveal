package dev.hfps.reveal.feature.editor

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable

/** Adds the editor destination; [onBack] leaves it. */
fun NavGraphBuilder.editorScreen(onBack: () -> Unit) {
    composable<EditorRoute> { EditorScreen(onBack = onBack) }
}

/** Opens the editor on [uri] (a content URI string). */
fun NavController.navigateToEditor(uri: String) = navigate(EditorRoute(uri))
