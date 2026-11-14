package dev.hfps.reveal.ui.export

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.theme.EditorTheme
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.export.encode.EncoderSupport
import dev.hfps.reveal.feature.export.engine.ExportFailure
import dev.hfps.reveal.feature.export.model.BuiltInExportPreset
import dev.hfps.reveal.feature.export.model.ExportColour
import dev.hfps.reveal.feature.export.model.ExportDestination
import dev.hfps.reveal.feature.export.model.ExportOptions
import dev.hfps.reveal.feature.export.model.ExportPreset
import dev.hfps.reveal.feature.export.model.ExportPresets
import dev.hfps.reveal.feature.export.ui.ExportRequest
import dev.hfps.reveal.feature.export.ui.ExportRun
import dev.hfps.reveal.feature.export.ui.ExportSheetActions
import dev.hfps.reveal.feature.export.ui.ExportSheetContent
import dev.hfps.reveal.feature.export.ui.ExportUiState
import dev.hfps.reveal.feature.settings.SettingsContent
import dev.hfps.reveal.pipeline.export.ExportSize
import dev.hfps.reveal.pipeline.export.PixelSize
import dev.hfps.reveal.pipeline.recipe.Recipe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Goldens of the export sheet (editor: dark; library: light) and Settings, under `screenshots/export/`. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h1400dp-xhdpi")
class ExportScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val support = EncoderSupport(heif = true, heicCodec = false, hevcMain10 = false)
    private val single = ExportUiState(
        request = ExportRequest.Current(null, "content://x", "DSC00002.ARW", Recipe()),
        options = ExportOptions(colour = ExportColour.DISPLAY_P3),
        support = support,
        authorship = Authorship("Ana Example", "© 2026 Ana Example"),
        fullSize = PixelSize(6024, 4024),
        outputSize = PixelSize(6024, 4024),
        estimateBytes = 9_100_000,
    )

    @Test fun editorSheet() = shoot("editor_sheet") { EditorTheme { Sheet(single) } }

    @Test fun libraryBatchInstagram() = shoot("library_batch_light") {
        RevealTheme(darkTheme = false) {
            Sheet(
                single.copy(
                    request = ExportRequest.Versions(listOf(1, 2, 3, 4, 5)),
                    options = ExportOptions(
                        size = ExportSize.INSTAGRAM_PORTRAIT,
                        destination = ExportDestination.Share,
                    ),
                    outputSize = PixelSize(1080, 721),
                    estimateBytes = 410_000,
                    authorship = Authorship(),
                ),
            )
        }
    }

    @Test fun progress() = shoot("editor_progress") {
        EditorTheme {
            Sheet(
                single.copy(request = ExportRequest.Versions(listOf(1, 2, 3, 4, 5)), run = ExportRun("j", 5, 2, 0.47f)),
            )
        }
    }

    @Test fun result() = shoot("editor_result") {
        EditorTheme {
            Sheet(
                single.copy(
                    run = ExportRun(
                        "j",
                        5,
                        5,
                        1f,
                        finished = true,
                        failures = listOf("DSC00007.ARW" to ExportFailure.DESTINATION_UNAVAILABLE),
                        exported = 4,
                        results = (1..4).map { Uri.parse("content://media/external/images/media/$it") },
                    ),
                ),
            )
        }
    }

    /** A user preset picked and then changed: "Update preset", "Rename", "Delete" (docs/adr/0025). */
    @Test fun presetModified() = shoot("preset_modified_dark") { EditorTheme { Sheet(ExportSamples.presetModified) } }

    /** A built-in preset picked (Instagram 4:5): the chip is selected, "Save as preset" is not offered. */
    @Test fun presetBuiltIn() = shoot("preset_builtin_light") {
        RevealTheme(darkTheme = false) { Sheet(ExportSamples.presetBuiltIn) }
    }

    @Test fun settings() = shoot("settings_light") {
        RevealTheme(darkTheme = false) {
            SettingsContent("Ana Example", "© 2026 Ana Example", {}, {}, {})
        }
    }

    private fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/export/phone_$name.png")
    }
}

@Composable
private fun Sheet(state: ExportUiState) {
    Box(Modifier.fillMaxSize().background(RevealTheme.colors.card)) {
        ExportSheetContent(state, ExportSheetActions())
    }
}

/** Export sheet states with presets (docs/adr/0025), shared by the phone, landscape and tablet goldens. */
private object ExportSamples {
    private val support = EncoderSupport(heif = true, heicCodec = false, hevcMain10 = false)
    private val prints = ExportPreset.User(
        "u1",
        "Prints A3",
        ExportOptions(quality = 100, size = ExportSize.LongEdge(4960)),
    )
    private val presets = ExportPresets.builtIns + prints + ExportPreset.User("u2", "Family album", ExportOptions())
    private val base = ExportUiState(
        request = ExportRequest.Versions(listOf(1, 2, 3)),
        support = support,
        authorship = Authorship("Ana Example", "© 2026 Ana Example"),
        fullSize = PixelSize(6024, 4024),
        estimateBytes = 9_100_000,
        presets = presets,
    )

    val presetModified = base.copy(
        options = prints.options.copy(quality = 95),
        outputSize = PixelSize(4960, 3313),
        activePreset = prints.key,
    )

    val presetBuiltIn = base.copy(
        options = BuiltInExportPreset.INSTAGRAM.options,
        outputSize = PixelSize(1080, 721),
        estimateBytes = 410_000,
        activePreset = "builtin:instagram",
    )
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w844dp-h390dp-xhdpi")
class ExportLandscapeScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun presets() {
        composeRule.setContent { EditorTheme { Sheet(ExportSamples.presetModified) } }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/export/landscape_presets.png")
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1495dp-h934dp-mdpi")
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalRoborazziApi::class)
class ExportTabletScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    /** The sheet is a centred dialog from 840 dp (RevealBottomSheet). */
    @Test fun presets() {
        composeRule.setContent {
            RevealTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize().background(RevealTheme.colors.background)) {
                    RevealBottomSheet(onDismissRequest = {}) {
                        ExportSheetContent(ExportSamples.presetBuiltIn, ExportSheetActions())
                    }
                }
            }
        }
        captureScreenRoboImage("src/test/screenshots/export/tablet_presets.png")
    }
}
