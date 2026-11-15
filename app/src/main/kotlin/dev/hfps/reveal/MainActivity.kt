package dev.hfps.reveal

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavController
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.handoff.HandoffApps
import dev.hfps.reveal.data.handoff.HandoffBatches
import dev.hfps.reveal.data.handoff.HandoffIngests
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffNotice
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.ServerIssue
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.preset.PresetFilesIo
import dev.hfps.reveal.data.preset.PresetIntents
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.storage.AppStart
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.isDark
import dev.hfps.reveal.feature.editor.EditorRoute
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.editor.presets.PresetLinkHost
import dev.hfps.reveal.feature.editor.presets.PresetLinkViewModel
import dev.hfps.reveal.feature.editor.presets.PresetMessages
import dev.hfps.reveal.feature.library.GalleryAccessSheet
import dev.hfps.reveal.feature.library.GalleryOffer
import dev.hfps.reveal.feature.library.HandoffServerSheet
import dev.hfps.reveal.feature.library.LibraryRoute
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.feature.settings.SyncSettingsRoute
import dev.hfps.reveal.handoff.HandoffGrantService
import dev.hfps.reveal.handoff.HandoffNotices
import dev.hfps.reveal.input.PalmRejection
import dev.hfps.reveal.navigation.RevealNavHost
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.theme.ThemeModeStore
import java.util.UUID
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

    @Inject
    lateinit var ingests: HandoffIngests

    @Inject
    lateinit var gallery: GalleryAccess

    /** The hand-off this activity receives (its key in [ingests], kept across recreation), or null. */
    private var ingestKey: String? = null

    /**
     * The hand-offs whose intents this activity received (it holds their grants until it is destroyed): each told
     * [HandoffIngests.Ingest.holderGone] when it finishes.
     */
    private val held = ArrayList<HandoffIngests.Ingest>()

    /** The ingest whose outcome [openRequest] holds: marked shown once navigated to. */
    private var requestKey: String? = null

    /** A single photo from Hoard opened in the editor: the media offer may show over it (ADR 0011 "Amendment W4"). */
    private val editorMediaOffer = MutableStateFlow(false)

    /** What a hand-off of server references could not do (docs/adr/0011 "Amendment W3/H"), until dismissed. */
    private val serverNotice = MutableStateFlow<ServerNotice?>(null)

    /** The outcome whose notices were already reported (merged with its batch's): [show] skips them. */
    private var noticeShown: HandoffOutcome? = null

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
        // A recreated activity restores its back stack; only a fresh launch handles its intent. A hand-off still being
        // received when it was recreated goes on in the app's scope: this activity re-attaches to it.
        if (savedInstanceState == null) {
            handleIntent(intent)
        } else {
            savedInstanceState.getString(KEY_INGEST)?.let(::reattach)
            editorMediaOffer.value = savedInstanceState.getBoolean(KEY_MEDIA_OFFER)
        }
        watchServer()
        openedFromIntent = savedInstanceState?.getBoolean(KEY_FROM_INTENT) ?: openedFromIntent
        // Bulk background work (indexing, access checks, sync, the thumbnail prefetch) waits for the first frame
        // (ADR 0018 "W3/C": nothing reads files before it).
        window.decorView.doOnPreDraw { view -> view.post { AppStart.firstFrame() } }
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
                requestKey?.let { ingests.attach(it)?.shown = true }
                requestKey = null
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
                ServerNoticeHost(navController)
                EditorGalleryOffer()
            }
        }
    }

    /**
     * The photo permission offer over the editor, for a single photo Hoard handed over as a MediaStore copy (ADR 0011
     * "Amendment W4"): non-blocking, once (the same answer as the library's offer).
     */
    @Composable
    private fun EditorGalleryOffer() {
        val wanted by editorMediaOffer.collectAsStateWithLifecycle()
        val hoardMedia by gallery.hoardMedia.collectAsStateWithLifecycle()
        val state by gallery.state.collectAsStateWithLifecycle()
        val answered by gallery.mediaAsked.collectAsStateWithLifecycle()
        val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            gallery.setMediaAsked()
            gallery.refresh()
            editorMediaOffer.value = false
        }
        if (wanted && GalleryAccess.shouldOfferForMedia(hoardMedia, state, answered)) {
            GalleryAccessSheet(
                offer = GalleryOffer.HOARD,
                partial = state == GalleryAccessState.PARTIAL,
                onAllow = { permission.launch(GalleryAccess.permissions()) },
                onDecline = {
                    gallery.setMediaAsked()
                    editorMediaOffer.value = false
                },
            )
        }
    }

    /** The notice of a hand-off of server references (docs/adr/0011 "Amendment W3/H"), until dismissed. */
    @Composable
    private fun ServerNoticeHost(navController: NavController) {
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

    /**
     * After pairing, the batch that waited for it opens; after a sync, photos opened while the server was not answering
     * fill in (docs/adr/0011 "Amendment W3/H").
     */
    private fun watchServer() {
        // A member that finished after the batch's notice was shown: its own notice, once.
        lifecycleScope.launch { batches.lateNotices.collect { notify(it.issue, it.notFound) } }
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
        // A hand-off of an intent already reported its notices with the rest of its batch (noticeShown).
        if (outcome !== noticeShown) {
            val notice = noticeOf(outcome)
            notify(notice.issue, notice.notFound)
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

    private fun noticeOf(outcome: HandoffOutcome) = HandoffIngests.noticeOf(outcome)

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
        outState.putBoolean(KEY_MEDIA_OFFER, editorMediaOffer.value)
        ingestKey?.let { outState.putString(KEY_INGEST, it) }
    }

    override fun onDestroy() {
        // Finishing (not recreated): the grants of the intents it received go with this activity (the grant service
        // holds its own for work still running).
        if (isFinishing) held.forEach { it.holderGone() }
        super.onDestroy()
    }

    /** Recreated: follows the hand-off [key] again, or handles one lost with the process ([restoreLost]). */
    private fun reattach(key: String) {
        ingestKey = key
        val ingest = ingests.attach(key)
        if (ingest != null) {
            held += ingest
            follow(ingest, handedOver = false)
        } else {
            restoreLost()
        }
    }

    /**
     * Recreated after its process died, and its hand-off with it: a silent batch member receives its intent again
     * while the grant still works (it has nothing to show), else finishes; a shown one keeps its restored screen.
     */
    private fun restoreLost() {
        val parsed = HandoffIntents.parse(intent) ?: return
        if (!parsed.silentMember) return
        val granted = parsed.uris.isNotEmpty() && parsed.uris.all {
            checkCallingOrSelfUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        }
        if (granted) handleIntent(intent) else leaveQuietly()
    }

    /** A silent batch member leaves without a transition. */
    private fun leaveQuietly() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
        val batchKey = HandoffBatches.key(referrer?.host, parsed.title, marked = parsed.batchIndex != null)
        // Hoard's MediaStore copies need the photo permission to stay readable: offered once (ADR 0011 "Amendment
        // W4"). The debug build's `HandoffHostActivity` plays Hoard in the instrumented tests.
        val sender = HandoffApps.referrerPackage(referrer)
        val fromHoard = HandoffApps.isHoard(sender) || (BuildConfig.DEBUG && sender == packageName)
        val hoardMedia = fromHoard && parsed.uris.any { GalleryAccess.isMediaImages(it.toString()) }
        if (hoardMedia) gallery.noteHoardMedia()
        val key = UUID.randomUUID().toString()
        ingestKey = key
        // Received in the app's scope, which holds no reference to this activity (a rotation does not drop a batch
        // half-way); this activity follows it. The intents above the first of a split batch (`BATCH_INDEX` > 0) are
        // silent: never decided by the task (Hoard starts Reveal into its own task, so `isTaskRoot` is false for an
        // ordinary hand-off too).
        val ingest = ingests.receive(key, parsed, batchKey, hoardMedia)
        held += ingest
        // The grant service holds the intent's grant until the copies and previews that need it are done, so back
        // returns to the sender at once and a silent member leaves at once (ADR 0011 "Amendment W4", "Grant service").
        val handedOver = HandoffGrantService.start(this, ingest, parsed.uris)
        follow(ingest, handedOver)
        if (ingest.silent && handedOver) leaveQuietly()
    }

    /**
     * Follows [ingest] from this activity: shows its outcome once (a re-attached activity skips what was shown) and,
     * while it is still here when the rest settles, what could not be kept. Without the grant service
     * ([handedOver] false: its start was refused) a silent batch member stays until everything that needs the
     * intent's grant is done, then finishes.
     */
    private fun follow(ingest: HandoffIngests.Ingest, handedOver: Boolean) {
        lifecycleScope.launch {
            val result = ingest.await()
            val outcome = result.received.outcome
            if (!ingest.silent && !ingest.shown) present(ingest, outcome, result.follower)
            val settled = ingest.awaitSettled()
            if (ingest.claimSettledNotice()) {
                HandoffNotices.texts(this@MainActivity, settled).forEach {
                    Toast.makeText(this@MainActivity, it, Toast.LENGTH_LONG).show()
                }
            }
            if (ingest.silent) {
                ingests.done(ingest.key)
                if (!handedOver) leaveQuietly()
            } else if (ingest.shown) {
                ingests.done(ingest.key)
            }
        }
    }

    private suspend fun present(ingest: HandoffIngests.Ingest, outcome: HandoffOutcome, follower: Boolean) {
        // The notices of the whole batch (every member's, summed), shown once with this intent's outcome.
        val notice = batches.takeNotices(ingest.batchKey, noticeOf(outcome))
        // The batch's other members were ingested before this one: the library is behind the editor.
        val shown = if (follower && outcome is HandoffOutcome.OpenEditor && outcome.closeOnBack) {
            outcome.copy(closeOnBack = false, libraryBehind = true)
        } else {
            outcome
        }
        noticeShown = shown
        notify(notice.issue, notice.notFound)
        // A single photo opened in the editor: leaving it returns to the calling app.
        openedFromIntent = shown is HandoffOutcome.OpenUri ||
            (shown is HandoffOutcome.OpenEditor && shown.closeOnBack)
        if (ingest.hoardMedia && shown is HandoffOutcome.OpenEditor && shown.closeOnBack) editorMediaOffer.value = true
        requestKey = ingest.key
        openRequest.value = shown
    }

    private companion object {
        const val KEY_FROM_INTENT = "openedFromIntent"
        const val KEY_INGEST = "handoffIngest"
        const val KEY_MEDIA_OFFER = "editorMediaOffer"
    }
}
