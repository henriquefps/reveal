package dev.hfps.reveal.feature.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.index.IndexPhase
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.PhotoFilters
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

/**
 * Width from which the library's top bar shows search, sort and the sync status's text (`docs/plan/ux.md`); sheets
 * become centred dialogs from the same width (`DialogMinWidth`).
 */
val TwoPaneMinWidth: Dp = 840.dp
private const val MAX_STARS = 5

/**
 * The library screen without its ViewModel (screenshots, tests): the hfps shell with one home grid of every photo,
 * its filter bar, and the Recent view (FIX/L); the filter sheet and "Manage folders" open over it.
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
            when {
                !state.sourcesLoaded -> Unit
                state.libraryEmpty && !state.recent -> EmptyLibrary(actions)
                else -> GridPane(state, actions, thumbnails, onGridDraw)
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        val pasteGroups = state.pasteChooser
        val copied = state.clipboard
        if (pasteGroups != null && copied != null) {
            PasteEditsSheet(copied, pasteGroups, state.selected.size, actions)
        }
        if (state.filtersOpen) FilterSheet(state, actions)
        if (state.foldersOpen) ManageFoldersSheet(state, actions)
    }
}

// ---------------------------------------------------------------------- top bars

@Composable
private fun syncText(badge: SyncBadge): String {
    val count = NumberFormat.getIntegerInstance()
    return when (badge) {
        SyncBadge.NotPaired -> stringResource(R.string.library_sync_not_paired)
        SyncBadge.Synced -> stringResource(R.string.library_sync_synced)
        SyncBadge.Syncing -> stringResource(R.string.library_sync_syncing)
        is SyncBadge.Uploading -> stringResource(R.string.library_sync_uploading, count.format(badge.count))
        is SyncBadge.Waiting -> stringResource(R.string.library_sync_waiting, count.format(badge.count))
        SyncBadge.Offline -> stringResource(R.string.library_sync_offline)
        SyncBadge.Problem -> stringResource(R.string.library_sync_problem)
    }
}

@Composable
private fun syncIcon(badge: SyncBadge): Pair<ImageVector, Color> {
    val colors = RevealTheme.colors
    return when (badge) {
        SyncBadge.NotPaired -> PhosphorRegular.CloudSlash to colors.mutedForeground
        SyncBadge.Synced -> PhosphorRegular.CloudCheck to colors.mutedForeground
        SyncBadge.Syncing -> PhosphorRegular.ArrowsClockwise to colors.primary
        is SyncBadge.Uploading -> PhosphorRegular.CloudArrowUp to colors.primary
        is SyncBadge.Waiting -> PhosphorRegular.Cloud to colors.mutedForeground
        SyncBadge.Offline -> PhosphorRegular.CloudSlash to colors.warning
        SyncBadge.Problem -> PhosphorRegular.WarningCircle to colors.destructive
    }
}

@Composable
private fun LibraryTopBar(state: LibraryUiState, actions: LibraryActions, wide: Boolean) {
    when {
        state.selecting -> SelectionTopBar(state, actions, wide)

        state.searchOpen -> SearchTopBar(state.query, actions)

        state.recent -> RevealTopBar(
            title = stringResource(R.string.library_recent),
            onBack = { actions.onRecent(false) },
            backContentDescription = stringResource(R.string.library_back),
            actions = { OverflowMenu(state, actions, wide = true, home = false) },
        )

        else -> RevealTopBar(
            title = stringResource(R.string.library_title),
            actions = {
                SyncStatus(state.syncBadge, wide, actions.onSync)
                RevealIconButton(
                    icon = PhosphorRegular.ClockCounterClockwise,
                    contentDescription = stringResource(R.string.library_recent),
                    onClick = { actions.onRecent(true) },
                    modifier = Modifier.testTag(LibraryTags.RECENT),
                )
                RevealIconButton(
                    icon = PhosphorRegular.Folder,
                    contentDescription = stringResource(R.string.library_manage_folders),
                    onClick = { actions.onFoldersOpen(true) },
                    modifier = Modifier.testTag(LibraryTags.FOLDERS),
                )
                if (wide) {
                    RevealIconButton(
                        icon = PhosphorRegular.MagnifyingGlass,
                        contentDescription = stringResource(R.string.library_search),
                        onClick = { actions.onSearchOpen(true) },
                    )
                    SortMenu(state.query, actions)
                }
                RevealIconButton(
                    icon = PhosphorRegular.Gear,
                    contentDescription = stringResource(R.string.library_menu_settings),
                    onClick = actions.onSettings,
                    modifier = Modifier.testTag(LibraryTags.SETTINGS),
                )
                OverflowMenu(state, actions, wide, home = true)
            },
        )
    }
}

/**
 * The compact sync status (FIX/L): an icon on phones (with the count while uploading), icon and text on wide windows;
 * a tap opens the Sync screen (pairing when not paired).
 */
