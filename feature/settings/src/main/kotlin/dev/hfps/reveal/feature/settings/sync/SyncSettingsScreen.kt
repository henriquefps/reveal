package dev.hfps.reveal.feature.settings.sync

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.sync.ClaimException
import dev.hfps.reveal.sync.PairException
import dev.hfps.reveal.sync.PairProblem
import dev.hfps.reveal.sync.ServerSwitchException
import dev.hfps.reveal.sync.SyncProblem
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.SyncStatus
import dev.hfps.reveal.sync.account.PairingLink
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.sync.api.DeviceCandidate
import dev.hfps.reveal.sync.work.SyncScheduler
import java.net.URI
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A folder and its originals-backup switch. */
@Immutable
data class BackupFolder(val id: Long, val name: String, val backup: Boolean)

/** Pairing in progress or its failure. */
sealed interface PairingState {
    data object Idle : PairingState

    data object Scanning : PairingState

    data object Working : PairingState

    data class Failed(val problem: PairProblem) : PairingState

    /**
     * The link is for the paired server at another host (ADR 0018 "W5 (f)"): reuse this device's key there only if the
     * user says it is the same server. [protected] false (neither https nor private) adds a warning.
     */
    data class ConfirmKeyReuse(val host: String, val protected: Boolean = false) : PairingState

    /**
     * A code without `server=` paired with another server while paired (ADR 0018 "W5 (g)"): switch (forget this
     * server's sync state) or cancel. A device named [deviceName] is already registered on [serverName].
     */
    data class ConfirmSwitch(val serverName: String, val deviceName: String) : PairingState
}

/** Everything the Sync screen shows. */
@Immutable
data class SyncUiState(
    val account: SyncAccount? = null,
    val options: SyncOptions = SyncOptions(),
    val status: SyncStatus = SyncStatus(),
    val folders: List<BackupFolder> = emptyList(),
    val pairing: PairingState = PairingState.Idle,
    /** "Photos opened from other apps": everything (true) or only edited photos (the default, FIX/W). */
    val handoffBackup: Boolean = false,
    /** Right after pairing: phones of the same model another app paired from (Hoard ADR 0017's one question). */
    val samePhone: List<DeviceCandidate> = emptyList(),
)

/** What the Sync screen can ask for. */
@Immutable
data class SyncActions(
    val onBack: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onScanned: (String) -> Unit = {},
    val onCancelScan: () -> Unit = {},
    val onPaste: (String) -> Unit = {},
    val onOptions: (SyncOptions) -> Unit = {},
    val onFolderBackup: (Long, Boolean) -> Unit = { _, _ -> },
    val onHandoffBackup: (Boolean) -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onUnpair: () -> Unit = {},
    val onThisDevice: () -> Unit = {},
    val onSamePhone: (String) -> Unit = {},
    val onNotSamePhone: () -> Unit = {},
    /** The answer to [PairingState.ConfirmKeyReuse]: true = same server, false = pair as a new device. */
    val onKeyReuse: (Boolean) -> Unit = {},
    val onKeyReuseCancel: () -> Unit = {},
    /**
     * After the Unpair confirmation opened by "Switch server" ([PairProblem.PAIRED_ELSEWHERE]): pair, and only then
     * forget the paired server.
     */
    val onSwitchServer: () -> Unit = {},
    /** The answer to [PairingState.ConfirmSwitch]: true = switch, false = keep the paired server. */
    val onConfirmSwitch: (Boolean) -> Unit = {},
)

/** Test tags of the Sync screen. */
object SyncTags {
    const val SCREEN = "sync_screen"
    const val SCAN = "sync_scan"
    const val SCANNER = "sync_scanner"
    const val LINK = "sync_link"
    const val PAIR = "sync_pair"
    const val PAIR_ERROR = "sync_pair_error"
    const val SERVER = "sync_server"
    const val STATUS = "sync_status"
    const val PREVIEWS = "sync_previews"
    const val ORIGINALS = "sync_originals"
    const val EDITED_PREVIEWS = "sync_edited_previews"
    const val WIFI_ONLY = "sync_wifi_only"
    const val SYNC_NOW = "sync_now"
    const val UNPAIR = "sync_unpair"
    const val UNPAIR_CONFIRM = "sync_unpair_confirm"

