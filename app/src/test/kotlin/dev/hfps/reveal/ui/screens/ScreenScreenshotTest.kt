package dev.hfps.reveal.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.editor.EditorActions
import dev.hfps.reveal.feature.editor.EditorContent
import dev.hfps.reveal.feature.editor.EditorPhase
import dev.hfps.reveal.feature.editor.EditorUiState
import dev.hfps.reveal.feature.editor.open.OpenError
import dev.hfps.reveal.ui.about.AboutContent
import dev.hfps.reveal.ui.about.LicenceEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens of the M0 screens under `app/src/test/screenshots/screens/`: About, and the editor's loading and
 * error states (the developed canvas needs a GPU and is covered by the instrumented tests).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class ScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun about() = shoot("about_light") {
        RevealTheme(darkTheme = false) {
            AboutContent(
                version = "0.1.0",
                licences = listOf(
                    LicenceEntry("LibRaw 0.22.3", "LGPL-2.1 (chosen from LGPL-2.1 / CDDL-1.0)"),
                    LicenceEntry("Inter 4.1, Rasmus Andersson", "SIL Open Font License 1.1"),
                    LicenceEntry("Phosphor Icons 2.1.1", "MIT"),
                ),
                onBack = {},
            )
        }
    }

    @Test fun editorLoading() = shoot("editor_loading") {
        EditorTheme {
            EditorContent(
                state = EditorUiState(fileName = "DSC00002.ARW", phase = EditorPhase.Loading(0.6f)),
                actions = EditorActions(onBack = {}),
            )
        }
    }

    @Test fun editorError() = shoot("editor_error_corrupt") {
        EditorTheme {
            EditorContent(
                state = EditorUiState(fileName = "broken.ARW", phase = EditorPhase.Failed(OpenError.CORRUPT)),
                actions = EditorActions(onBack = {}),
            )
        }
    }

    @Test fun editorErrorCamera() = shoot("editor_error_camera") {
        EditorTheme {
            EditorContent(
                state = EditorUiState(
                    fileName = "a-very-long-file-name-from-a-camera-that-names-files-verbosely-0001.DNG",
                    phase = EditorPhase.Failed(OpenError.UNSUPPORTED_CAMERA),
                ),
                actions = EditorActions(onBack = {}),
            )
        }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/screens/phone_$name.png")
    }
}
