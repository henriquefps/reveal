package dev.hfps.reveal.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.index.IndexPhase
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.PhotoFilter
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.NumberFormat

/** Width from which the library shows the sources and the grid side by side (`docs/plan/ux.md`). */
val TwoPaneMinWidth: Dp = 840.dp
private val SourcesPaneWidth = 320.dp
private const val MAX_STARS = 5

/**
 * The library screen without its ViewModel (screenshots, tests): the hfps shell with the sources list and the grid
 * (side by side from [TwoPaneMinWidth], one at a time below it).
 */
@Composable
fun LibraryContent(
    state: LibraryUiState,
    actions: LibraryActions,
    thumbnails: ThumbnailSource,
    modifier: Modifier = Modifier,
    snackbar: SnackbarHostState = remember { SnackbarHostState() },
    onGridDraw: (Int) -> Unit = {},
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val wide = maxWidth >= TwoPaneMinWidth
        RevealScaffold(topBar = { LibraryTopBar(state, actions, wide) }) {
            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    SourcesPane(
                        state.sources,
                        state.selection,
                        actions,
                        Modifier.width(SourcesPaneWidth).fillMaxHeight(),
                    )
                    VerticalDivider(color = RevealTheme.colors.hairline)
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        if (state.selection == null) {
                            if (state.sources.isNotEmpty()) {
                                EmptyState(
                                    PhosphorRegular.Images,
                                    stringResource(R.string.library_title),
                                    stringResource(R.string.library_pick_source),
                                )
                            }
                        } else {
                            GridPane(state, actions, thumbnails, onGridDraw)
                        }
                    }
                }
            } else if (state.selection == null) {
                if (state.sourcesLoaded) SourcesPane(state.sources, null, actions)
            } else {
                GridPane(state, actions, thumbnails, onGridDraw)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        val pasteGroups = state.pasteChooser
        val copied = state.clipboard
        if (pasteGroups != null && copied != null) {
            PasteEditsSheet(copied, pasteGroups, state.selected.size, actions)
        }
    }
}

// ---------------------------------------------------------------------- top bars

@Composable
private fun LibraryTopBar(state: LibraryUiState, actions: LibraryActions, wide: Boolean) {
    when {
        state.selecting -> SelectionTopBar(state, actions)

        state.searchOpen -> SearchTopBar(state.query, actions)

        else -> {
            val showBack = !wide && state.selection != null
            RevealTopBar(
                title = selectionTitle(state, wide),
                onBack = if (showBack) ({ actions.onSelect(null) }) else null,
                backContentDescription = stringResource(R.string.library_back),
                actions = {
                    if (state.selection?.scope() != null) {
                        RevealIconButton(
                            icon = PhosphorRegular.MagnifyingGlass,
                            contentDescription = stringResource(R.string.library_search),
                            onClick = { actions.onSearchOpen(true) },
                        )
                        SortMenu(state.query, actions)
                    }
                    OverflowMenu(actions)
                },
            )
        }
    }
}

@Composable
private fun selectionTitle(state: LibraryUiState, wide: Boolean): String = when (val s = state.selection) {
    null -> stringResource(R.string.library_title)

    is LibrarySelection.Folder -> state.currentSource?.name ?: stringResource(R.string.library_title)

    is LibrarySelection.Special -> if (wide && s.which == SpecialSource.ALL) {
        stringResource(R.string.library_title)
    } else {
        stringResource(specialName(s.which))
    }
}

private fun specialName(which: SpecialSource) = when (which) {
    SpecialSource.ALL -> R.string.library_all_photos
    SpecialSource.RECENT -> R.string.library_recent
    SpecialSource.FROM_APPS -> R.string.library_from_apps
}