    const val HANDOFF_BACKUP = "sync_handoff_backup"
    const val THIS_DEVICE = "sync_this_device"
    const val SAME_PHONE = "sync_same_phone"
    const val SAME_PHONE_YES = "sync_same_phone_yes"
    const val SAME_PHONE_NO = "sync_same_phone_no"
    const val PAIR_AGAIN = "sync_pair_again"
    const val KEY_REUSE = "sync_key_reuse"
    const val KEY_REUSE_YES = "sync_key_reuse_yes"
    const val KEY_REUSE_NO = "sync_key_reuse_no"
    const val SWITCH_SERVER = "sync_switch_server"
    const val SWITCH = "sync_switch"
    const val SWITCH_YES = "sync_switch_yes"
    const val SWITCH_NO = "sync_switch_no"

    fun folder(id: Long) = "sync_folder_$id"
}

@HiltViewModel
class SyncSettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sync: SyncRepository,
    library: LibraryRepository,
) : ViewModel() {
    private val pairing = MutableStateFlow<PairingState>(PairingState.Idle)
    private val lib = library

    val state: StateFlow<SyncUiState> = combine(
        sync.account,
        sync.options,
        sync.status,
        combine(library.sources(), library.handoffBackup(), ::Pair),
        combine(pairing, sync.pairCandidates, ::Pair),
    ) { account, options, status, (sources, handoffBackup), (pairingState, samePhone) ->
        SyncUiState(
            account = account,
            options = options,
            status = status,
            folders = sources.map { BackupFolder(it.id, it.name, it.backup) },
            pairing = pairingState,
            handoffBackup = handoffBackup,
            samePhone = samePhone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), SyncUiState())

    fun scan() = pairing.update { PairingState.Scanning }

    fun cancelScan() = pairing.update { PairingState.Idle }

    /** The link waiting for the answer to [PairingState.ConfirmKeyReuse]. */
    private var confirming: PairingLink? = null

    /** The code for another server waiting for "Switch server" ([PairProblem.PAIRED_ELSEWHERE]). */
    private var switching: PairingLink? = null

    /**
     * Pairs with the link of a scanned QR or a pasted text; a link for the paired server at another host asks first
     * whether it is the same server ([PairingState.ConfirmKeyReuse]).
     */
    fun pair(text: String) {
        val link = PairingLink.parse(text)
        if (link == null) {
            pairing.update { PairingState.Failed(PairProblem.INVALID_LINK) }
            return
        }
        pairing.update { PairingState.Working }
        viewModelScope.launch {
            val question = sync.keyReuseQuestion(link)
            if (question != null) {
                confirming = link
                pairing.update { PairingState.ConfirmKeyReuse(question.host, question.protected) }
            } else {
                pair(link, SyncRepository.KeyReuse.AUTO)
            }
        }
    }

    /**
     * "Switch server", confirmed in the Unpair dialog: pair with the code, and only once that worked forget the paired
     * server. A failed pairing keeps the paired server and shows the error.
     */
    fun switchServer() {
        val link = switching ?: return
        switching = null
        pairing.update { PairingState.Working }
        viewModelScope.launch {
            try {
                sync.pairSwitching(link)
                pairing.update { PairingState.Idle }
                SyncScheduler.now(context)
            } catch (e: PairException) {
                pairing.update { PairingState.Failed(e.problem) }
            }
        }
    }

    /** The answer to [PairingState.ConfirmSwitch]: switch to the server just paired, or keep the paired one. */
    fun confirmSwitch(switch: Boolean) {
        if (!switch) {
            sync.cancelSwitch()
            pairing.update { PairingState.Idle }
            return
        }
        pairing.update { PairingState.Working }
        viewModelScope.launch {
            if (sync.switchServer() != null) {
                pairing.update { PairingState.Idle }
                SyncScheduler.now(context)
            } else {
                pairing.update { PairingState.Idle }
            }
        }
    }

    override fun onCleared() {
        // A switch nobody answered: the paired server stays, the other server's key is dropped.
        sync.cancelSwitch()
    }

    /** The user's answer: the same server (reuse the key) or not (a normal pairing as a new device). */
    fun answerKeyReuse(same: Boolean) {
        val link = confirming ?: return
        confirming = null
        pairing.update { PairingState.Working }
        viewModelScope.launch {
            pair(link, if (same) SyncRepository.KeyReuse.CONFIRMED else SyncRepository.KeyReuse.DECLINED)
        }
    }

    fun cancelKeyReuse() {
        confirming = null
        pairing.update { PairingState.Idle }
    }

    private suspend fun pair(link: PairingLink, keyReuse: SyncRepository.KeyReuse) {
        try {
            sync.pair(link, keyReuse)
            pairing.update { PairingState.Idle }
            SyncScheduler.now(context)
        } catch (e: PairException) {
            switching = link.takeIf { e.problem == PairProblem.PAIRED_ELSEWHERE }
            pairing.update { PairingState.Failed(e.problem) }
        } catch (e: ServerSwitchException) {
            pairing.update { PairingState.ConfirmSwitch(e.serverName, e.deviceName) }
        }
    }

    fun setOptions(options: SyncOptions) {
        sync.setOptions(options)
        SyncScheduler.blobs(context)
    }

    fun setFolderBackup(id: Long, backup: Boolean) {
        viewModelScope.launch {
            lib.setBackup(id, backup)
            SyncScheduler.blobs(context)
        }
    }

    fun setHandoffBackup(everything: Boolean) {
        viewModelScope.launch {
            lib.setHandoffBackup(everything)
            SyncScheduler.blobs(context)
        }
    }

    fun syncNow() {
        SyncScheduler.now(context)
        SyncScheduler.blobs(context)
    }

    fun unpair() {
        viewModelScope.launch { sync.unpair() }
    }

    /** "Same phone": joins the other app's phone (Hoard ADR 0017); a refusal leaves it for the This device screen. */
    fun samePhone(physicalDeviceId: String) {
        viewModelScope.launch {
            try {
                sync.claim(physicalDeviceId)
            } catch (@Suppress("SwallowedException") e: ClaimException) {
                sync.dismissCandidates()
            }
        }
    }

    fun notSamePhone() = sync.dismissCandidates()

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun SyncSettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onThisDevice: () -> Unit = {},
    viewModel: SyncSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SyncSettingsContent(
        state = state,
        actions = SyncActions(
            onBack = onBack,
            onScan = viewModel::scan,
            onScanned = viewModel::pair,
            onCancelScan = viewModel::cancelScan,
            onPaste = viewModel::pair,
            onOptions = viewModel::setOptions,
            onFolderBackup = viewModel::setFolderBackup,
            onHandoffBackup = viewModel::setHandoffBackup,
            onSyncNow = viewModel::syncNow,
            onUnpair = viewModel::unpair,
            onThisDevice = onThisDevice,
            onSamePhone = viewModel::samePhone,
            onNotSamePhone = viewModel::notSamePhone,
            onKeyReuse = viewModel::answerKeyReuse,
            onKeyReuseCancel = viewModel::cancelKeyReuse,
            onSwitchServer = viewModel::switchServer,
            onConfirmSwitch = viewModel::confirmSwitch,
        ),
        modifier = modifier,
    )
}

