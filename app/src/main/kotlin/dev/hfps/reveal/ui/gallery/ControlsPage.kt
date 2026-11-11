package dev.hfps.reveal.ui.gallery

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
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
internal fun ControlsPage() {
    var chip by remember { mutableIntStateOf(0) }
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    Section(R.string.gallery_section_buttons) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PrimaryButton(stringResource(R.string.button_primary), onClick = {}, icon = PhosphorRegular.Export)
            SecondaryButton(stringResource(R.string.button_secondary), onClick = {})
            GhostButton(stringResource(R.string.button_ghost), onClick = {})
            PrimaryButton(stringResource(R.string.button_disabled), onClick = {}, enabled = false)
        }
    }
    Section(R.string.gallery_section_icon_buttons) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            RevealIconButton(PhosphorRegular.ArrowUUpLeft, stringResource(R.string.action_undo), onClick = {})
            RevealIconButton(PhosphorRegular.ArrowUUpRight, stringResource(R.string.action_redo), onClick = {})
            RevealIconButton(PhosphorRegular.CircleHalf, stringResource(R.string.action_before_after), onClick = {})
            RevealIconButton(
                icon = PhosphorFill.SlidersHorizontal,
                contentDescription = stringResource(R.string.action_adjustments),
                onClick = {},
                active = true,
            )
        }
    }
    Section(R.string.gallery_section_chips) {
        val labels = listOf(R.string.chip_original, R.string.chip_square, R.string.chip_portrait, R.string.chip_landscape)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            labels.forEachIndexed { index, label ->
                RevealChip(stringResource(label), selected = index == chip, onClick = { chip = index })
            }
        }
    }
    Section(R.string.gallery_section_sheet) {
        SecondaryButton(stringResource(R.string.sheet_open), onClick = { sheetOpen = true })
    }
    Column {
        Section(R.string.gallery_section_list) {}
        RevealListRow(
            title = stringResource(R.string.row_library),
            subtitle = stringResource(R.string.row_library_summary),
            icon = PhosphorRegular.Images,
            onClick = {},
            trailing = { CaretRight() },
        )
        RevealRowDivider()
        RevealListRow(
            title = stringResource(R.string.row_theme),
            icon = PhosphorRegular.Sun,
            onClick = {},
            trailing = { Value(stringResource(R.string.theme_system)) },
        )
        RevealRowDivider()
        RevealListRow(
            title = stringResource(R.string.row_export),
            subtitle = stringResource(R.string.row_export_summary),
            icon = PhosphorRegular.Export,
            onClick = {},
            trailing = { CaretRight() },
        )
        RevealRowDivider()
        RevealListRow(
            title = stringResource(R.string.row_about),
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
                Text(stringResource(R.string.sheet_body), style = RevealTheme.typography.body)
                PrimaryButton(
                    text = stringResource(R.string.sheet_close),
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
