package dev.hfps.reveal

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.isDark
import dev.hfps.reveal.feature.editor.EditorRoute
import dev.hfps.reveal.feature.editor.navigateToEditor
import dev.hfps.reveal.feature.library.ViewerRoute
import dev.hfps.reveal.navigation.OpenIntents
import dev.hfps.reveal.navigation.RevealNavHost
import dev.hfps.reveal.theme.ThemeModeStore
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var themeModeStore: ThemeModeStore

    /** A photo another app asked us to open (`ACTION_VIEW` / `ACTION_EDIT` / `ACTION_SEND`), until navigated to. */
    private val openRequest = MutableStateFlow<Uri?>(null)

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
                request?.let {
                    navController.navigateToEditor(it.toString())
                    openRequest.value = null
                }
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_FROM_INTENT, openedFromIntent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = OpenIntents.photoUri(intent) ?: return
        openedFromIntent = true
        openRequest.value = uri
    }

    private companion object {
        const val KEY_FROM_INTENT = "openedFromIntent"
    }
}
