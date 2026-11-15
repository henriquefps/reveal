package dev.hfps.reveal

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.reveal.data.handoff.HandoffBatches
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.ServerIssue
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.preset.PresetFilesIo
import dev.hfps.reveal.data.preset.PresetIntents
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.isDark
import dev.hfps.reveal.feature.editor.EditorRoute
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.editor.presets.PresetLinkHost
import dev.hfps.reveal.feature.editor.presets.PresetLinkViewModel
import dev.hfps.reveal.feature.editor.presets.PresetMessages
import dev.hfps.reveal.feature.library.HandoffServerSheet
import dev.hfps.reveal.feature.library.LibraryRoute
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.feature.settings.SyncSettingsRoute
import dev.hfps.reveal.input.PalmRejection
import dev.hfps.reveal.navigation.RevealNavHost
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.theme.ThemeModeStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeModeStore: ThemeModeStore

    @Inject
    lateinit var handoffs: HandoffRepository

    @Inject
    lateinit var presets: PresetRepository

    @Inject
    lateinit var sync: SyncRepository

    @Inject
    lateinit var batches: HandoffBatches

    /** What a hand-off of server references could not do (docs/adr/0011 "Amendment W3/H"), until dismissed. */
    private val serverNotice = MutableStateFlow<ServerNotice?>(null)

    private data class ServerNotice(val issue: ServerIssue?, val notFound: Int)

    /** What the last hand-off from another app (ADR 0011) resolved to, until navigated to. */
    private val openRequest = MutableStateFlow<HandoffOutcome?>(null)

    /** "Import from a link" for a preset link shared to or opened with Reveal (docs/adr/0019). */
    private val presetLink: PresetLinkViewModel by viewModels()

    /** Ignores finger touches while a pen is down (palm rejection, docs/adr/0016). */
    private val palmRejection = PalmRejection()

    /** True while the editor shows a photo another app sent: leaving it returns to that app. */
    private var openedFromIntent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity restores its back stack; only a fresh launch handles its intent.
        if (savedInstanceState == null) handleIntent(intent)
        watchServer()
        openedFromIntent = savedInstanceState?.getBoolean(KEY_FROM_INTENT) ?: openedFromIntent
        setContent {
            val mode by themeModeStore.mode.collectAsStateWithLifecycle()
            val navController = rememberNavController()
            val entry by navController.currentBackStackEntryAsState()
            // The editor and the viewer are always dark (docs/plan/ux.md): their bars follow them, not the theme.
            val destination = entry?.destination
            val inEditor = destination?.hasRoute<EditorRoute>() == true ||
                destination?.hasRoute<ViewerRoute>() == true
            val dark = mode.isDark()
            val darkBars = dark || inEditor
            DisposableEffect(darkBars) {
                val style = if (darkBars) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val request by openRequest.collectAsStateWithLifecycle()
            LaunchedEffect(request) {
                val outcome = request ?: return@LaunchedEffect
                openRequest.value = null
                show(navController, outcome)
            }
            RevealTheme(darkTheme = dark) {
                RevealNavHost(
                    navController = navController,
                    onEditorBack = {
                        if (openedFromIntent) {
                            finish()
                        } else {
                            navController.popBackStack()
                        }
                    },
                )
                PresetLinkHost(presetLink)
                val notice by serverNotice.collectAsStateWithLifecycle()
                notice?.let { shown ->
                    HandoffServerSheet(
                        issue = shown.issue,
                        notFound = shown.notFound,
                        onPair = {
                            // The batch stays kept: pairing with that server opens it (watchServer).
                            serverNotice.value = null
                            navController.navigate(SyncSettingsRoute)
                        },
                        onDismiss = {
                            serverNotice.value = null
                            if (shown.issue is ServerIssue.NotPaired) lifecycleScope.launch { handoffs.dropPending() }
                        },
                    )
                }
            }
        }
    }

    /**
     * After pairing, the batch that waited for it opens; after a sync, photos opened while the server was not answering
     * fill in (docs/adr/0011 "Amendment W3/H").
     */
    private fun watchServer() {
        // A cascade of intents ended with nothing to open in the editor: the batch shows in the library.
        lifecycleScope.launch {
            batches.openLibrary.collect { openRequest.value = HandoffOutcome.OpenLibrary(emptyList()) }
        }
        lifecycleScope.launch {
            sync.account.filterNotNull().collect { account ->
                handoffs.resumePending(account.serverId)?.let { outcome ->
                    openedFromIntent = outcome is HandoffOutcome.OpenEditor && outcome.closeOnBack
                    openRequest.value = outcome
                }
            }
        }
        lifecycleScope.launch {
            sync.status.map { it.lastSyncAt }.distinctUntilChanged().collect { handoffs.retryUnreachable() }
        }
    }

    /** Navigates to what a hand-off resolved to (ADR 0011). */
    private fun show(navController: NavController, outcome: HandoffOutcome) {
        when (outcome) {
            is HandoffOutcome.OpenEditor -> notify(outcome.issue, outcome.notFound)
            is HandoffOutcome.OpenLibrary -> notify(outcome.issue, outcome.notFound)
            is HandoffOutcome.ServerRefs -> notify(outcome.issue, outcome.notFound)
            else -> Unit
        }
        when (outcome) {
            is HandoffOutcome.ServerRefs -> Unit

            is HandoffOutcome.OpenEditor -> {
                if (outcome.libraryBehind) {
                    navController.navigate(LibraryRoute(fromApps = true)) {
                        popUpTo<LibraryRoute> { inclusive = true }
                    }
                }
                // Several photos handed over: the tablet filmstrip shows them ("Opened from other apps").
                val strip = if (outcome.libraryBehind) {
                    PhotoListCodec.encode(PhotoQuery.FROM_APPS)
                } else {
                    null
                }
                navController.navigateToEditor(outcome.versionId, strip)
            }

            is HandoffOutcome.OpenUri -> navController.navigateToEditor(outcome.uri.toString())

            is HandoffOutcome.OpenLibrary -> navController.navigate(LibraryRoute(fromApps = true)) {
                popUpTo<LibraryRoute> { inclusive = true }
            }

            HandoffOutcome.Nothing -> Toast.makeText(this, R.string.handoff_unreadable, Toast.LENGTH_LONG).show()
        }
    }

    private fun notify(issue: ServerIssue?, notFound: Int) {
        if (issue != null || notFound > 0) serverNotice.value = ServerNotice(issue, notFound)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val events = palmRejection.filter(event)
        var handled = false
        for (e in events) {
            handled = super.dispatchTouchEvent(e) || handled
            if (e !== event) e.recycle()
        }
        return handled || events.isEmpty()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FROM_INTENT, openedFromIntent)
    }

    @Suppress("ReturnCount") // One early return per kind of intent: preset file, preset link, photo hand-off.
    private fun handleIntent(intent: Intent?) {
        // A preset or pack file (docs/adr/0019): imported into the library, never a photo hand-off.
        PresetIntents.presetFile(intent, contentResolver)?.let { uri ->
            lifecycleScope.launch {
                val event = PresetFilesIo(contentResolver, presets).import(uri)
                PresetMessages.text(this@MainActivity, event)?.let {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                }
            }
            return
        }
        // A link to a preset or pack (shared text or an https link): confirmed by the user before any download.
        PresetIntents.presetLink(intent)?.let { link ->
            presetLink.offer(link)
            return
        }
        val parsed = HandoffIntents.parse(intent) ?: return
        // Several intents of one sender and title are one batch (docs/adr/0011 "Batches over 500").
        val key = HandoffBatches.key(referrer?.host, parsed.title)
        // An activity above others (a cascade of startActivities) that has nothing to open ingests and leaves at once.
        val silentMember = key != null && !parsed.opensSomething && !isTaskRoot
        lifecycleScope.launch {
            val join = key?.let { batches.join(it) }
            val request = parsed.copy(batchAt = join?.at)
            if (key != null && parsed.opensSomething) batches.claim(key)
            val outcome = handoffs.receive(request)
            if (key != null) batches.touch(key)
            if (silentMember) {
                batches.silentFinished(key!!)
                finish()
                @Suppress("DEPRECATION")
                overridePendingTransition(0, 0)
                return@launch
            }
            // A single photo opened in the editor: leaving it returns to the calling app.
            openedFromIntent = outcome is HandoffOutcome.OpenUri ||
                (outcome is HandoffOutcome.OpenEditor && outcome.closeOnBack)
            openRequest.value = outcome
        }
    }

    private companion object {
        const val KEY_FROM_INTENT = "openedFromIntent"
    }
}
