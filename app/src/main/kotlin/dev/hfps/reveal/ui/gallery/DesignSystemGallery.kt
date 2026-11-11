@file:Suppress("MatchingDeclarationName")

package dev.hfps.reveal.ui.gallery

import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.ThemeMode

/** The pages of the gallery; the editor page renders in the always-dark darkroom theme. */
enum class GalleryPage(@param:StringRes val label: Int) {
    Foundations(R.string.gallery_page_foundations),
    Icons(R.string.gallery_page_icons),
    Controls(R.string.gallery_page_controls),
    Editor(R.string.gallery_page_editor),
}

/**
 * Proves the design system: tokens, type scale, Phosphor icons, controls and the editor surface, inside the hfps
 * shell. Reachable from the start screen's menu.
 */
@Composable
fun DesignSystemGallery(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    modifier: Modifier = Modifier,
    initialPage: GalleryPage = GalleryPage.Foundations,
    onBack: () -> Unit = {},
) {
    var page by rememberSaveable { mutableStateOf(initialPage) }
    if (page == GalleryPage.Editor) {
        EditorTheme { GalleryShell(page, { page = it }, themeMode, onThemeModeChange, onBack, modifier) }
    } else {
        GalleryShell(page, { page = it }, themeMode, onThemeModeChange, onBack, modifier)
    }
}

@Composable
private fun GalleryShell(
    page: GalleryPage,
    onPageChange: (GalleryPage) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RevealScaffold(
        modifier = modifier,
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.gallery_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.gallery_back),
                actions = {
                    RevealIconButton(
                        icon = PhosphorRegular.DotsThree,
                        contentDescription = stringResource(R.string.gallery_more),
                        onClick = {},
                    )
                },
            )
        },
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GalleryPage.entries.forEach {
                    RevealChip(
                        label = stringResource(it.label),
                        selected = it == page,
                        onClick = { onPageChange(it) },
                    )
                }
            }
            if (page == GalleryPage.Editor) {
                EditorSurfaceSample(Modifier.weight(1f))
            } else {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    when (page) {
                        GalleryPage.Foundations -> FoundationsPage(themeMode, onThemeModeChange)
                        GalleryPage.Icons -> IconsPage()
                        else -> ControlsPage()
                    }
                }
            }
        }
    }
}