@Composable
private fun SyncStatus(badge: SyncBadge, wide: Boolean, onClick: () -> Unit) {
    val colors = RevealTheme.colors
    val count = NumberFormat.getIntegerInstance()
    val text = syncText(badge)
    val (icon, tint) = syncIcon(badge)
    val short = (badge as? SyncBadge.Uploading)?.count?.let(count::format)
    val label = if (wide) text else short
    val description = stringResource(R.string.library_sync_status, text)
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(RevealTheme.shapes.control)
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = description }
            .padding(horizontal = 12.dp)
            .testTag(LibraryTags.SYNC_STATUS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        if (label != null) {
            Text(
                label,
                style = RevealTheme.typography.ui,
                color = colors.foreground,
                maxLines = 1,
                modifier = Modifier.padding(start = 6.dp).clearAndSetSemantics {},
            )
        }
    }
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
            SortItems(query, actions) { open = false }
        }
    }
}

/** The three orders, the current one ticked. */
@Composable
private fun SortItems(query: PhotoQuery, actions: LibraryActions, onDone: () -> Unit) {
    listOf(
        PhotoSort.CAPTURE_NEWEST to R.string.library_sort_newest,
        PhotoSort.CAPTURE_OLDEST to R.string.library_sort_oldest,
        PhotoSort.NAME to R.string.library_sort_name,
    ).forEach { (sort, label) ->
        MenuItem(if (query.sort == sort) PhosphorRegular.Check else null, stringResource(label)) {
            onDone()
            actions.onQuery(query.copy(sort = sort))
        }
    }
}

/**
 * The ⋯ menu. On the phone's home it also holds search and sort (the bar has no room for them); everywhere it adds a
 * folder, opens a single photo, the design system and About.
 */
@Composable
private fun OverflowMenu(state: LibraryUiState, actions: LibraryActions, wide: Boolean, home: Boolean) {
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
            if (home && !wide) {
                MenuItem(PhosphorRegular.MagnifyingGlass, stringResource(R.string.library_search)) {
                    open = false
                    actions.onSearchOpen(true)
                }
                SortItems(state.query, actions) { open = false }
                HorizontalDivider(color = RevealTheme.colors.hairline)
            }
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

/** The selection bar; [wide] (two panes) shows copy, paste, reset and export as buttons instead of in the menu. */
@Composable
private fun SelectionTopBar(state: LibraryUiState, actions: LibraryActions, wide: Boolean) {
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
        if (wide) WideSelectionActions(state, actions)
        SelectionOverflow(state, actions, wide)
    }
}

