package dev.hfps.reveal.feature.library

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.db.FilterCounts
import dev.hfps.reveal.data.library.SourceItem
import dev.hfps.reveal.data.model.CullingFilter
import dev.hfps.reveal.data.model.EditFilter
import dev.hfps.reveal.data.model.PhotoFilters
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.SourceFilter
import dev.hfps.reveal.data.model.SyncFilter
import dev.hfps.reveal.data.model.TypeFilter
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import java.text.NumberFormat

/**
 * One choice of the filter sheet, of any group (FIX/L): how to toggle it, its `PhotoListCodec` token (test tags), and
 * its label and count. Folder labels are the folder's name; every other label is a string resource.
 */
@Immutable
internal data class FilterOption(
    val token: String,
    val label: Int?,
    val name: String?,
    val count: Int?,
    val isOn: (PhotoFilters) -> Boolean,
    val toggle: (PhotoFilters) -> PhotoFilters,
)

/** A group of the sheet: its heading and its choices (OR within, AND across). */
@Immutable
internal data class FilterGroup(val title: Int, val options: List<FilterOption>)

private fun token(filters: PhotoFilters) = PhotoListCodec.encodeFilters(filters)

private fun option(source: SourceFilter, label: Int?, name: String?, count: Int?) = FilterOption(
    token = token(PhotoFilters(sources = setOf(source))),
    label = label,
    name = name,
    count = count,
    isOn = { source in it.sources },
    toggle = { it.toggle(source) },
)

private fun option(choice: CullingFilter, label: Int, count: Int?) = FilterOption(
    token(PhotoFilters(culling = setOf(choice))),
    label,
    null,
    count,
    { choice in it.culling },
    { it.toggle(choice) },
)

private fun option(choice: EditFilter, label: Int, count: Int?) = FilterOption(
    token(PhotoFilters(edits = setOf(choice))),
    label,
    null,
    count,
    { choice in it.edits },
    { it.toggle(choice) },
)

private fun option(choice: TypeFilter, label: Int, count: Int?) = FilterOption(
    token(PhotoFilters(types = setOf(choice))),
    label,
    null,
    count,
    { choice in it.types },
    { it.toggle(choice) },
)

private fun option(choice: SyncFilter, label: Int, count: Int?) = FilterOption(
    token(PhotoFilters(sync = setOf(choice))),
    label,
    null,
    count,
    { choice in it.sync },
    { it.toggle(choice) },
)

/**
 * The sheet's groups. "Opened from other apps" and "Only on the server" show when they hold photos (or are on); the
 * Sync group only while paired. [counts] null = unknown (no counts shown).
 */
internal fun filterGroups(
    filters: PhotoFilters,
    sources: List<SourceItem>,
    counts: FilterCounts?,
    paired: Boolean,
): List<FilterGroup> {
    val sourceOptions = buildList {
        sources.forEach { add(option(SourceFilter.Folder(it.id), null, it.name, it.photoCount)) }
        if ((counts?.fromApps ?: 0) > 0 || SourceFilter.FromApps in filters.sources) {
            add(option(SourceFilter.FromApps, R.string.library_from_apps, null, counts?.fromApps))
        }
        if (paired || (counts?.serverOnly ?: 0) > 0 || SourceFilter.ServerOnly in filters.sources) {
            add(option(SourceFilter.ServerOnly, R.string.library_filter_server_only, null, counts?.serverOnly))
        }
    }
    return listOfNotNull(
        FilterGroup(R.string.library_filter_group_source, sourceOptions).takeIf { sourceOptions.isNotEmpty() },
        FilterGroup(
            R.string.library_filter_group_culling,
            listOf(
                option(CullingFilter.PICKED, R.string.library_filter_picked, counts?.picked),
                option(CullingFilter.STARRED, R.string.library_filter_starred, counts?.starred),
                option(CullingFilter.REJECTED, R.string.library_filter_rejected, counts?.rejected),
            ),
        ),
        FilterGroup(
            R.string.library_filter_group_edits,
            listOf(
                option(EditFilter.EDITED, R.string.library_filter_edited, counts?.edited),
                option(EditFilter.NOT_EDITED, R.string.library_filter_not_edited, counts?.notEdited),
            ),
        ),
        FilterGroup(
            R.string.library_filter_group_type,
            listOf(
                option(TypeFilter.RAW, R.string.library_filter_raw, counts?.raw),
                option(TypeFilter.JPEG, R.string.library_filter_jpeg, counts?.jpeg),
            ),
        ),
        FilterGroup(
            R.string.library_filter_group_sync,
            listOf(
                option(SyncFilter.SYNCED, R.string.library_filter_synced, counts?.synced),
                option(SyncFilter.NOT_SYNCED, R.string.library_filter_not_synced, counts?.notSynced),
            ),
        ).takeIf { paired },
    )
}

