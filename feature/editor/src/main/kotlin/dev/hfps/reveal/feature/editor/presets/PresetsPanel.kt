package dev.hfps.reveal.feature.editor.presets

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.data.preset.LibraryGroup
import dev.hfps.reveal.data.preset.LibraryPreset
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealMotion
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.feature.editor.panels.PanelHost
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The presets a chip shows. */
internal fun PresetLibrary.presetsOf(key: String): List<LibraryPreset> = when (key) {
    PresetChips.FAVOURITES -> favourites
    PresetChips.RECENT -> recents
    else -> groups.firstOrNull { it.uuid == key }?.presets.orEmpty()
}

/** The chip the panel opens on: the applied preset's group, else the first group, else Recent. */
internal fun PresetLibrary.defaultChip(appliedUuid: String?): String =
    appliedUuid?.let { uuid -> groups.firstOrNull { g -> g.presets.any { it.uuid == uuid } }?.uuid }
        ?: groups.firstOrNull { it.presets.isNotEmpty() }?.uuid
        ?: groups.firstOrNull()?.uuid
        ?: PresetChips.RECENT

/**
 * The Presets panel (docs/plan/ux.md "Presets", docs/adr/0019): group chips (Favourites, Recent, then the groups),
 * a grid of live previews of each preset on the current photo, the strength slider of the applied preset, "Create
 * preset" from the edit and ⋯ (import). Long-press a preset or a group chip for its options. The grid owns its
 * scrolling (the panel card does not scroll this panel).
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
fun PresetsPanel(host: PanelHost, modifier: Modifier = Modifier) {
    val state = host.state
    val actions = host.actions
    val presets = state.presets
    val library = presets.library
    val session = presets.session?.takeIf { it.isCurrent(state.edit.history) }
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    val shown = chip?.takeIf { key ->
        key == PresetChips.FAVOURITES || key == PresetChips.RECENT || library.groups.any { it.uuid == key }
    } ?: library.defaultChip(session?.preset?.id)
    var draggingStrength by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf<LibraryPreset?>(null) }
    var menu by remember { mutableStateOf(false) }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(actions.onImportPresets)
    }
    val base = remember(session, state.edit.committed) { session?.before ?: state.edit.committed }

    Column(modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = !draggingStrength,
            enter = fadeIn(RevealMotion.standard()),
            exit = fadeOut(RevealMotion.fast()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val chipScroll = rememberScrollState()
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(chipScroll)
                        .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    RevealChip(
                        label = stringResource(R.string.presets_favourites),
                        selected = shown == PresetChips.FAVOURITES,
                        onClick = { chip = PresetChips.FAVOURITES },
                        icon = PhosphorRegular.Star,
                        modifier = Modifier.testTag(EditorTags.presetChip(PresetChips.FAVOURITES)),
                    )
                    RevealChip(
                        label = stringResource(R.string.presets_recent),
                        selected = shown == PresetChips.RECENT,
                        onClick = { chip = PresetChips.RECENT },
                        modifier = Modifier.testTag(EditorTags.presetChip(PresetChips.RECENT)),
                    )
                    for (group in library.groups) {
                        val bring = remember { BringIntoViewRequester() }
                        LaunchedEffect(shown == group.uuid) { if (shown == group.uuid) bring.bringIntoView() }
                        RevealChip(
                            label = groupName(group),
                            selected = shown == group.uuid,
                            onClick = { chip = group.uuid },
                            icon = if (group.kind == GroupKind.PACK) PhosphorRegular.Package else null,
                            modifier = Modifier
                                .bringIntoViewRequester(bring)
                                .testTag(EditorTags.presetChip(group.uuid)),
                        )
                    }
                }
                RevealIconButton(
                    icon = PhosphorRegular.Plus,
                    contentDescription = stringResource(R.string.presets_create),
                    onClick = actions.onCreatePreset,
                    enabled = state.ready && !state.edit.committed.isUnedited(),
                    modifier = Modifier.testTag(EditorTags.PRESET_CREATE),
                )
                Box {
                    RevealIconButton(
                        icon = PhosphorRegular.DotsThree,
                        contentDescription = stringResource(R.string.presets_more),
                        onClick = { menu = true },
                        modifier = Modifier.testTag(EditorTags.PRESET_MENU),
                    )
                }
            }
        }
        val group = library.groups.firstOrNull { it.uuid == shown }
        val pack = group?.pack
        if (pack != null && pack.trial && !draggingStrength) {
            TrialPackBanner(pack, onKeep = { actions.onKeepPack(pack.rowId) }, onRemove = {
                actions.onRemovePack(pack.rowId)
                chip = null
            })
        }
        val items = library.presetsOf(shown)
        Box(Modifier.weight(1f, fill = false)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = !draggingStrength,
                enter = fadeIn(RevealMotion.standard()),
                exit = fadeOut(RevealMotion.fast()),
            ) {
                if (items.isEmpty()) {
                    EmptyPresets(
                        shown = shown,
                        libraryEmpty = library.all.isEmpty(),
                        onImport = { importer.launch(arrayOf("*/*")) },
                    )
                } else {
                    val grid = rememberLazyGridState()
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(CELL_MIN),
                        state = grid,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = GRID_MAX)
                            .testTag(EditorTags.PRESET_GRID),
                    ) {
                        items(items, key = { it.uuid }) { preset ->
                            PresetCell(
                                preset = preset,
                                base = base,
                                ready = state.ready,
                                selected = session?.preset?.id == preset.uuid,
                                thumbnails = actions.presetThumbnails,
                                preview = actions.presetPreview,
                                onClick = { actions.onApplyPreset(preset.uuid) },
                                onLongClick = { options = preset },
                            )
                        }
                    }
                }
            }
        }
        if (session != null) {
            RevealSlider(
                label = stringResource(R.string.presets_strength) + " · " + session.preset.name,
                value = session.strength * PERCENT,
                spec = STRENGTH_SPEC,
                format = { "${it.roundToInt()} %" },
                onValueChange = { actions.onPresetStrength(it / PERCENT) },
                onReset = {
                    actions.onPresetStrength(1f)
                    actions.onPresetStrengthDone()
                },
                enabled = state.ready,
                testTag = EditorTags.PRESET_STRENGTH,
                onDragStateChange = { draggingStrength = it },
                onValueChangeFinished = actions.onPresetStrengthDone,
            )
        }
    }
    if (menu) {
        PresetsMenuSheet(
            group = library.groups.firstOrNull { it.uuid == shown },
            library = library,
            actions = actions,
            canCreate = state.ready && !state.edit.committed.isUnedited(),
            onImport = { importer.launch(arrayOf("*/*")) },
            onGroupRemove = { chip = null },
            onDismiss = { menu = false },
        )
    }
    options?.let { preset ->
        PresetOptionsSheet(
            preset = preset,
            library = library,
            actions = actions,
            onDismiss = { options = null },
        )
    }
    presets.creating?.let { draft ->
        CreatePresetSheet(
            draft = draft,
            recipe = state.edit.committed,
            groups = library.userGroups,
            onSave = actions.onSavePreset,
            onDismiss = actions.onCreatePresetDismiss,
        )
    }
}

