package dev.hfps.reveal.debug

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.feature.editor.debug.EditorDebugScreen

/**
 * Debug-only host of the M0 pipeline canvas (`adb shell am start -n dev.hfps.reveal.debug/dev.hfps.reveal.debug.PipelineDebugActivity`).
 * Lives in the debug source set so it never ships in a release build.
 */
class PipelineDebugActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EditorTheme {
                EditorDebugScreen()
            }
        }
    }
}
