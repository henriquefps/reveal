package dev.hfps.reveal.feature.settings.sync

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.sync.ClaimException
import dev.hfps.reveal.sync.ClaimProblem
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.ThisDevice
import dev.hfps.reveal.sync.api.DeviceCandidate
import dev.hfps.reveal.sync.api.DeviceEntry
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the This device screen shows (Hoard ADR 0017, Reveal ADR 0027). */
@Immutable
sealed interface ThisDeviceState {
    data object Loading : ThisDeviceState

    data object Unreachable : ThisDeviceState

    data class Loaded(val device: ThisDevice, val working: Boolean = false, val problem: ClaimProblem? = null) :
        ThisDeviceState
}

/** What the This device screen can ask for. */
@Immutable
data class ThisDeviceActions(
    val onBack: () -> Unit = {},
    val onRetry: () -> Unit = {},
    val onJoin: (String) -> Unit = {},
    val onLeave: () -> Unit = {},
    val onDismissProblem: () -> Unit = {},
)

/** Test tags of the This device screen. */
object ThisDeviceTags {
    const val SCREEN = "this_device_screen"
    const val DEVICE = "this_device_row"
    const val CULLING = "this_device_culling"
    const val LEAVE = "this_device_leave"
    const val RETRY = "this_device_retry"
    const val UNSUPPORTED = "this_device_unsupported"

    fun app(id: String) = "this_device_app_$id"

    fun candidate(id: String) = "this_device_candidate_$id"

    fun join(id: String) = "this_device_join_$id"
}

@HiltViewModel
class ThisDeviceViewModel @Inject constructor(private val sync: SyncRepository) : ViewModel() {
    private val mutable = MutableStateFlow<ThisDeviceState>(ThisDeviceState.Loading)
    val state: StateFlow<ThisDeviceState> = mutable.asStateFlow()

    fun load() {
        mutable.update { if (it is ThisDeviceState.Loaded) it.copy(working = true) else ThisDeviceState.Loading }
        viewModelScope.launch {
            val device = try {
                sync.thisDevice()
            } catch (@Suppress("SwallowedException") e: IOException) {
                null
            }
            mutable.update { if (device == null) ThisDeviceState.Unreachable else ThisDeviceState.Loaded(device) }
        }
    }

    /** Joins the phone [physicalDeviceId] (null: leaves the group for one of its own). */
    fun claim(physicalDeviceId: String?) {
        val current = mutable.value as? ThisDeviceState.Loaded ?: return
        mutable.update { current.copy(working = true, problem = null) }
        viewModelScope.launch {
            try {
                val device = sync.claim(physicalDeviceId)
                mutable.update { ThisDeviceState.Loaded(device) }
            } catch (e: ClaimException) {
                mutable.update { current.copy(working = false, problem = e.problem) }
            }
        }
    }

    fun dismissProblem() = mutable.update { (it as? ThisDeviceState.Loaded)?.copy(problem = null) ?: it }
}

@Composable
fun ThisDeviceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ThisDeviceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.load() }
    ThisDeviceContent(
        state = state,
        actions = ThisDeviceActions(
            onBack = onBack,
            onRetry = viewModel::load,
            onJoin = { viewModel.claim(it) },
            onLeave = { viewModel.claim(null) },
            onDismissProblem = viewModel::dismissProblem,
        ),
        modifier = modifier,
    )
}

/** Stateless This device screen (screenshots, tests). */
@Composable
fun ThisDeviceContent(state: ThisDeviceState, actions: ThisDeviceActions, modifier: Modifier = Modifier) {
    RevealScaffold(
        modifier = modifier.testTag(ThisDeviceTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.device_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                ThisDeviceState.Loading -> Hint(stringResource(R.string.device_loading))

                ThisDeviceState.Unreachable -> {
                    Hint(stringResource(R.string.device_unreachable))
                    Column(Modifier.padding(horizontal = 16.dp)) {
                        SecondaryButton(
                            text = stringResource(R.string.device_retry),
                            onClick = actions.onRetry,
                            icon = PhosphorRegular.ArrowsClockwise,
                            modifier = Modifier.testTag(ThisDeviceTags.RETRY),
                        )
                    }
                }

                is ThisDeviceState.Loaded -> Loaded(state, actions)
            }
        }
    }
}