/** A group's display name: the default user group is "My presets". */
@Composable
internal fun groupName(group: LibraryGroup): String = if (group.kind == GroupKind.USER && group.name.isEmpty()) {
    stringResource(R.string.presets_group_default)
} else {
    group.name
}

/**
 * One preset: its live preview on the photo (square, centre-cropped), the name under it, a star when it is a
 * favourite, an outline when it is the applied one. Tap applies, long-press opens its options.
 */
@OptIn(ExperimentalFoundationApi::class)
@Suppress("LongParameterList", "LongMethod")
@Composable
private fun PresetCell(
    preset: LibraryPreset,
    base: Recipe,
    ready: Boolean,
    selected: Boolean,
    thumbnails: PresetThumbnailSource?,
    preview: (dev.hfps.reveal.pipeline.preset.Preset, Recipe) -> Recipe,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val c = RevealTheme.colors
    val key = PresetThumbnails.key(
        "",
        base,
        preset.uuid,
        preset.preset.version,
        preset.preset.patch.hashCode(),
    )
    val currentPreview by rememberUpdatedState(preview)
    val image by produceState(thumbnails?.cached(key), key, ready, thumbnails) {
        if (value != null || !ready || thumbnails == null) return@produceState
        val recipe = withContext(Dispatchers.Default) { currentPreview(preset.preset, base) }
        value = thumbnails.render(key, recipe)
    }
    val appliedLabel = stringResource(R.string.presets_applied)
    val favouriteLabel = stringResource(R.string.presets_favourite_badge)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .clip(shape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick, role = Role.Button)
            .semantics(mergeDescendants = true) {
                contentDescription = preset.name
                this.selected = selected
                stateDescription = listOfNotNull(
                    appliedLabel.takeIf { selected },
                    favouriteLabel.takeIf { preset.favourite },
                ).joinToString(", ")
            }
            .testTag(EditorTags.preset(preset.uuid)),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(c.muted)
                .then(if (selected) Modifier.border(2.dp, c.primary, shape) else Modifier),
        ) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (preset.favourite) {
                Icon(
                    imageVector = PhosphorFill.Star,
                    contentDescription = null,
                    tint = c.warning,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(16.dp),
                )
            }
            if (selected) {
                Icon(
                    imageVector = PhosphorFill.CheckCircle,
                    contentDescription = null,
                    tint = c.primary,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .size(20.dp),
                )
            }
        }
        Text(
            text = preset.name,
            style = RevealTheme.typography.ui,
            color = if (selected) c.primary else c.foreground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp, bottom = 2.dp),
        )
    }
}

@Composable
private fun EmptyPresets(shown: String, libraryEmpty: Boolean, onImport: () -> Unit) {
    val c = RevealTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .testTag(EditorTags.PRESET_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val body = when {
            libraryEmpty -> R.string.presets_empty_body
            shown == PresetChips.FAVOURITES -> R.string.presets_favourites_empty
            shown == PresetChips.RECENT -> R.string.presets_recent_empty
            else -> R.string.presets_empty_body
        }
        if (libraryEmpty) {
            Text(stringResource(R.string.presets_empty_title), style = RevealTheme.typography.ui, color = c.foreground)
        }
        Text(
            stringResource(body),
            style = RevealTheme.typography.body,
            color = c.mutedForeground,
            textAlign = TextAlign.Center,
        )
        if (libraryEmpty) SecondaryButton(stringResource(R.string.presets_import), onImport)
    }
}

private val CELL_MIN = 92.dp
private val GRID_MAX = 2000.dp
private const val PERCENT = 100f
private val STRENGTH_SPEC = SliderSpec(min = 0f, max = 200f, neutral = 100f, step = 1f, fineStep = 1f, bipolar = false)
