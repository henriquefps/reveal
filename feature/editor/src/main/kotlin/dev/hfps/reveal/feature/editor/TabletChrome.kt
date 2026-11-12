package dev.hfps.reveal.feature.editor

import dev.hfps.reveal.feature.editor.canvas.PhotoController
import dev.hfps.reveal.feature.editor.layout.EditorChrome
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.feature.editor.tools.EditorTool

/** What every part of the tablet layout reads (one holder instead of seven parameters). */
internal class TabletChrome(
    val state: EditorUiState,
    val actions: EditorActions,
    val photo: PhotoController,
    val chrome: EditorChrome,
    val host: PanelHost,
    val select: (EditorTool) -> Unit,
    val onToggleChrome: () -> Unit,
)