@Suppress("LongMethod")
@Composable
private fun Loaded(state: ThisDeviceState.Loaded, actions: ThisDeviceActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val device = state.device
        Eyebrow(stringResource(R.string.device_section_this), Modifier.padding(horizontal = 16.dp))
        RevealListRow(
            title = device.name.ifBlank { stringResource(R.string.device_unnamed) },
            subtitle = listOfNotNull(device.app?.let { appName(it) }, device.model).joinToString(" · ")
                .ifBlank { null },
            icon = PhosphorRegular.DeviceMobile,
            modifier = Modifier.testTag(ThisDeviceTags.DEVICE),
        )
        if (!device.supported) {
            Hint(stringResource(R.string.device_unsupported), Modifier.testTag(ThisDeviceTags.UNSUPPORTED))
            return
        }
        if ("culling" in device.features) {
            Hint(stringResource(R.string.device_culling), Modifier.testTag(ThisDeviceTags.CULLING))
        }

        Eyebrow(
            stringResource(R.string.device_section_phone),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Hint(stringResource(R.string.device_phone_hint))
        val others = device.others
        if (others.isEmpty()) {
            Hint(stringResource(R.string.device_alone))
        } else {
            for (other in others) OtherApp(other)
            Column(Modifier.padding(horizontal = 16.dp)) {
                GhostButton(
                    text = stringResource(R.string.device_leave),
                    onClick = actions.onLeave,
                    enabled = !state.working,
                    modifier = Modifier.testTag(ThisDeviceTags.LEAVE),
                )
            }
        }

        if (device.candidates.isNotEmpty()) {
            Eyebrow(
                stringResource(R.string.device_section_candidates),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Hint(stringResource(R.string.device_candidates_hint))
            for (candidate in device.candidates) Candidate(candidate, enabled = !state.working, onJoin = actions.onJoin)
        }

        state.problem?.let { problem ->
            AlertDialog(
                onDismissRequest = actions.onDismissProblem,
                containerColor = RevealTheme.colors.card,
                shape = RevealTheme.shapes.card,
                title = { Text(stringResource(R.string.device_claim_failed), style = RevealTheme.typography.title) },
                text = {
                    Text(
                        stringResource(problem.text()),
                        style = RevealTheme.typography.body,
                        color = RevealTheme.colors.mutedForeground,
                    )
                },
                confirmButton = { PrimaryButton(stringResource(R.string.device_ok), actions.onDismissProblem) },
            )
        }
    }
}

@Composable
private fun OtherApp(entry: DeviceEntry) {
    RevealListRow(
        title = entry.app?.let { appName(it) } ?: stringResource(R.string.device_app_unknown),
        subtitle = listOfNotNull(
            entry.name.takeIf { it.isNotBlank() },
            listOfNotNull(entry.manufacturer, entry.model).joinToString(" ").takeIf { it.isNotBlank() },
        ).joinToString(" · ").ifBlank { null },
        icon = PhosphorRegular.LinkSimple,
        modifier = Modifier.testTag(ThisDeviceTags.app(entry.id)),
    )
}

@Composable
private fun Candidate(candidate: DeviceCandidate, enabled: Boolean, onJoin: (String) -> Unit) {
    RevealListRow(
        title = candidate.name.ifBlank { stringResource(R.string.device_unnamed) },
        subtitle = candidateSubtitle(candidate),
        icon = PhosphorRegular.DeviceMobile,
        modifier = Modifier.testTag(ThisDeviceTags.candidate(candidate.physicalDeviceId)),
        trailing = {
            SecondaryButton(
                text = stringResource(R.string.device_join),
                onClick = { onJoin(candidate.physicalDeviceId) },
                enabled = enabled,
                modifier = Modifier.testTag(ThisDeviceTags.join(candidate.physicalDeviceId)),
            )
        },
    )
}

/** "Hoard · Google Pixel 8 · same model". */
@Composable
internal fun candidateSubtitle(candidate: DeviceCandidate): String {
    val apps = candidate.devices.mapNotNull { it.app }.distinct().map { appName(it) }
    val model = candidate.devices.firstNotNullOfOrNull { d ->
        listOfNotNull(d.manufacturer, d.model).joinToString(" ").takeIf { it.isNotBlank() }
    }
    val match = if (candidate.modelMatch) stringResource(R.string.device_same_model) else null
    return listOfNotNull(apps.joinToString(", ").ifBlank { null }, model, match).joinToString(" · ")
}

/** An app id as people read it: `reveal` -> Reveal, `hoard-debug` -> Hoard (debug). */
@Composable
internal fun appName(app: String): String {
    val name = app.substringBefore('-')
    val variant = app.substringAfter('-', "")
    val base = when (name) {
        "reveal" -> stringResource(R.string.device_app_reveal)
        "hoard" -> stringResource(R.string.device_app_hoard)
        else -> name
    }
    return if (variant.isEmpty()) base else stringResource(R.string.device_app_variant, base, variant)
}

@Composable
private fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = RevealTheme.typography.ui,
        color = RevealTheme.colors.mutedForeground,
        modifier = modifier.padding(horizontal = 16.dp),
    )
}