/** Stateless Sync screen (screenshots, tests). [now] formats "last sync" (fixed in screenshots). */
@Composable
fun SyncSettingsContent(
    state: SyncUiState,
    actions: SyncActions,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    RevealScaffold(
        modifier = modifier.testTag(SyncTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.sync_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val account = state.account
            if (account == null) {
                Unpaired(state.pairing, actions)
            } else {
                Paired(account, state, actions, now)
            }
        }
        if (state.account != null) {
            SamePhoneDialog(state.samePhone, onJoin = actions.onSamePhone, onDismiss = actions.onNotSamePhone)
        }
        val pairing = state.pairing
        if (pairing is PairingState.ConfirmKeyReuse) {
            KeyReuseDialog(pairing, onAnswer = actions.onKeyReuse, onDismiss = actions.onKeyReuseCancel)
        }
        if (pairing is PairingState.ConfirmSwitch && state.account != null) {
            SwitchServerDialog(pairing, state.account, onAnswer = actions.onConfirmSwitch)
        }
    }
}

@Composable
private fun Unpaired(pairing: PairingState, actions: SyncActions, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Eyebrow(stringResource(R.string.sync_section_pair))
            Text(
                stringResource(R.string.sync_intro),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.foreground,
            )
            Text(
                stringResource(R.string.sync_intro_server),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
            )
        }
        PairControls(pairing, actions)
    }
}

