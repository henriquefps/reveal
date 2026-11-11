package dev.hfps.reveal.designsystem.components

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * hfps bottom sheet for forms and choosers on phones (Vaul's equivalent): drag handle, 16dp top radius, the card
 * surface. Material's sheet tracks the finger and flings with velocity; dismiss by drag, scrim tap or back.
 *
 * It opens fully expanded, with no half-height stop (`skipPartiallyExpanded`), so **one back always closes it**.
 * With a half-height stop, Material's sheet takes back from the expanded state as "collapse to half" whenever the
 * content is taller than half the window: a sheet whose content grows after it opened (the info sheet loading EXIF)
 * gains that stop while expanded, and on a shorter window (a phone at 450 dpi, a larger font) back only collapsed it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevealBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        sheetState = sheetState,
        shape = RevealTheme.shapes.sheet,
        containerColor = RevealTheme.colors.card,
        contentColor = RevealTheme.colors.cardForeground,
        dragHandle = { BottomSheetDefaults.DragHandle(color = RevealTheme.colors.mutedForeground) },
        content = content,
    )
}