private fun ClaimProblem.text(): Int = when (this) {
    ClaimProblem.SAME_APP -> R.string.device_claim_same_app
    ClaimProblem.NOT_FOUND -> R.string.device_claim_not_found
    ClaimProblem.UNREACHABLE -> R.string.device_unreachable
    ClaimProblem.SERVER_ERROR -> R.string.device_claim_error
}

/**
 * The one question of Hoard ADR 0017 right after pairing: one phone of the same model has another app (Hoard) paired
 * -> "Is this the same phone as Hoard on Pixel 8?"; several -> which one, or none.
 */
@Composable
fun SamePhoneDialog(candidates: List<DeviceCandidate>, onJoin: (String) -> Unit, onDismiss: () -> Unit) {
    if (candidates.isEmpty()) return
    val single = candidates.singleOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        modifier = Modifier.testTag(SyncTags.SAME_PHONE),
        title = { Text(stringResource(R.string.device_same_phone_title), style = RevealTheme.typography.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (single != null) {
                    val apps = single.devices.mapNotNull { it.app }.distinct().map { appName(it) }
                        .ifEmpty { listOf(stringResource(R.string.device_app_unknown)) }
                    Text(
                        stringResource(R.string.device_same_phone_one, apps.joinToString(", "), single.name),
                        style = RevealTheme.typography.body,
                        color = RevealTheme.colors.mutedForeground,
                    )
                } else {
                    Text(
                        stringResource(R.string.device_same_phone_many),
                        style = RevealTheme.typography.body,
                        color = RevealTheme.colors.mutedForeground,
                    )
                    for (candidate in candidates) {
                        RevealListRow(
                            title = candidate.name,
                            subtitle = candidateSubtitle(candidate),
                            onClick = { onJoin(candidate.physicalDeviceId) },
                            modifier = Modifier.testTag(ThisDeviceTags.candidate(candidate.physicalDeviceId)),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (single != null) {
                PrimaryButton(
                    text = stringResource(R.string.device_same_phone_yes),
                    onClick = { onJoin(single.physicalDeviceId) },
                    modifier = Modifier.testTag(SyncTags.SAME_PHONE_YES),
                )
            }
        },
        dismissButton = {
            GhostButton(
                text = stringResource(
                    if (single != null) R.string.device_same_phone_no else R.string.device_same_phone_none,
                ),
                onClick = onDismiss,
                modifier = Modifier.testTag(SyncTags.SAME_PHONE_NO),
            )
        },
    )
}