/** "Pair with a new code" while paired (the same server at a new address, ADR 0018 "W5 (f)"). */
@Composable
private fun PairAgain(
    open: Boolean,
    onOpen: () -> Unit,
    pairing: PairingState,
    actions: SyncActions,
    onSwitchServer: () -> Unit,
) {
    if (open || pairing != PairingState.Idle) {
        Text(
            stringResource(R.string.sync_pair_again_hint),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        PairControls(pairing, actions, onSwitchServer)
    } else {
        GhostButton(
            text = stringResource(R.string.sync_pair_again),
            onClick = onOpen,
            icon = PhosphorRegular.QrCode,
            modifier = Modifier.padding(horizontal = 16.dp).testTag(SyncTags.PAIR_AGAIN),
        )
    }
}

/** Scan or paste a pairing link (unpaired, or pairing again with a new code). */
@Composable
private fun PairControls(pairing: PairingState, actions: SyncActions, onSwitchServer: (() -> Unit)? = null) {
    var link by rememberSaveable { mutableStateOf("") }
    val busy = pairing == PairingState.Working || pairing is PairingState.ConfirmKeyReuse ||
        pairing is PairingState.ConfirmSwitch
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (pairing == PairingState.Scanning) {
            PairingScanner(onLink = actions.onScanned, onClose = actions.onCancelScan)
        } else {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.sync_scan),
                    onClick = actions.onScan,
                    icon = PhosphorRegular.QrCode,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().testTag(SyncTags.SCAN),
                )
                LinkField(link, { link = it.take(MAX_LINK) })
                SecondaryButton(
                    text = stringResource(if (busy) R.string.sync_pairing else R.string.sync_pair),
                    onClick = { actions.onPaste(link) },
                    icon = PhosphorRegular.LinkSimple,
                    enabled = link.isNotBlank() && !busy,
                    modifier = Modifier.fillMaxWidth().testTag(SyncTags.PAIR),
                )
                if (pairing is PairingState.Failed) {
                    Text(
                        stringResource(pairing.problem.text()),
                        style = RevealTheme.typography.ui,
                        color = RevealTheme.colors.destructive,
                        modifier = Modifier.testTag(SyncTags.PAIR_ERROR),
                    )
                    if (pairing.problem == PairProblem.PAIRED_ELSEWHERE && onSwitchServer != null) {
                        GhostButton(
                            text = stringResource(R.string.sync_switch_server),
                            onClick = onSwitchServer,
                            icon = PhosphorRegular.Swap,
                            modifier = Modifier.testTag(SyncTags.SWITCH_SERVER),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LinkField(value: String, onChange: (String) -> Unit) {
    val c = RevealTheme.colors
    TextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(R.string.sync_link_label), style = RevealTheme.typography.ui) },
        placeholder = { Text(stringResource(R.string.sync_link_hint), style = RevealTheme.typography.ui) },
        textStyle = RevealTheme.typography.body.copy(color = c.foreground),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = c.primary,
            unfocusedIndicatorColor = c.input,
            cursorColor = c.primary,
            focusedLabelColor = c.mutedForeground,
            unfocusedLabelColor = c.mutedForeground,
            focusedPlaceholderColor = c.mutedForeground,
            unfocusedPlaceholderColor = c.mutedForeground,
        ),
        modifier = Modifier.fillMaxWidth().testTag(SyncTags.LINK),
    )
}

