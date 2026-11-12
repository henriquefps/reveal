package dev.hfps.reveal.feature.export.ui

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealChip
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.components.slider.RevealSlider
import dev.hfps.reveal.designsystem.components.slider.SliderSpec
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.export.R
import dev.hfps.reveal.feature.export.destination.ShareCache
import dev.hfps.reveal.feature.export.engine.ExportFailure
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportFormat
import dev.hfps.reveal.feature.export.model.ExportNaming
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.model.MetadataLevel
import dev.hfps.reveal.feature.export.model.NameTemplate
import dev.hfps.reveal.pipeline.export.ExportSize
import kotlin.math.roundToInt

/** Test tags of the export sheet (instrumented tests are locale-independent). */
object ExportTags {
    const val SHEET = "export_sheet"
    const val START = "export_start"
    const val CANCEL = "export_cancel"
    const val DONE = "export_done"
    const val SHARE_RESULTS = "export_share_results"
    const val ESTIMATE = "export_estimate"
    const val PROGRESS = "export_progress"
    const val FORMAT_JPEG = "export_format_jpeg"
    const val FORMAT_HEIF = "export_format_heif"
    const val DEST_PICTURES = "export_dest_pictures"
    const val DEST_FOLDER = "export_dest_folder"
    const val DEST_SHARE = "export_dest_share"
}

/**
 * Hosts the export sheet for [request] (null = closed): a bottom sheet on phones, a dialog on tablets (>= 600dp wide).
 * Format, colour, quality, size, metadata, destination and file name, with a live estimate; then progress (the job
 * runs in the background: closing the sheet does not stop it) and the result. Share hands the files to the system
 * share sheet and deletes them when it returns.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheetHost(
    request: ExportRequest?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ExportViewModel = hiltViewModel(),
) {
    if (request == null) return
    LaunchedEffect(request) { viewModel.open(request) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val shareLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The share sheet returned: the receiving app has read the files.
        ShareCache.clear(context)
    }
    // Sharing files already saved (Pictures/Reveal, a folder): nothing to clean up when the sheet returns.
    val shareResultsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    LaunchedEffect(state.share) {
        if (state.share.isEmpty()) return@LaunchedEffect
        shareLauncher.launch(Intent.createChooser(shareIntent(state.share, state.options.format.mimeType), null))
        viewModel.onShareLaunched()
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setFolder(uri, folderName(uri))
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start()
    }
    val actions = ExportSheetActions(
        onOptions = viewModel::setOptions,
        onPickFolder = { folderPicker.launch(null) },
        onRememberedFolder = viewModel::rememberedFolder,
        onStart = {
            val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            if (needsPermission) notifications.launch(Manifest.permission.POST_NOTIFICATIONS) else viewModel.start()
        },
        onCancel = viewModel::cancel,
        onShareResults = { uris ->
            shareResultsLauncher.launch(Intent.createChooser(shareIntent(uris, state.options.format.mimeType), null))
        },
        onDone = {
            viewModel.dismissRun()
            onDismiss()
        },
    )
    // A bottom sheet on phones, a centred dialog on tablets (RevealBottomSheet decides by the window width).
    RevealBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        ExportSheetContent(state, actions, Modifier.navigationBarsPadding())
    }
}

/** Everything the sheet content can ask for (stateless content for screenshots). */
class ExportSheetActions(
    val onOptions: (ExportOptions) -> Unit = {},
    val onPickFolder: () -> Unit = {},
    val onRememberedFolder: () -> ExportDestination.Folder? = { null },
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    /** Share the files the finished run saved ([ExportRun.results]) through the system share sheet. */
    val onShareResults: (List<Uri>) -> Unit = {},
    val onDone: () -> Unit = {},
)

@Composable
fun ExportSheetContent(state: ExportUiState, actions: ExportSheetActions, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(ExportTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val count = state.request?.count ?: 1
        Text(
            stringResource(R.string.export_title),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
        )
        if (count > 1) {
            Text(
                pluralStringResource(R.plurals.export_photos, count, count),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
            )
        }
        val run = state.run
        if (run != null) RunSection(run, actions) else OptionsSection(state, actions)
        Spacer(Modifier.padding(4.dp))
    }
}