@Composable
private fun SortMenu(query: PhotoQuery, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.SortAscending,
            contentDescription = stringResource(R.string.library_sort),
            onClick = { open = true },
        )
        DropdownMenu(open, {
            open = false
        }, containerColor = RevealTheme.colors.card, shape = RevealTheme.shapes.control) {
            listOf(
                PhotoSort.CAPTURE_NEWEST to R.string.library_sort_newest,
                PhotoSort.CAPTURE_OLDEST to R.string.library_sort_oldest,
                PhotoSort.NAME to R.string.library_sort_name,
            ).forEach { (sort, label) ->
                MenuItem(if (query.sort == sort) PhosphorRegular.Check else null, stringResource(label)) {
                    open = false
                    actions.onQuery(query.copy(sort = sort))
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.library_menu),
            onClick = { open = true },
            modifier = Modifier.testTag(LibraryTags.MENU),
        )
        DropdownMenu(open, {
            open = false
        }, containerColor = RevealTheme.colors.card, shape = RevealTheme.shapes.control) {
            MenuItem(PhosphorRegular.FolderPlus, stringResource(R.string.library_add_folder)) {
                open = false
                actions.onAddFolder()
            }
            MenuItem(PhosphorRegular.Image, stringResource(R.string.library_menu_open_photo)) {
                open = false
                actions.onOpenPhoto()
            }
            MenuItem(PhosphorRegular.Palette, stringResource(R.string.library_menu_gallery)) {
                open = false
                actions.onGallery()
            }
            MenuItem(PhosphorRegular.Info, stringResource(R.string.library_menu_about)) {
                open = false
                actions.onAbout()
            }
        }
    }
}

@Composable
private fun SearchTopBar(query: PhotoQuery, actions: LibraryActions) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RevealIconButton(
            icon = PhosphorRegular.ArrowLeft,
            contentDescription = stringResource(R.string.library_search_close),
            onClick = { actions.onSearchOpen(false) },
        )
        TextField(
            value = query.search,
            onValueChange = { actions.onQuery(query.copy(search = it)) },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.library_search_hint), style = RevealTheme.typography.body) },
            textStyle = RevealTheme.typography.body.copy(color = RevealTheme.colors.foreground),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = RevealTheme.colors.primary,
                unfocusedIndicatorColor = RevealTheme.colors.input,
                cursorColor = RevealTheme.colors.primary,
                focusedPlaceholderColor = RevealTheme.colors.mutedForeground,
                unfocusedPlaceholderColor = RevealTheme.colors.mutedForeground,
            ),
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp)
                .focusRequester(focus)
                .testTag(LibraryTags.SEARCH_FIELD),
        )
    }
}

@Composable
private fun SelectionTopBar(state: LibraryUiState, actions: LibraryActions) {
    val count = state.selected.size
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp)
            .testTag(LibraryTags.SELECTION_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RevealIconButton(
            icon = PhosphorRegular.X,
            contentDescription = stringResource(R.string.library_selection_close),
            onClick = { actions.onSelectionChange(emptySet()) },
        )
        Text(
            text = pluralStringResource(R.plurals.library_selected, count, count),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
        )
        RatingMenu(actions)
        RevealIconButton(
            icon = PhosphorRegular.CheckCircle,
            contentDescription = stringResource(R.string.library_action_pick),
            onClick = { actions.onFlag(PickFlag.PICK) },
        )
        RevealIconButton(
            icon = PhosphorRegular.XCircle,
            contentDescription = stringResource(R.string.library_action_reject),
            onClick = { actions.onFlag(PickFlag.REJECT) },
        )
        SelectionOverflow(state, actions)
    }
}

@Composable
private fun RatingMenu(actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.Star,
            contentDescription = stringResource(R.string.library_action_rate),
            onClick = { open = true },
        )
        DropdownMenu(open, {
            open = false
        }, containerColor = RevealTheme.colors.card, shape = RevealTheme.shapes.control) {
            for (stars in MAX_STARS downTo 1) {
                MenuItem(PhosphorFill.Star, pluralStringResource(R.plurals.library_stars, stars, stars)) {
                    open = false
                    actions.onRate(stars)
                }
            }
            MenuItem(PhosphorRegular.Star, stringResource(R.string.library_rating_none)) {
                open = false
                actions.onRate(0)
            }
        }
    }
}

/** A [MenuItem] with a test tag (instrumented tests find it in any language). */
@Composable
private fun TaggedMenuItem(icon: ImageVector, text: String, tag: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.testTag(tag)) { MenuItem(icon, text, enabled, onClick) }
}

