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
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RevealBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
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