@Composable
private fun OptionsSection(state: ExportUiState, actions: ExportSheetActions) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val set = actions.onOptions
        FormatSection(state, set)
        if (options.format.hasQuality) {
            RevealSlider(
                label = stringResource(R.string.export_quality),
                value = options.quality.toFloat(),
                spec = QUALITY_SPEC,
                format = { it.roundToInt().toString() },
                onValueChange = { set(options.copy(quality = it.roundToInt().coerceIn(1, ExportOptions.MAX_QUALITY))) },
                onReset = { set(options.copy(quality = ExportOptions.DEFAULT_QUALITY)) },
            )
        }
        SizeSection(state, set)
        MetadataSection(state, set)
        DestinationSection(state, actions)
        NamingSection(state, set)
        EstimateRow(state)
        if (state.previewOnly > 0) {
            Note(
                pluralStringResource(R.plurals.export_preview_only, state.previewOnly, state.previewOnly),
                warning = true,
            )
        }
        PrimaryButton(
            text = stringResource(R.string.export_start),
            onClick = actions.onStart,
            enabled = state.request != null,
            modifier = Modifier.fillMaxWidth().testTag(ExportTags.START),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChipRow(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        content()
    }
}

@Composable
private fun Note(text: String, warning: Boolean = false) {
    Text(
        text,
        style = RevealTheme.typography.ui,
        color = if (warning) RevealTheme.colors.warning else RevealTheme.colors.mutedForeground,
    )
}

@Composable
private fun FormatSection(state: ExportUiState, set: (ExportOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val heif = state.support?.heif ?: false
        Eyebrow(stringResource(R.string.export_format))
        ChipRow {
            RevealChip(
                "JPEG",
                options.format == ExportFormat.JPEG,
                { set(options.copy(format = ExportFormat.JPEG)) },
                Modifier.testTag(ExportTags.FORMAT_JPEG),
            )
            if (heif) {
                RevealChip(
                    "HEIF",
                    options.format == ExportFormat.HEIF,
                    { set(options.copy(format = ExportFormat.HEIF)) },
                    Modifier.testTag(ExportTags.FORMAT_HEIF),
                )
            }
        }
        when {
            state.support != null && !heif -> Note(stringResource(R.string.export_heif_unsupported))
            options.format == ExportFormat.HEIF -> Note(stringResource(R.string.export_heif_note))
        }
        if (options.format == ExportFormat.JPEG) {
            Eyebrow(stringResource(R.string.export_colour))
            ChipRow {
                RevealChip(
                    "sRGB",
                    options.colour == ExportColour.SRGB,
                    { set(options.copy(colour = ExportColour.SRGB)) },
                )
                RevealChip(
                    "Display P3",
                    options.colour == ExportColour.DISPLAY_P3,
                    { set(options.copy(colour = ExportColour.DISPLAY_P3)) },
                )
            }
            if (options.colour == ExportColour.DISPLAY_P3) Note(stringResource(R.string.export_colour_p3_note))
        }
    }
}

private enum class SizeChoice { FULL, WEB, INSTAGRAM, LONG_EDGE, MEGAPIXELS, PERCENT }

private fun ExportSize.choice(): SizeChoice = when (this) {
    ExportSize.Original -> SizeChoice.FULL
    ExportSize.WEB -> SizeChoice.WEB
    ExportSize.INSTAGRAM_PORTRAIT -> SizeChoice.INSTAGRAM
    is ExportSize.LongEdge -> SizeChoice.LONG_EDGE
    is ExportSize.Megapixels -> SizeChoice.MEGAPIXELS
    is ExportSize.Percent -> SizeChoice.PERCENT
    is ExportSize.FitBox -> SizeChoice.INSTAGRAM
}