@Suppress("LongMethod")
@Composable
private fun Paired(
    account: SyncAccount,
    state: SyncUiState,
    actions: SyncActions,
    now: Long,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status = state.status
    var confirmUnpair by rememberSaveable { mutableStateOf(false) }
    // The Unpair confirmation opened by "Switch server": confirming it also pairs with the other server's code.
    var switchServer by rememberSaveable { mutableStateOf(false) }
    // "Pair with a new code" (the same server at a new address, ADR 0018 "W5 (f)"): closes once the account changes.
    var pairAgain by rememberSaveable(account.url, account.deviceId) { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Eyebrow(stringResource(R.string.sync_section_server), Modifier.padding(horizontal = 16.dp))
        RevealListRow(
            title = account.serverName.ifBlank { stringResource(R.string.sync_server_unnamed) },
            subtitle = host(account.url),
            icon = PhosphorRegular.HardDrives,
            modifier = Modifier.testTag(SyncTags.SERVER),
        )
        if (account.url.startsWith("http://", ignoreCase = true)) {
            Text(
                stringResource(R.string.sync_cleartext),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.warning,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        PairAgain(
            open = pairAgain,
            onOpen = { pairAgain = true },
            pairing = state.pairing,
            actions = actions,
            onSwitchServer = {
                switchServer = true
                confirmUnpair = true
            },
        )
        RevealListRow(
            title = stringResource(R.string.device_title),
            subtitle = stringResource(R.string.device_row_subtitle),
            icon = PhosphorRegular.DeviceMobile,
            onClick = actions.onThisDevice,
            trailing = {
                Icon(PhosphorRegular.CaretRight, null, tint = RevealTheme.colors.mutedForeground)
            },
            modifier = Modifier.testTag(SyncTags.THIS_DEVICE),
        )
        RevealRowDivider()
        RevealListRow(
            title = statusTitle(status, now),
            subtitle = statusSubtitle(status),
            icon = when {
                status.problem != null -> PhosphorRegular.CloudSlash
                status.pending > 0 || status.running -> PhosphorRegular.CloudArrowUp
                else -> PhosphorRegular.CloudCheck
            },
            iconTint = if (status.problem != null) RevealTheme.colors.warning else RevealTheme.colors.mutedForeground,
            modifier = Modifier.testTag(SyncTags.STATUS),
        )
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrimaryButton(
                text = stringResource(R.string.sync_now),
                onClick = actions.onSyncNow,
                icon = PhosphorRegular.ArrowsClockwise,
                enabled = !status.running,
                modifier = Modifier.testTag(SyncTags.SYNC_NOW),
            )
        }

        Eyebrow(stringResource(R.string.sync_section_what), Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
        Text(
            stringResource(R.string.sync_edits_always),
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        val options = state.options
        SwitchRow(
            title = stringResource(R.string.sync_previews),
            subtitle = stringResource(R.string.sync_previews_subtitle),
            checked = options.previews,
            onChange = { actions.onOptions(options.copy(previews = it)) },
            tag = SyncTags.PREVIEWS,
        )
        SwitchRow(
            title = stringResource(R.string.sync_edited_previews),
            subtitle = stringResource(R.string.sync_edited_previews_subtitle),
            checked = options.editedPreviews,
            onChange = { actions.onOptions(options.copy(editedPreviews = it)) },
            tag = SyncTags.EDITED_PREVIEWS,
        )
        SwitchRow(
            title = stringResource(R.string.sync_originals),
            subtitle = stringResource(R.string.sync_originals_subtitle),
            checked = options.originals,
            onChange = { actions.onOptions(options.copy(originals = it)) },
            tag = SyncTags.ORIGINALS,
        )
        SwitchRow(
            title = stringResource(R.string.sync_wifi_only),
            subtitle = null,
            checked = options.originalsWifiOnly,
            enabled = options.originals,
            onChange = { actions.onOptions(options.copy(originalsWifiOnly = it)) },
            tag = SyncTags.WIFI_ONLY,
        )
        if (options.originals) {
            Eyebrow(
                stringResource(R.string.sync_section_folders),
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            )
            Text(
                stringResource(R.string.sync_folders_hint),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            for (folder in state.folders) {
                // On = "Everything", off = "Only photos you edit" (the default for new folders, FIX/V).
                SwitchRow(
                    title = folder.name,
                    subtitle = stringResource(
                        if (folder.backup) R.string.sync_folder_everything else R.string.sync_folder_edited,
                    ),
                    checked = folder.backup,
                    onChange = { actions.onFolderBackup(folder.id, it) },
                    tag = SyncTags.folder(folder.id),
                )
            }
            // Hand-offs: the same two modes, "Only photos you edit" by default (FIX/W).
            SwitchRow(
                title = stringResource(R.string.sync_handoff_backup),
                subtitle = stringResource(
                    if (state.handoffBackup) R.string.sync_folder_everything else R.string.sync_folder_edited,
                ),
                checked = state.handoffBackup,
                onChange = actions.onHandoffBackup,
                tag = SyncTags.HANDOFF_BACKUP,
            )
        }

        Eyebrow(
            stringResource(R.string.sync_section_storage),
            Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        )
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StorageLine(stringResource(R.string.sync_uploaded, size(context, status.uploadedBytes)))
            if (status.uploadsPending > 0) {
                StorageLine(
                    pluralStringResource(
                        R.plurals.sync_waiting,
                        status.uploadsPending,
                        size(context, status.uploadBytesPending),
                        status.uploadsPending,
                    ),
                )
            }
            StorageLine(pluralStringResource(R.plurals.sync_server_photos, status.serverPhotos, status.serverPhotos))
        }

        Column(Modifier.padding(16.dp)) {
            GhostButton(
                text = stringResource(R.string.sync_unpair),
                onClick = {
                    switchServer = false
                    confirmUnpair = true
                },
                modifier = Modifier.testTag(SyncTags.UNPAIR),
            )
        }
        if (confirmUnpair) {
            AlertDialog(
                onDismissRequest = { confirmUnpair = false },
                containerColor = RevealTheme.colors.card,
                shape = RevealTheme.shapes.card,
                title = { Text(stringResource(R.string.sync_unpair_title), style = RevealTheme.typography.title) },
                text = {
                    Text(
                        stringResource(R.string.sync_unpair_body),
                        style = RevealTheme.typography.body,
                        color = RevealTheme.colors.mutedForeground,
                    )
                },
                confirmButton = {
                    PrimaryButton(
                        text = stringResource(R.string.sync_unpair),
                        onClick = {
                            confirmUnpair = false
                            if (switchServer) actions.onSwitchServer() else actions.onUnpair()
                            switchServer = false
                        },
                        modifier = Modifier.testTag(SyncTags.UNPAIR_CONFIRM),
                    )
                },
                dismissButton = { GhostButton(stringResource(R.string.sync_cancel), { confirmUnpair = false }) },
            )
        }
    }
}

@Composable
private fun StorageLine(text: String) {
    Text(text, style = RevealTheme.typography.body, color = RevealTheme.colors.foreground)
}

/** A titled switch row (Sync, Settings' XMP section). */
@Composable
internal fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    tag: String,
    enabled: Boolean = true,
) {
    val c = RevealTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 16.dp, vertical = 4.dp)
            .semantics { contentDescription = title },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = RevealTheme.typography.body, color = if (enabled) c.foreground else c.mutedForeground)
            if (subtitle != null) Text(subtitle, style = RevealTheme.typography.ui, color = c.mutedForeground)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedTrackColor = c.primary,
                checkedThumbColor = c.primaryForeground,
                uncheckedTrackColor = c.muted,
                uncheckedThumbColor = c.mutedForeground,
                uncheckedBorderColor = c.input,
            ),
            modifier = Modifier.testTag(tag),
        )
    }
}

