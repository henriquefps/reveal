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
import dev.hfps.reveal.data.handoff.HandoffIntents
import dev.hfps.reveal.data.handoff.HandoffOutcome
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.model.LibraryScope
import dev.hfps.reveal.data.model.PhotoListCodec
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.isDark
import dev.hfps.reveal.feature.editor.EditorRoute
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.library.LibraryRoute
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.input.PalmRejection
import dev.hfps.reveal.navigation.RevealNavHost
import dev.hfps.reveal.theme.ThemeModeStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeModeStore: ThemeModeStore

    @Inject
    lateinit var handoffs: HandoffRepository

    /** What the last hand-off from another app (ADR 0011) resolved to, until navigated to. */
    private val openRequest = MutableStateFlow<HandoffOutcome?>(null)

    /** Ignores finger touches while a pen is down (palm rejection, docs/adr/0016). */
    private val palmRejection = PalmRejection()

    /** True while the editor shows a photo another app sent: leaving it returns to that app. */
    private var openedFromIntent = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A recreated activity restores its back stack; only a fresh launch handles its intent.
        if (savedInstanceState == null) handleIntent(intent)
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
                    themeMode = mode,
                    onThemeModeChange = themeModeStore::set,
                    onEditorBack = {
                        if (openedFromIntent) {
                            finish()
                        } else {
                            navController.popBackStack()
                        }
                    },
                )
            }
        }
    }

    /** Navigates to what a hand-off resolved to (ADR 0011). */
    private fun show(navController: NavController, outcome: HandoffOutcome) {
        when (outcome) {
            is HandoffOutcome.OpenEditor -> {
                if (outcome.libraryBehind) {
                    navController.navigate(LibraryRoute(fromApps = true)) {
                        popUpTo<LibraryRoute> { inclusive = true }
                    }
                }
                // Several photos handed over: the tablet filmstrip shows them ("Opened from other apps").
                val strip = if (outcome.libraryBehind) {
                    PhotoListCodec.encode(PhotoQuery(scope = LibraryScope.FromApps))
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

    private fun handleIntent(intent: Intent?) {
        val request = HandoffIntents.parse(intent) ?: return
        lifecycleScope.launch {
            val outcome = handoffs.receive(request)
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