@Suppress("LongMethod")
@Composable
private fun SelectionOverflow(state: LibraryUiState, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.library_menu),
            onClick = { open = true },
        )
        DropdownMenu(open, {
            open = false
        }, containerColor = RevealTheme.colors.card, shape = RevealTheme.shapes.control) {
            MenuItem(PhosphorRegular.SelectionAll, stringResource(R.string.library_selection_all)) {
                open = false
                actions.onSelectionChange(state.photoIds.toSet())
            }
            MenuItem(PhosphorRegular.Minus, stringResource(R.string.library_action_unflag)) {
                open = false
                actions.onFlag(PickFlag.NONE)
            }
            MenuItem(PhosphorRegular.Trash, stringResource(R.string.library_action_remove)) {
                open = false
                actions.onRemovePhotos()
            }
            TaggedMenuItem(
                PhosphorRegular.Copy,
                stringResource(R.string.library_action_copy_edits),
                LibraryTags.COPY_EDITS,
                enabled = state.selected.size == 1,
            ) {
                open = false
                actions.onCopyEdits()
            }
            TaggedMenuItem(
                PhosphorRegular.ClipboardText,
                stringResource(R.string.library_action_paste_edits_choose),
                LibraryTags.PASTE_EDITS,
                enabled = state.clipboard != null,
            ) {
                open = false
                actions.onPasteEdits()
            }
            TaggedMenuItem(
                PhosphorRegular.ArrowCounterClockwise,
                stringResource(R.string.library_action_reset_edits),
                LibraryTags.RESET_EDITS,
            ) {
                open = false
                actions.onResetEdits()
            }
            // Placeholders until their workstreams land (presets, export).
            val coming = stringResource(R.string.library_action_coming)
            MenuItem(
                PhosphorRegular.Sparkle,
                "${stringResource(R.string.library_action_preset)} · $coming",
                enabled = false,
            ) {
            }
            MenuItem(
                PhosphorRegular.Export,
                "${stringResource(R.string.library_action_export)} · $coming",
                enabled = false,
            ) {
            }
        }
    }
}

// ---------------------------------------------------------------------- grid pane

/** The empty grid of a folder, or of "Opened from other apps" before anything arrived. */
@Composable
private fun EmptyGrid(selection: LibrarySelection?) {
    if (selection == LibrarySelection.Special(SpecialSource.FROM_APPS)) {
        EmptyState(
            PhosphorRegular.ArrowSquareIn,
            stringResource(R.string.library_from_apps_empty_title),
            stringResource(R.string.library_from_apps_empty_body),
        )
    } else {
        EmptyState(
            PhosphorRegular.Images,
            stringResource(R.string.library_folder_empty_title),
            stringResource(R.string.library_folder_empty_body),
        )
    }
}

@Composable
private fun GridPane(
    state: LibraryUiState,
    actions: LibraryActions,
    thumbnails: ThumbnailSource,
    onGridDraw: (Int) -> Unit,
) {
    val source = state.currentSource
    Column(Modifier.fillMaxSize()) {
        source?.let { SourceBanner(it) }
        if (state.photoCount > 0 || state.filtered) FilterRow(state.query, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val selection = state.selection
            when {
                !state.photosLoaded -> Unit

                state.photoCount == 0 && state.filtered -> EmptyState(
                    PhosphorRegular.FunnelSimple,
                    stringResource(R.string.library_no_match_title),
                    stringResource(R.string.library_no_match_body),
                ) {
                    GhostButton(stringResource(R.string.library_clear_filters), {
                        actions.onQuery(state.query.copy(filter = PhotoFilter.ALL, search = ""))
                    })
                }

                state.photoCount == 0 && selection == LibrarySelection.Special(SpecialSource.RECENT) -> EmptyState(
                    PhosphorRegular.ClockCounterClockwise,
                    stringResource(R.string.library_recent_empty_title),
                    stringResource(R.string.library_recent_empty_body),
                )

                state.photoCount == 0 && source?.indexing != null -> EmptyState(
                    PhosphorRegular.MagnifyingGlass,
                    stringResource(R.string.library_folder_indexing_title),
                    stringResource(R.string.library_source_indexing),
                )

                state.photoCount == 0 -> EmptyGrid(selection)

                else -> PhotoGrid(
                    entries = state.entries,
                    selected = state.selected,
                    thumbnails = thumbnails,
                    onOpen = actions.onOpen,
                    onSelectionChange = actions.onSelectionChange,
                    onDraw = onGridDraw,
                )
            }
        }
    }
}