@Composable
private fun statusTitle(status: SyncStatus, now: Long): String {
    val last = status.lastSyncAt
    return when {
        status.running -> stringResource(R.string.sync_running)

        last == null -> stringResource(R.string.sync_never)

        else -> stringResource(
            R.string.sync_last,
            DateUtils.getRelativeTimeSpanString(last, now, DateUtils.MINUTE_IN_MILLIS).toString(),
        )
    }
}

@Composable
private fun statusSubtitle(status: SyncStatus): String = status.problem?.let { stringResource(it.text()) }
    ?: if (status.pending > 0) {
        pluralStringResource(R.plurals.sync_pending, status.pending, status.pending)
    } else {
        stringResource(R.string.sync_up_to_date)
    }

private fun SyncProblem.text(): Int = when (this) {
    SyncProblem.OFFLINE -> R.string.sync_problem_offline
    SyncProblem.UNAUTHORIZED -> R.string.sync_problem_unauthorized
    SyncProblem.SERVER_CHANGED -> R.string.sync_problem_server_changed
    SyncProblem.STORAGE_NOT_CONFIGURED -> R.string.sync_problem_storage
    SyncProblem.SERVER_ERROR -> R.string.sync_problem_server
}

private fun PairProblem.text(): Int = when (this) {
    PairProblem.INVALID_LINK -> R.string.sync_pair_invalid_link
    PairProblem.INVALID_TOKEN -> R.string.sync_pair_invalid_token
    PairProblem.OTHER_SERVER -> R.string.sync_pair_other_server
    PairProblem.UNREACHABLE -> R.string.sync_pair_unreachable
    PairProblem.SERVER_ERROR -> R.string.sync_pair_server_error
    PairProblem.PAIRED_ELSEWHERE -> R.string.sync_pair_paired_elsewhere
}

