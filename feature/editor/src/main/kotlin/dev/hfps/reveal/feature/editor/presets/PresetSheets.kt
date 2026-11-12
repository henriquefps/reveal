@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.hfps.reveal.feature.editor.presets

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.hfps.reveal.data.preset.GroupKind
import dev.hfps.reveal.data.preset.LibraryGroup
import dev.hfps.reveal.data.preset.LibraryPreset
import dev.hfps.reveal.data.preset.PackInfo
import dev.hfps.reveal.data.preset.PresetLibrary
import dev.hfps.reveal.designsystem.components.ChecklistItem
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChecklist
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.R
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetLimits
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Recipe

/** The string resource of a chooser section. */
internal fun sectionName(section: PresetSection): Int = when (section) {
    PresetSection.PROFILE -> R.string.presets_section_profile
    PresetSection.TREATMENT -> R.string.presets_section_treatment
    PresetSection.WHITE_BALANCE -> R.string.presets_section_white_balance
    PresetSection.EXPOSURE -> R.string.presets_section_exposure
    PresetSection.CONTRAST -> R.string.presets_section_contrast
    PresetSection.HIGHLIGHTS -> R.string.presets_section_highlights
    PresetSection.SHADOWS -> R.string.presets_section_shadows
    PresetSection.WHITES -> R.string.presets_section_whites
    PresetSection.BLACKS -> R.string.presets_section_blacks
    PresetSection.TONE_CURVE -> R.string.presets_section_tone_curve
    PresetSection.VIBRANCE -> R.string.presets_section_vibrance
    PresetSection.SATURATION -> R.string.presets_section_saturation
    PresetSection.COLOUR_MIXER -> R.string.presets_section_colour_mixer
    PresetSection.COLOUR_GRADING -> R.string.presets_section_colour_grading
    PresetSection.TEXTURE -> R.string.presets_section_texture
    PresetSection.CLARITY -> R.string.presets_section_clarity
    PresetSection.DEHAZE -> R.string.presets_section_dehaze
    PresetSection.VIGNETTE -> R.string.presets_section_vignette
    PresetSection.GRAIN -> R.string.presets_section_grain
    PresetSection.SHARPENING -> R.string.presets_section_sharpening
    PresetSection.NOISE_REDUCTION -> R.string.presets_section_noise_reduction
    PresetSection.LENS_CORRECTIONS -> R.string.presets_section_lens_corrections
    PresetSection.CHROMATIC_ABERRATION -> R.string.presets_section_chromatic_aberration
    PresetSection.DEFRINGE -> R.string.presets_section_defringe
    PresetSection.TRANSFORM -> R.string.presets_section_transform
}

/** A single-line text field in the editor's colours (as Settings' fields). */
@Composable
internal fun PresetTextField(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    val c = RevealTheme.colors
    TextField(
        value = value,
        onValueChange = { onChange(it.take(PresetLimits.NAME)) },
        singleLine = true,
        label = { Text(label, style = RevealTheme.typography.ui) },
        textStyle = RevealTheme.typography.body.copy(color = c.foreground),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = c.primary,
            unfocusedIndicatorColor = c.input,
            cursorColor = c.primary,
            focusedLabelColor = c.mutedForeground,
            unfocusedLabelColor = c.mutedForeground,
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .testTag(tag),
    )
}

/**
 * "Create preset" (docs/adr/0019 "Fields"): a name, the user group (or a new one) and the settings to include,
 * ticked from what the edit changed minus the photo-specific sections.
 */