/** Indexing progress, unplugged or lost access: one line above the grid. */
@Composable
private fun SourceBanner(source: SourceItem) {
    val colors = RevealTheme.colors
    val indexing = source.indexing
    when {
        source.state == SourceState.UNPLUGGED -> BannerText(
            stringResource(
                R.string.library_unplugged_banner,
                source.volumeName ?: stringResource(R.string.library_unplugged_volume),
            ),
            colors.warning,
        )

        source.state == SourceState.PERMISSION_LOST -> BannerText(
            stringResource(R.string.library_permission_banner),
            colors.destructive,
        )

        indexing != null -> Column(Modifier.fillMaxWidth()) {
            val format = NumberFormat.getIntegerInstance()
            val text = if (indexing.phase == IndexPhase.METADATA) {
                stringResource(
                    R.string.library_indexing_metadata,
                    format.format(indexing.done),
                    format.format(indexing.total),
                )
            } else {
                stringResource(R.string.library_indexing, format.format(indexing.done))
            }
            if (indexing.total > 0) {
                LinearProgressIndicator(
                    progress = { indexing.done.toFloat() / indexing.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = colors.primary,
                    trackColor = colors.muted,
                    drawStopIndicator = {},
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth(), color = colors.primary, trackColor = colors.muted)
            }
            Text(
                text,
                style = RevealTheme.typography.ui,
                color = colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun BannerText(text: String, tint: Color) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(PhosphorRegular.Warning, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Text(
            text,
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun FilterRow(query: PhotoQuery, actions: LibraryActions) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        fun set(filter: PhotoFilter) = actions.onQuery(query.copy(filter = filter))
        RevealChip(stringResource(R.string.library_filter_all), query.filter == PhotoFilter.ALL, {
            set(PhotoFilter.ALL)
        })
        RevealChip(stringResource(R.string.library_filter_edited), query.filter == PhotoFilter.EDITED, {
            set(PhotoFilter.EDITED)
        })
        RevealChip(
            stringResource(R.string.library_filter_not_edited),
            query.filter == PhotoFilter.NOT_EDITED,
            { set(PhotoFilter.NOT_EDITED) },
        )
        RevealChip(stringResource(R.string.library_filter_picked), query.filter == PhotoFilter.PICKED, {
            set(PhotoFilter.PICKED)
        })
        RatedChip(query, actions)
        RevealChip(stringResource(R.string.library_filter_raw), query.filter == PhotoFilter.RAW, {
            set(PhotoFilter.RAW)
        })
        RevealChip(stringResource(R.string.library_filter_jpeg), query.filter == PhotoFilter.JPEG, {
            set(PhotoFilter.JPEG)
        })
    }
}

@Composable
private fun RatedChip(query: PhotoQuery, actions: LibraryActions) {
    var open by remember { mutableStateOf(false) }
    val selected = query.filter == PhotoFilter.RATED
    Box {
        RevealChip(
            label = if (selected) {
                stringResource(R.string.library_filter_rated, query.minRating)
            } else {
                stringResource(R.string.library_filter_rated_short)
            },
            selected = selected,
            onClick = { open = true },
            icon = PhosphorFill.Star,
        )
        DropdownMenu(open, {
            open = false
        }, containerColor = RevealTheme.colors.card, shape = RevealTheme.shapes.control) {
            for (stars in 1..MAX_STARS) {
                MenuItem(
                    if (selected &&
                        query.minRating == stars
                    ) {
                        PhosphorRegular.Check
                    } else {
                        null
                    },
                    stringResource(R.string.library_filter_rated, stars),
                ) {
                    open = false
                    actions.onQuery(query.copy(filter = PhotoFilter.RATED, minRating = stars))
                }
            }
        }
    }
}