/** The choices that are on, in sheet order: the chips above the grid. */
internal fun activeOptions(filters: PhotoFilters, sources: List<SourceItem>, paired: Boolean): List<FilterOption> {
    // A folder filter whose folder is not known yet still shows (and can be removed).
    val known = sources.map { it.id }.toSet()
    val missing = filters.sources.filterIsInstance<SourceFilter.Folder>().filter { it.sourceId !in known }
        .map { option(it, R.string.library_filter_folder, null, null) }
    return missing + filterGroups(filters, sources, null, paired).flatMap { it.options }.filter { it.isOn(filters) }
}

@Composable
private fun FilterOption.text(): String = name ?: stringResource(checkNotNull(label))

@Composable
private fun FilterOption.textWithCount(): String {
    val text = text()
    return count?.let { stringResource(R.string.library_filter_with_count, text, formatCount(it)) } ?: text
}

private fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/**
 * The row above the home grid: the Filters button (with how many choices are on), a removable chip per active choice
 * and "Clear all". Scrolls sideways when the chips do not fit.
 */
@Composable
internal fun FilterBar(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val filters = state.filters
    val active = activeOptions(filters, state.sources, state.paired)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RevealChip(
            label = if (filters.isEmpty) {
                stringResource(R.string.library_filters)
            } else {
                stringResource(R.string.library_filters_count, filters.count)
            },
            selected = false,
            onClick = { actions.onFiltersOpen(true) },
            icon = PhosphorRegular.FunnelSimple,
            modifier = Modifier.testTag(LibraryTags.FILTERS),
        )
        active.forEach { choice ->
            RevealChip(
                label = choice.text(),
                selected = true,
                onClick = { actions.onFilters(choice.toggle(filters)) },
                icon = PhosphorRegular.X,
                modifier = Modifier.testTag(LibraryTags.activeFilter(choice.token)),
            )
        }
        if (active.size > 1) {
            GhostButton(
                text = stringResource(R.string.library_filters_clear_all),
                onClick = { actions.onFilters(PhotoFilters.NONE) },
                modifier = Modifier.testTag(LibraryTags.FILTERS_CLEAR),
            )
        }
    }
}

/** The filter sheet (a centred dialog on wide windows, `RevealBottomSheet`). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FilterSheet(state: LibraryUiState, actions: LibraryActions) {
    RevealBottomSheet(onDismissRequest = { actions.onFiltersOpen(false) }) {
        FilterSheetContent(state, actions)
    }
}

/** The filter sheet's content without the sheet (screenshots): every group, its choices with their counts. */
@Composable
fun FilterSheetContent(state: LibraryUiState, actions: LibraryActions, modifier: Modifier = Modifier) {
    val filters = state.filters
    Column(modifier.fillMaxWidth().testTag(LibraryTags.FILTER_SHEET)) {
        Text(
            stringResource(R.string.library_filters),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            filterGroups(filters, state.sources, state.counts, state.paired).forEach { group ->
                Eyebrow(
                    stringResource(group.title),
                    Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    group.options.forEach { choice ->
                        val on = choice.isOn(filters)
                        RevealChip(
                            label = choice.textWithCount(),
                            selected = on,
                            onClick = { actions.onFilters(choice.toggle(filters)) },
                            icon = if (on) PhosphorRegular.Check else null,
                            modifier = Modifier.testTag(LibraryTags.filterChoice(choice.token)),
                        )
                    }
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                GhostButton(
                    text = stringResource(R.string.library_filters_clear_all),
                    onClick = { actions.onFilters(PhotoFilters.NONE) },
                    enabled = !filters.isEmpty,
                )
            }
            PrimaryButton(
                text = stringResource(R.string.library_filters_done),
                onClick = { actions.onFiltersOpen(false) },
                modifier = Modifier.testTag(LibraryTags.FILTER_SHEET_DONE),
            )
        }
    }
}
