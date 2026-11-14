package dev.hfps.reveal.designsystem.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
@Suppress("MultipleEmitters", "LongMethod", "ktlint:compose:multiple-emitters-check")
internal fun ControlsPage() {
    var chip by remember { mutableIntStateOf(0) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    Section("Buttons") {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton("Export", onClick = {}, icon = PhosphorRegular.Export)
            SecondaryButton("Save preset", onClick = {})
            GhostButton("Cancel", onClick = {})
            PrimaryButton("Disabled", onClick = {}, enabled = false)
        }
    }
    Section("Icon buttons") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RevealIconButton(PhosphorRegular.ArrowUUpLeft, "Undo", onClick = {})
            RevealIconButton(PhosphorRegular.ArrowUUpRight, "Redo", onClick = {})
            RevealIconButton(PhosphorRegular.CircleHalf, "Before and after", onClick = {})
            RevealIconButton(
                icon = PhosphorFill.SlidersHorizontal,
                contentDescription = "Adjustments",
                onClick = {},
                active = true,
            )
        }
    }
    Section("Chips") {
        val labels =
            listOf("Original", "1:1", "4:5", "16:9")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                RevealChip(label, selected = index == chip, onClick = { chip = index })
            }
        }
    }
    Section("Bottom sheet") {
        SecondaryButton("Open the sheet", onClick = { sheetOpen = true })
    }
    Column {
        Section("List") {}
        RevealListRow(
            title = "Library",
            subtitle = "3 folders · 1,284 photos",
            icon = PhosphorRegular.Images,
            onClick = {},
            trailing = { CaretRight() },
        )
        RevealRowDivider()
        RevealListRow(
            title = "Theme",
            icon = PhosphorRegular.Sun,
            onClick = {},
            trailing = { Value("System") },
        )
        RevealRowDivider()
        RevealListRow(
            title = "Export defaults",
            subtitle = "JPEG · 92 % · long edge 4096 px",
            icon = PhosphorRegular.Export,
            onClick = {},
            trailing = { CaretRight() },
        )
        RevealRowDivider()
        RevealListRow(
            title = "About",
            icon = PhosphorRegular.Info,
            onClick = {},
            trailing = { CaretRight() },
        )
    }
    if (sheetOpen) {
        RevealBottomSheet(
            onDismissRequest = { sheetOpen = false },
            sheetState = rememberModalBottomSheetState(),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    "A form or chooser: drag handle, 16 dp radius, the card surface.",
                    style = RevealTheme.typography.body,
                )
                PrimaryButton(
                    text = "Done",
                    onClick = { sheetOpen = false },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun CaretRight() {
    Icon(
        imageVector = PhosphorRegular.CaretRight,
        contentDescription = null,
        tint = RevealTheme.colors.mutedForeground,
    )
}

@Composable
private fun Value(text: String) {
    Text(text = text, style = RevealTheme.typography.value, color = RevealTheme.colors.mutedForeground)
}