@Composable
@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun SizeSection(state: ExportUiState, set: (ExportOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val choice = options.size.choice()
        val full = state.fullSize
        val longest = full?.let { maxOf(it.width, it.height) } ?: DEFAULT_LONG_EDGE_MAX
        val fullMp = full?.let { it.pixels / MEGA } ?: DEFAULT_MP_MAX
        fun size(s: ExportSize) = set(options.copy(size = s))
        val px = stringResource(R.string.export_unit_px)
        val mp = stringResource(R.string.export_unit_mp)
        Eyebrow(stringResource(R.string.export_size))
        ChipRow {
            RevealChip(
                stringResource(R.string.export_size_full),
                choice == SizeChoice.FULL,
                { size(ExportSize.Original) },
            )
            RevealChip(stringResource(R.string.export_size_web), choice == SizeChoice.WEB, { size(ExportSize.WEB) })
            RevealChip(
                stringResource(R.string.export_size_instagram),
                choice == SizeChoice.INSTAGRAM,
                { size(ExportSize.INSTAGRAM_PORTRAIT) },
            )
            RevealChip(stringResource(R.string.export_size_long_edge), choice == SizeChoice.LONG_EDGE, {
                size(ExportSize.LongEdge(minOf(DEFAULT_LONG_EDGE, longest)))
            })
            RevealChip(stringResource(R.string.export_size_megapixels), choice == SizeChoice.MEGAPIXELS, {
                size(ExportSize.Megapixels(minOf(DEFAULT_MP, fullMp.toFloat())))
            })
            RevealChip(stringResource(R.string.export_size_percent), choice == SizeChoice.PERCENT, {
                size(ExportSize.Percent(DEFAULT_PERCENT))
            })
        }
        when (val s = options.size) {
            is ExportSize.LongEdge -> if (choice == SizeChoice.LONG_EDGE) {
                RevealSlider(
                    label = stringResource(R.string.export_size_long_edge),
                    value = s.pixels.toFloat(),
                    spec = SliderSpec(
                        MIN_LONG_EDGE,
                        longest.toFloat().coerceAtLeast(MIN_LONG_EDGE + 1),
                        DEFAULT_LONG_EDGE.toFloat().coerceAtMost(longest.toFloat()),
                        LONG_EDGE_STEP,
                        1f,
                        false,
                    ),
                    format = { "${it.roundToInt()} $px" },
                    onValueChange = { size(ExportSize.LongEdge(it.roundToInt().coerceAtLeast(1))) },
                    onReset = { size(ExportSize.LongEdge(minOf(DEFAULT_LONG_EDGE, longest))) },
                )
            }

            is ExportSize.Megapixels -> RevealSlider(
                label = stringResource(R.string.export_size_megapixels),
                value = s.megapixels,
                spec = SliderSpec(
                    MIN_MP,
                    fullMp.toFloat().coerceAtLeast(MIN_MP + 1f),
                    minOf(DEFAULT_MP, fullMp.toFloat()),
                    MP_STEP,
                    MP_STEP,
                    false,
                ),
                format = { "${(it * TENTHS).roundToInt() / TENTHS} $mp" },
                onValueChange = { size(ExportSize.Megapixels(it.coerceAtLeast(MIN_MP))) },
                onReset = { size(ExportSize.Megapixels(minOf(DEFAULT_MP, fullMp.toFloat()))) },
            )

            is ExportSize.Percent -> RevealSlider(
                label = stringResource(R.string.export_size_percent),
                value = s.percent.toFloat(),
                spec = SliderSpec(MIN_PERCENT, FULL_PERCENT, DEFAULT_PERCENT.toFloat(), 1f, 1f, false),
                format = { "${it.roundToInt()} %" },
                onValueChange = { size(ExportSize.Percent(it.roundToInt().coerceIn(1, FULL_PERCENT.toInt()))) },
                onReset = { size(ExportSize.Percent(DEFAULT_PERCENT)) },
            )

            else -> Unit
        }
        state.outputSize?.let { out ->
            Note(stringResource(R.string.export_output_size, out.width, out.height))
        }
        val box = options.size as? ExportSize.FitBox
        if (box != null && full != null && !box.fitsExactly(full.width, full.height)) {
            Note(stringResource(R.string.export_size_instagram_fit), warning = true)
        }
    }
}

@Composable
private fun MetadataSection(state: ExportUiState, set: (ExportOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val m = options.metadata
        Eyebrow(stringResource(R.string.export_metadata))
        ChipRow {
            RevealChip(stringResource(R.string.export_metadata_all), m.level == MetadataLevel.ALL, {
                set(options.copy(metadata = m.copy(level = MetadataLevel.ALL)))
            })
            RevealChip(stringResource(R.string.export_metadata_copyright), m.level == MetadataLevel.COPYRIGHT, {
                set(options.copy(metadata = m.copy(level = MetadataLevel.COPYRIGHT)))
            })
            RevealChip(stringResource(R.string.export_metadata_none), m.level == MetadataLevel.NONE, {
                set(options.copy(metadata = m.copy(level = MetadataLevel.NONE)))
            })
        }
        if (m.level == MetadataLevel.ALL) {
            SwitchRow(stringResource(R.string.export_metadata_remove_location), m.removeLocation) {
                set(options.copy(metadata = m.copy(removeLocation = it)))
            }
        }
        SwitchRow(stringResource(R.string.export_metadata_capture_time), m.keepCaptureTime) {
            set(options.copy(metadata = m.copy(keepCaptureTime = it)))
        }
        if (m.level != MetadataLevel.NONE) {
            val a = state.authorship
            Note(
                if (a.author.isBlank() && a.copyright.isBlank()) {
                    stringResource(R.string.export_metadata_author_unset)
                } else {
                    stringResource(
                        R.string.export_metadata_author,
                        a.author.ifBlank { "—" },
                        a.copyright.ifBlank { "—" },
                    )
                },
            )
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val c = RevealTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = label },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = RevealTheme.typography.body, color = c.foreground, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary,
                checkedThumbColor = c.primaryForeground,
                uncheckedTrackColor = c.muted,
                uncheckedThumbColor = c.mutedForeground,
                uncheckedBorderColor = c.input,
            ),
        )
    }
}