private fun host(url: String): String = runCatching {
    URI(url).let { uri ->
        uri.host +
            (if (uri.port > 0) ":${uri.port}" else "")
    }
}
    .getOrDefault(url)

private fun size(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

private const val MAX_LINK = 2000

/** "Same server at <host>?": reuse this device's key at another host (a warning when not https nor private). */
@Composable
private fun KeyReuseDialog(question: PairingState.ConfirmKeyReuse, onAnswer: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val host = question.host
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        modifier = Modifier.testTag(SyncTags.KEY_REUSE),
        title = { Text(stringResource(R.string.sync_key_reuse_title, host), style = RevealTheme.typography.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.sync_key_reuse_body),
                    style = RevealTheme.typography.body,
                    color = RevealTheme.colors.mutedForeground,
                )
                if (!question.protected) {
                    Text(
                        stringResource(R.string.sync_key_reuse_cleartext),
                        style = RevealTheme.typography.ui,
                        color = RevealTheme.colors.warning,
                    )
                }
            }
        },
        confirmButton = {
            PrimaryButton(
                text = stringResource(R.string.sync_key_reuse_yes),
                onClick = { onAnswer(true) },
                modifier = Modifier.testTag(SyncTags.KEY_REUSE_YES),
            )
        },
        dismissButton = {
            GhostButton(
                text = stringResource(R.string.sync_key_reuse_no),
                onClick = { onAnswer(false) },
                modifier = Modifier.testTag(SyncTags.KEY_REUSE_NO),
            )
        },
    )
}

/**
 * "This code is for another server": a code without `server=` paired with another server while paired (ADR 0018
 * "W5 (g)"). Switch = forget this server's sync state and keep the new pairing; Cancel = keep the paired server (the
 * device the other server registered stays there: the dialog says so).
 */
@Composable
private fun SwitchServerDialog(
    question: PairingState.ConfirmSwitch,
    account: SyncAccount,
    onAnswer: (Boolean) -> Unit,
) {
    val unnamed = stringResource(R.string.sync_server_unnamed)
    val current = account.serverName.ifBlank { unnamed }
    val other = question.serverName.ifBlank { unnamed }
    AlertDialog(
        onDismissRequest = { onAnswer(false) },
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        modifier = Modifier.testTag(SyncTags.SWITCH),
        title = { Text(stringResource(R.string.sync_switch_title), style = RevealTheme.typography.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.sync_switch_body, other, current),
                    style = RevealTheme.typography.body,
                    color = RevealTheme.colors.mutedForeground,
                )
                Text(
                    stringResource(R.string.sync_switch_registered, question.deviceName, other),
                    style = RevealTheme.typography.ui,
                    color = RevealTheme.colors.mutedForeground,
                )
            }
        },
        confirmButton = {
            PrimaryButton(
                text = stringResource(R.string.sync_switch_yes),
                onClick = { onAnswer(true) },
                modifier = Modifier.testTag(SyncTags.SWITCH_YES),
            )
        },
        dismissButton = {
            GhostButton(
                text = stringResource(R.string.sync_cancel),
                onClick = { onAnswer(false) },
                modifier = Modifier.testTag(SyncTags.SWITCH_NO),
            )
        },
    )
}