@Suppress("LongMethod")
@Composable
internal fun CreatePresetSheet(
    draft: CreatePresetDraft,
    recipe: Recipe,
    groups: List<LibraryGroup>,
    onSave: (String, Long?, String?, Set<PresetSection>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var groupId by rememberSaveable { mutableStateOf(groups.firstOrNull()?.rowId) }
    var newGroup by rememberSaveable { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf(draft.sections) }
    val changed = remember(recipe) { PresetSection.changedIn(recipe) }
    val edited = stringResource(R.string.presets_edited)
    RevealBottomSheet(onDismissRequest = onDismiss) {
        Eyebrow(stringResource(R.string.presets_create), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
        PresetTextField(stringResource(R.string.presets_name), name, { name = it }, EditorTags.PRESET_NAME)
        Text(
            stringResource(R.string.presets_group),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(start = 24.dp, top = 12.dp),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (groups.isEmpty()) {
                RevealChip(
                    label = stringResource(R.string.presets_group_default),
                    selected = newGroup == null,
                    onClick = { newGroup = null },
                )
            }
            for (group in groups) {
                RevealChip(
                    label = groupName(group),
                    selected = newGroup == null && groupId == group.rowId,
                    onClick = {
                        groupId = group.rowId
                        newGroup = null
                    },
                )
            }
            RevealChip(
                label = stringResource(R.string.presets_new_group),
                selected = newGroup != null,
                onClick = { newGroup = newGroup ?: "" },
                icon = PhosphorRegular.Plus,
                modifier = Modifier.testTag(EditorTags.PRESET_NEW_GROUP),
            )
        }
        newGroup?.let { value ->
            PresetTextField(stringResource(R.string.presets_new_group_name), value, { newGroup = it }, "${EditorTags.PRESET_NEW_GROUP}-name")
        }
        RevealChecklist(
            title = stringResource(R.string.presets_include),
            allLabel = stringResource(R.string.presets_include_all),
            items = PresetSection.entries.map { section ->
                ChecklistItem(section, stringResource(sectionName(section)), edited.takeIf { section in changed })
            },
            checked = sections,
            onCheckedChange = { sections = it },
            confirm = stringResource(R.string.presets_save),
            cancel = stringResource(R.string.presets_cancel),
            onConfirm = {
                if (name.isNotBlank() && (newGroup == null || newGroup!!.isNotBlank())) {
                    onSave(name, groupId, newGroup, sections)
                }
            },
            onCancel = onDismiss,
            modifier = Modifier.padding(top = 12.dp),
            tagPrefix = EditorTags.PRESET_SECTIONS,
        )
    }
}

/** The panel's ⋯: create, import, and the options of the group on screen (rename, order, export, delete; packs). */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun PresetsMenuSheet(
    group: LibraryGroup?,
    library: PresetLibrary,
    actions: EditorActions,
    canCreate: Boolean,
    onImport: () -> Unit,
    onGroupGone: () -> Unit,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var about by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    val stored = library.groups.filter { it.rowId != null }
    val index = group?.let { g -> stored.indexOfFirst { it.uuid == g.uuid } } ?: -1
    val rowId = group?.rowId
    val pack = group?.pack
    RevealBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                renaming && group != null && rowId != null -> NameEditor(
                    title = stringResource(R.string.presets_rename_group),
                    initial = group.name,
                    onDone = { name ->
                        actions.onRenamePresetGroup(rowId, name)
                        onDismiss()
                    },
                    onCancel = { renaming = false },
                )

                confirmDelete && group != null && rowId != null -> Confirm(
                    text = pluralStringResource(
                        R.plurals.presets_delete_group_confirm,
                        group.presets.size,
                        groupName(group),
                        group.presets.size,
                    ),
                    confirm = stringResource(R.string.presets_delete),
                    onConfirm = {
                        actions.onDeletePresetGroup(rowId)
                        onGroupGone()
                        onDismiss()
                    },
                    onCancel = { confirmDelete = false },
                )

                about && pack != null -> PackAbout(pack)

                exporting && group != null && rowId != null -> ExportGroup(group, actions, onDismiss)

                else -> {
                    RevealListRow(
                        title = stringResource(R.string.presets_create),
                        icon = PhosphorRegular.Plus,
                        onClick = if (canCreate) {
                            {
                                onDismiss()
                                actions.onCreatePreset()
                            }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag(EditorTags.presetOption("create")),
                    )
                    RevealListRow(
                        title = stringResource(R.string.presets_import),
                        icon = PhosphorRegular.UploadSimple,
                        onClick = {
                            onDismiss()
                            onImport()
                        },
                        modifier = Modifier.testTag(EditorTags.PRESET_IMPORT),
                    )
                    if (group != null && rowId != null) {
                        RevealRowDivider()
                        Eyebrow(groupName(group), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                        if (group.kind == GroupKind.USER) {
                            RevealListRow(
                                title = stringResource(R.string.presets_rename_group),
                                icon = PhosphorRegular.PencilSimple,
                                onClick = { renaming = true },
                                modifier = Modifier.testTag(EditorTags.presetOption("rename-group")),
                            )
                        }
                        if (index > 0) {
                            RevealListRow(
                                title = stringResource(R.string.presets_move_earlier),
                                icon = PhosphorRegular.CaretLeft,
                                onClick = { actions.onShiftPresetGroup(rowId, -1) },
                            )
                        }
                        if (index in 0 until stored.lastIndex) {
                            RevealListRow(
                                title = stringResource(R.string.presets_move_later),
                                icon = PhosphorRegular.CaretRight,
                                onClick = { actions.onShiftPresetGroup(rowId, 1) },
                            )
                        }
                        if (group.kind == GroupKind.USER && group.presets.isNotEmpty()) {
                            RevealListRow(
                                title = stringResource(R.string.presets_export_group),
                                icon = PhosphorRegular.Export,
                                onClick = { exporting = true },
                            )
                        }
                        pack?.let { pack ->
                            RevealListRow(
                                title = stringResource(R.string.presets_pack_info),
                                subtitle = listOf(pack.name, pack.author).filter { it.isNotBlank() }.joinToString(" · "),
                                icon = PhosphorRegular.Info,
                                onClick = { about = true },
                            )
                            RevealListRow(
                                title = stringResource(R.string.presets_pack_remove),
                                icon = PhosphorRegular.Trash,
                                onClick = {
                                    actions.onRemovePack(pack.rowId)
                                    onGroupGone()
                                    onDismiss()
                                },
                                modifier = Modifier.testTag(EditorTags.PRESET_REMOVE_PACK),
                            )
                        }
                        if (pack == null) {
                            RevealListRow(
                                title = stringResource(R.string.presets_delete_group),
                                icon = PhosphorRegular.Trash,
                                onClick = { confirmDelete = true },
                                modifier = Modifier.testTag(EditorTags.presetOption("delete-group")),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A long-pressed preset's options: favourite, rename, duplicate, move, order, export, delete. */
@Suppress("LongMethod", "CyclomaticComplexMethod")
@Composable
internal fun PresetOptionsSheet(
    preset: LibraryPreset,
    library: PresetLibrary,
    actions: EditorActions,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val group = library.groups.firstOrNull { it.uuid == preset.groupUuid }
    val index = group?.presets?.indexOfFirst { it.uuid == preset.uuid } ?: -1
    val copyName = stringResource(R.string.presets_duplicate_name, preset.name)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetFiles.MIME)) { uri ->
        uri?.let { actions.onExportPreset(preset.uuid, it) }
        onDismiss()
    }
    RevealBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when {
                renaming -> NameEditor(
                    title = stringResource(R.string.presets_rename),
                    initial = preset.name,
                    onDone = {
                        actions.onRenamePreset(preset.uuid, it)
                        onDismiss()
                    },
                    onCancel = { renaming = false },
                )

                confirmDelete -> Confirm(
                    text = stringResource(R.string.presets_delete_confirm, preset.name),
                    confirm = stringResource(R.string.presets_delete),
                    onConfirm = {
                        actions.onDeletePreset(preset.uuid)
                        onDismiss()
                    },
                    onCancel = { confirmDelete = false },
                )

                else -> {
                    Eyebrow(preset.name, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
                    RevealListRow(
                        title = stringResource(if (preset.favourite) R.string.presets_unfavourite else R.string.presets_favourite),
                        icon = PhosphorRegular.Star,
                        onClick = {
                            actions.onPresetFavourite(preset.uuid, !preset.favourite)
                            onDismiss()
                        },
                        modifier = Modifier.testTag(EditorTags.presetOption("favourite")),
                    )
                    if (preset.origin.editable) {
                        RevealListRow(
                            title = stringResource(R.string.presets_rename),
                            icon = PhosphorRegular.PencilSimple,
                            onClick = { renaming = true },
                            modifier = Modifier.testTag(EditorTags.presetOption("rename")),
                        )
                    }
                    RevealListRow(
                        title = stringResource(R.string.presets_duplicate),
                        icon = PhosphorRegular.Copy,
                        onClick = {
                            actions.onDuplicatePreset(preset.uuid, copyName)
                            onDismiss()
                        },
                        modifier = Modifier.testTag(EditorTags.presetOption("duplicate")),
                    )
                    if (preset.rowId != null && index > 0) {
                        RevealListRow(
                            title = stringResource(R.string.presets_move_earlier),
                            icon = PhosphorRegular.CaretLeft,
                            onClick = { actions.onShiftPreset(preset.uuid, -1) },
                        )
                    }
                    if (preset.rowId != null && group != null && index in 0 until group.presets.lastIndex) {
                        RevealListRow(
                            title = stringResource(R.string.presets_move_later),
                            icon = PhosphorRegular.CaretRight,
                            onClick = { actions.onShiftPreset(preset.uuid, 1) },
                        )
                    }
                    if (preset.origin.editable) {
                        for (target in library.userGroups.filter { it.uuid != preset.groupUuid && it.rowId != null }) {
                            RevealListRow(
                                title = stringResource(R.string.presets_move_to, groupName(target)),
                                icon = PhosphorRegular.Folder,
                                onClick = {
                                    actions.onMovePreset(preset.uuid, checkNotNull(target.rowId))
                                    onDismiss()
                                },
                            )
                        }
                    }
                    RevealListRow(
                        title = stringResource(R.string.presets_export),
                        icon = PhosphorRegular.Export,
                        onClick = { exporter.launch("${preset.name}.${PresetFiles.EXTENSION}") },
                        modifier = Modifier.testTag(EditorTags.presetOption("export")),
                    )
                    if (preset.rowId != null) {
                        RevealListRow(
                            title = stringResource(R.string.presets_delete),
                            icon = PhosphorRegular.Trash,
                            onClick = { confirmDelete = true },
                            modifier = Modifier.testTag(EditorTags.presetOption("delete")),
                        )
                    }
                }
            }
        }
    }
}

/** Exporting a user group as a pack: the author and the licence shown to importers, then the file. */
@Composable
private fun ExportGroup(group: LibraryGroup, actions: EditorActions, onDismiss: () -> Unit) {
    var author by rememberSaveable { mutableStateOf("") }
    val defaultLicence = stringResource(R.string.presets_export_licence_default)
    var licence by rememberSaveable { mutableStateOf(defaultLicence) }
    val rowId = checkNotNull(group.rowId)
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(PresetPacks.MIME)) { uri ->
        uri?.let { actions.onExportPresetGroup(rowId, it, author, licence.ifBlank { defaultLicence }) }
        onDismiss()
    }
    val name = groupName(group)
    Eyebrow(stringResource(R.string.presets_export_group), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    PresetTextField(stringResource(R.string.presets_export_author), author, { author = it }, "preset-export-author")
    PresetTextField(stringResource(R.string.presets_export_licence), licence, { licence = it }, "preset-export-licence")
    Buttons(
        confirm = stringResource(R.string.presets_export),
        onConfirm = { exporter.launch("$name.${PresetPacks.EXTENSION}") },
        onCancel = onDismiss,
    )
}

/** A pack's details: name, author, version, licence text and its link. */
@Composable
private fun PackAbout(pack: PackInfo) {
    val context = LocalContext.current
    val c = RevealTheme.colors
    Eyebrow(stringResource(R.string.presets_pack_info), Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(pack.name, style = RevealTheme.typography.title, color = c.foreground)
        if (pack.author.isNotBlank()) {
            Text(stringResource(R.string.presets_pack_by, pack.author), style = RevealTheme.typography.body, color = c.mutedForeground)
        }
        Text(stringResource(R.string.presets_pack_licence, pack.licenceName), style = RevealTheme.typography.body, color = c.foreground)
        if (pack.licenceText.isNotBlank()) {
            Text(
                pack.licenceText,
                style = RevealTheme.typography.mono,
                color = c.mutedForeground,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(c.muted, RoundedCornerShape(8.dp))
                    .padding(12.dp),
            )
        }
    }
    pack.link?.let { link ->
        RevealListRow(
            title = stringResource(R.string.presets_pack_link),
            subtitle = link,
            icon = PhosphorRegular.LinkSimple,
            onClick = {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            },
        )
    }
}

/** A pack on trial: who made it, its licence, and Keep / Remove (docs/adr/0019 "Import and export"). */
@Composable
internal fun TrialPackBanner(pack: PackInfo, onKeep: () -> Unit, onRemove: () -> Unit) {
    val c = RevealTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .background(c.muted, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(stringResource(R.string.presets_pack_trial, pack.name), style = RevealTheme.typography.uiMedium, color = c.foreground)
        val detail = listOfNotNull(
            pack.author.takeIf { it.isNotBlank() }?.let { stringResource(R.string.presets_pack_by, it) },
            stringResource(R.string.presets_pack_licence, pack.licenceName),
        ).joinToString(" · ")
        Text(detail, style = RevealTheme.typography.ui, color = c.mutedForeground)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            GhostButton(stringResource(R.string.presets_pack_remove), onRemove, Modifier.testTag(EditorTags.PRESET_REMOVE_PACK))
            PrimaryButton(stringResource(R.string.presets_pack_keep), onKeep, Modifier.testTag(EditorTags.PRESET_KEEP_PACK))
        }
    }
}

@Composable
private fun NameEditor(title: String, initial: String, onDone: (String) -> Unit, onCancel: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(initial) }
    Eyebrow(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
    PresetTextField(stringResource(R.string.presets_name), name, { name = it }, EditorTags.PRESET_NAME)
    Buttons(
        confirm = stringResource(R.string.presets_save),
        onConfirm = { if (name.isNotBlank()) onDone(name) },
        onCancel = onCancel,
    )
}

@Composable
private fun Confirm(text: String, confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
    Text(
        text,
        style = RevealTheme.typography.body,
        color = RevealTheme.colors.foreground,
        modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
    )
    Buttons(confirm, onConfirm, onCancel, Modifier.testTag(EditorTags.presetOption("confirm")))
}

@Composable
private fun Buttons(confirm: String, onConfirm: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        GhostButton(stringResource(R.string.presets_cancel), onCancel)
        PrimaryButton(confirm, onConfirm, modifier)
    }
}