@Composable
private fun DestinationSection(state: ExportUiState, actions: ExportSheetActions) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val set = actions.onOptions
        val destination = options.destination
        Eyebrow(stringResource(R.string.export_destination))
        ChipRow {
            RevealChip(
                stringResource(R.string.export_destination_pictures),
                destination == ExportDestination.Pictures,
                { set(options.copy(destination = ExportDestination.Pictures)) },
                Modifier.testTag(ExportTags.DEST_PICTURES),
            )
            RevealChip(
                stringResource(R.string.export_destination_folder),
                destination is ExportDestination.Folder,
                {
                    val remembered = actions.onRememberedFolder()
                    if (remembered != null) set(options.copy(destination = remembered)) else actions.onPickFolder()
                },
                Modifier.testTag(ExportTags.DEST_FOLDER),
            )
            RevealChip(
                stringResource(R.string.export_destination_share),
                destination == ExportDestination.Share,
                { set(options.copy(destination = ExportDestination.Share)) },
                Modifier.testTag(ExportTags.DEST_SHARE),
            )
        }
        when (destination) {
            ExportDestination.Pictures -> Note(stringResource(R.string.export_destination_pictures_note))

            ExportDestination.Share -> Note(stringResource(R.string.export_destination_share_note))

            is ExportDestination.Folder -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    destination.name.ifBlank { stringResource(R.string.export_destination_folder) },
                    style = RevealTheme.typography.body,
                    color = RevealTheme.colors.foreground,
                    modifier = Modifier.weight(1f),
                )
                GhostButton(text = stringResource(R.string.export_destination_change), onClick = actions.onPickFolder)
            }
        }
    }
}

@Composable
private fun NamingSection(state: ExportUiState, set: (ExportOptions) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        val options = state.options
        val naming = options.naming
        Eyebrow(stringResource(R.string.export_name))
        ChipRow {
            RevealChip(stringResource(R.string.export_name_original), naming.template == NameTemplate.ORIGINAL, {
                set(options.copy(naming = naming.copy(template = NameTemplate.ORIGINAL)))
            })
            RevealChip(stringResource(R.string.export_name_date), naming.template == NameTemplate.DATE, {
                set(options.copy(naming = naming.copy(template = NameTemplate.DATE)))
            })
            RevealChip(stringResource(R.string.export_name_sequence), naming.template == NameTemplate.SEQUENCE, {
                set(options.copy(naming = naming.copy(template = NameTemplate.SEQUENCE)))
            })
        }
        val c = RevealTheme.colors
        TextField(
            value = naming.suffix,
            onValueChange = { set(options.copy(naming = naming.copy(suffix = it.take(MAX_SUFFIX)))) },
            singleLine = true,
            label = { Text(stringResource(R.string.export_name_suffix), style = RevealTheme.typography.ui) },
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
            modifier = Modifier.fillMaxWidth(),
        )
        val sample = ExportNaming.fileName(SAMPLE_NAME, SAMPLE_DATE, 1, naming, options.format)
        Note(stringResource(R.string.export_name_example, sample))
    }
}

@Composable
private fun EstimateRow(state: ExportUiState) {
    val context = LocalContext.current
    val bytes = state.estimateBytes
    val text = when {
        bytes == null -> stringResource(R.string.export_estimate_pending)

        (state.request?.count ?: 1) > 1 -> stringResource(
            R.string.export_estimate_each,
            Formatter.formatShortFileSize(context, bytes),
        )

        else -> stringResource(R.string.export_estimate, Formatter.formatShortFileSize(context, bytes))
    }
    Text(
        text,
        style = RevealTheme.typography.uiMedium,
        color = RevealTheme.colors.foreground,
        modifier = Modifier.testTag(ExportTags.ESTIMATE),
    )
}