@Composable
private fun RowScope.WideSelectionActions(state: LibraryUiState, actions: LibraryActions) {
    RevealIconButton(
        icon = PhosphorRegular.Copy,
        contentDescription = stringResource(R.string.library_action_copy_edits),
        onClick = actions.onCopyEdits,
        enabled = state.selected.size == 1,
        modifier = Modifier.testTag(LibraryTags.COPY_EDITS),
    )
    RevealIconButton(
        icon = PhosphorRegular.ClipboardText,
        contentDescription = stringResource(R.string.library_action_paste_edits_choose),
        onClick = actions.onPasteEdits,
        enabled = state.clipboard != null,
        modifier = Modifier.testTag(LibraryTags.PASTE_EDITS),
    )
    RevealIconButton(
        icon = PhosphorRegular.ArrowCounterClockwise,
        contentDescription = stringResource(R.string.library_action_reset_edits),
        onClick = actions.onResetEdits,
        modifier = Modifier.testTag(LibraryTags.RESET_EDITS),
    )
    RevealIconButton(
        icon = PhosphorRegular.Export,
        contentDescription = stringResource(R.string.library_action_export),
        onClick = actions.onExport,
        modifier = Modifier.testTag(LibraryTags.EXPORT),
    )
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
private fun SelectionOverflow(state: LibraryUiState, actions: LibraryActions, wide: Boolean) {
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
            if (state.selectedUnavailable.isNotEmpty()) {
                TaggedMenuItem(
                    PhosphorRegular.ArrowsClockwise,
                    stringResource(R.string.library_unavailable_try_again),
                    UnavailableTags.SELECTION_TRY_AGAIN,
                ) {
                    open = false
                    actions.onTryAgain(state.selectedUnavailable)
                }
                TaggedMenuItem(
                    PhosphorRegular.ImageBroken,
                    stringResource(R.string.library_unavailable_remove),
                    UnavailableTags.SELECTION_REMOVE,
                ) {
                    open = false
                    actions.onForget(state.selectedUnavailable)
                }
            }
            if (state.selectedRemote.isNotEmpty()) {
                TaggedMenuItem(
                    PhosphorRegular.CloudSlash,
                    stringResource(R.string.library_action_delete_server),
                    ServerViewTags.DELETE_FROM_SERVER,
                ) {
                    open = false
                    actions.onDeleteFromServer()
                }
            }
            if (state.selectedConflictCopies.isNotEmpty()) {
                TaggedMenuItem(
                    PhosphorRegular.GitBranch,
                    stringResource(R.string.library_action_delete_conflicts),
                    LibraryTags.DELETE_CONFLICTS,
                ) {
                    open = false
                    actions.onDeleteConflictCopies()
                }
            }
            if (!wide) {
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
            }
            TaggedMenuItem(
                PhosphorRegular.SquaresFour,
                stringResource(R.string.library_action_preset),
                LibraryTags.APPLY_PRESET,
            ) {
                open = false
                actions.onApplyPreset()
            }
            if (!wide) {
                TaggedMenuItem(
                    PhosphorRegular.Export,
                    stringResource(R.string.library_action_export),
                    LibraryTags.EXPORT,
                ) {
                    open = false
                    actions.onExport()
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- grid pane

/** The grid's empty states: nothing matches, Recent empty, a folder indexing or empty, nothing handed over yet. */
@Composable
private fun EmptyGrid(state: LibraryUiState, actions: LibraryActions) {
    val filters = state.filters
    val plain = state.query.search.isBlank() && filters.count == 1
    when {
        state.recent -> EmptyState(
            PhosphorRegular.ClockCounterClockwise,
            stringResource(R.string.library_recent_empty_title),
            stringResource(R.string.library_recent_empty_body),
        )

        plain && state.currentSource?.indexing != null -> EmptyState(
            PhosphorRegular.MagnifyingGlass,
            stringResource(R.string.library_folder_indexing_title),
            stringResource(R.string.library_source_indexing),
        )

        plain && filters.singleFolder != null -> EmptyState(
            PhosphorRegular.Images,
            stringResource(R.string.library_folder_empty_title),
            stringResource(R.string.library_folder_empty_body),
        )

        plain && filters.onlyFromApps -> EmptyState(
            PhosphorRegular.ArrowSquareIn,
            stringResource(R.string.library_from_apps_empty_title),
            stringResource(R.string.library_from_apps_empty_body),
        )

        state.filtered -> EmptyState(
            PhosphorRegular.FunnelSimple,
            stringResource(R.string.library_no_match_title),
            stringResource(R.string.library_no_match_body),
        ) {
            GhostButton(stringResource(R.string.library_clear_filters), {
                actions.onFilters(PhotoFilters.NONE)
                actions.onSearchOpen(false)
            })
        }

        else -> EmptyState(
            PhosphorRegular.Images,
            stringResource(R.string.library_home_empty_title),
            stringResource(R.string.library_home_empty_body),
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
    // The narrowed-to folder's banner; on the unfiltered home, the folder being indexed.
    val banner = state.currentSource ?: state.sources.firstOrNull { it.indexing != null }.takeIf { !state.recent }
    Column(Modifier.fillMaxSize()) {
        banner?.let { SourceBanner(it) }
        if (!state.recent) FilterBar(state, actions)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                !state.photosLoaded -> Unit

                state.photoCount == 0 -> EmptyGrid(state, actions)

                else -> PhotoGrid(
                    entries = state.entries,
                    selected = state.selected,
                    thumbnails = thumbnails,
                    onOpen = { row -> open(row, actions) },
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

/** A grid tap: an unavailable photo explains itself, a conflict copy opens the chooser, anything else the viewer. */
private fun open(row: PhotoRow, actions: LibraryActions) = when {
    row.unavailable -> actions.onOpenUnavailable(row)
    row.conflict -> actions.onOpenConflict(row)
    else -> actions.onOpen(row)
}