@Composable
private fun RunSection(run: ExportRun, actions: ExportSheetActions) {
    Column(verticalArrangement = Arrangement.spacedBy(SECTION_GAP)) {
        if (!run.finished) {
            Text(
                stringResource(R.string.export_progress, minOf(run.done + 1, run.total), run.total),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.foreground,
            )
            LinearProgressIndicator(
                progress = { run.fraction.coerceIn(0f, 1f) },
                color = RevealTheme.colors.primary,
                trackColor = RevealTheme.colors.muted,
                modifier = Modifier.fillMaxWidth().testTag(ExportTags.PROGRESS),
            )
            Note(stringResource(R.string.export_progress_background))
            SecondaryButton(
                text = stringResource(R.string.export_cancel),
                onClick = actions.onCancel,
                modifier = Modifier.fillMaxWidth().testTag(ExportTags.CANCEL),
            )
            return
        }
        val summary = when {
            run.cancelled -> pluralStringResource(R.plurals.export_result_cancelled, run.exported, run.exported)
            else -> pluralStringResource(R.plurals.export_result_done, run.exported, run.exported)
        }
        Text(summary, style = RevealTheme.typography.body, color = RevealTheme.colors.foreground)
        for ((name, failure) in run.failures) {
            Note("$name — ${stringResource(failure.label())}", warning = true)
        }
        if (run.waiting > 0) {
            Note(pluralStringResource(R.plurals.export_result_waiting, run.waiting, run.waiting), warning = true)
        }
        if (run.results.isNotEmpty()) {
            SecondaryButton(
                text = stringResource(R.string.export_share_results),
                onClick = { actions.onShareResults(run.results) },
                modifier = Modifier.fillMaxWidth().testTag(ExportTags.SHARE_RESULTS),
            )
        }
        PrimaryButton(
            text = stringResource(R.string.export_done),
            onClick = actions.onDone,
            modifier = Modifier.fillMaxWidth().testTag(ExportTags.DONE),
        )
    }
}

/** The user-facing status of a failed item. */
fun ExportFailure.label(): Int = when (this) {
    ExportFailure.SOURCE_MISSING -> R.string.export_failure_source
    ExportFailure.UNSUPPORTED -> R.string.export_failure_unsupported
    ExportFailure.DESTINATION_UNAVAILABLE -> R.string.export_failure_destination
    ExportFailure.LOW_STORAGE -> R.string.export_failure_storage
    ExportFailure.OUT_OF_MEMORY -> R.string.export_failure_memory
    ExportFailure.GPU_UNSUPPORTED -> R.string.export_failure_gpu
    ExportFailure.ORIGINAL_ELSEWHERE -> R.string.export_failure_elsewhere
    ExportFailure.FAILED -> R.string.export_failure_other
}

/**
 * `ACTION_SEND` (one file) or `ACTION_SEND_MULTIPLE` with the [uris] in `EXTRA_STREAM` and the `ClipData` (which
 * carries the read grant to the receiving app), typed [mimeType].
 */
fun shareIntent(uris: List<Uri>, mimeType: String): Intent {
    val intent = if (uris.size == 1) {
        Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
    } else {
        Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
    }
    intent.type = mimeType
    intent.clipData =
        ClipData.newRawUri(null, uris.first()).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    return intent
}

private fun folderName(tree: Uri): String =
    runCatching { DocumentsContract.getTreeDocumentId(tree).substringAfterLast(':').substringAfterLast('/') }
        .getOrNull().orEmpty()

private val SECTION_GAP = 12.dp
private const val TENTHS = 10f
private const val FULL_PERCENT = 100f
private val QUALITY_SPEC = SliderSpec(
    min = 1f,
    max = 100f,
    neutral = ExportOptions.DEFAULT_QUALITY.toFloat(),
    step = 1f,
    fineStep = 1f,
    bipolar = false,
)
private const val MEGA = 1_000_000.0
private const val DEFAULT_LONG_EDGE = 3000
private const val DEFAULT_LONG_EDGE_MAX = 8000
private const val DEFAULT_MP_MAX = 24.0
private const val DEFAULT_MP = 12f
private const val DEFAULT_PERCENT = 50
private const val MIN_LONG_EDGE = 256f
private const val LONG_EDGE_STEP = 8f
private const val MIN_MP = 0.5f
private const val MP_STEP = 0.5f
private const val MIN_PERCENT = 5f
private const val MAX_SUFFIX = 40
private const val SAMPLE_NAME = "DSC01234.ARW"
private const val SAMPLE_DATE = "2026:08:07 14:30:12"
