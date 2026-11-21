package dev.hfps.reveal.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.settings.SettingsContent
import dev.hfps.reveal.feature.settings.SettingsHubState
import dev.hfps.reveal.feature.settings.appearance.AppearanceActions
import dev.hfps.reveal.feature.settings.appearance.AppearanceContent
import dev.hfps.reveal.feature.settings.appearance.AppearanceSection
import dev.hfps.reveal.feature.settings.appearance.AppearanceUiState
import dev.hfps.reveal.feature.settings.appearance.LanguageChoice
import dev.hfps.reveal.feature.settings.editing.EditingContent
import dev.hfps.reveal.feature.settings.folders.BackupFolder
import dev.hfps.reveal.feature.settings.folders.FoldersAccessContent
import dev.hfps.reveal.feature.settings.folders.FoldersActions
import dev.hfps.reveal.feature.settings.folders.FoldersUiState
import dev.hfps.reveal.feature.settings.sync.BackupActions
import dev.hfps.reveal.feature.settings.sync.BackupSyncContent
import dev.hfps.reveal.feature.settings.sync.BackupUiState
import dev.hfps.reveal.feature.settings.sync.PhotoServerContent
import dev.hfps.reveal.feature.settings.sync.SyncActions
import dev.hfps.reveal.feature.settings.sync.SyncUiState
import dev.hfps.reveal.sync.SyncStatus
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.account.SyncOptions
import dev.hfps.reveal.ui.about.AboutContent
import dev.hfps.reveal.ui.about.LicenceEntry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Fictional server, folders and people for the settings goldens. */
internal object SettingsSamples {
    const val NOW = 1_762_851_600_000L
    val account = SyncAccount("https://photos.example.org", "858e1b9d", "Home server", "d1", NOW - 86_400_000)
    val status = SyncStatus(
        lastSyncAt = NOW - 120_000,
        uploadsPending = 4,
        uploadBytesPending = 96_000_000,
        uploadedBytes = 1_850_000_000,
        serverPhotos = 2481,
    )
    val hub = SettingsHubState(server = "Home server", status = status)
    val server = SyncUiState(account = account, status = status)
    val backup = BackupUiState(paired = true, options = SyncOptions(), status = status, handoffBackup = false)
    val folders = FoldersUiState(
        paired = true,
        originals = true,
        folders = listOf(
            BackupFolder(1, "Camera", backup = true, coveredByHoard = true),
            BackupFolder(2, "Beach day", backup = false),
            BackupFolder(3, "Studio", backup = true),
        ),
        gallery = GalleryAccessState.PARTIAL,
    )
    val licences = listOf(
        LicenceEntry("LibRaw 0.22.3", "LGPL-2.1 (chosen from LGPL-2.1 / CDDL-1.0)"),
        LicenceEntry("lensfun lens database (converted, commit 52cf1a7)", "CC BY-SA 3.0"),
        LicenceEntry("MediaPipe BlazeFace face detection model", "Apache-2.0"),
        LicenceEntry("Inter 4.1, Rasmus Andersson", "SIL Open Font License 1.1"),
        LicenceEntry("Phosphor Icons 2.1.1", "MIT"),
    )
}

/**
 * Settings goldens under `screenshots/settings/`: the hub and each of its screens in pt-BR (the owner's language),
 * `phone_<screen>_<light|dark>.png`. Photo server's dialogs and states in English: `SyncScreenshotTest` (sync/).
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "pt-rBR-w390dp-h1000dp-xhdpi")
abstract class SettingsScreenshotBase(private val dark: Boolean) {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun hub() = shot("settings") { SettingsContent(SettingsSamples.hub, {}, {}, now = SettingsSamples.NOW) }

    @Test fun server() = shot("server") {
        PhotoServerContent(SettingsSamples.server, SyncActions(), now = SettingsSamples.NOW)
    }

    @Test fun serverNotPaired() = shot("server_unpaired") {
        PhotoServerContent(SyncUiState(), SyncActions(), now = SettingsSamples.NOW)
    }

    @Test fun backup() = shot("backup") { BackupSyncContent(SettingsSamples.backup, BackupActions()) }

    @Test fun backupNotPaired() = shot("backup_unpaired") { BackupSyncContent(BackupUiState(), BackupActions()) }

    @Test fun folders() = shot("folders") { FoldersAccessContent(SettingsSamples.folders, FoldersActions()) }

    @Test fun appearance() = shot("appearance") {
        AppearanceContent(AppearanceUiState(ThemeMode.Dark, LanguageChoice.SYSTEM), AppearanceActions())
    }

    /** The language menu open (a popup: the whole screen is captured). */
    @OptIn(ExperimentalRoborazziApi::class)
    @Test
    fun appearanceDropdown() {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                AppearanceContent(
                    AppearanceUiState(ThemeMode.System, LanguageChoice.PORTUGUESE_BRAZIL),
                    AppearanceActions(),
                    openSection = AppearanceSection.LANGUAGE,
                )
            }
        }
        composeRule.waitForIdle()
        captureScreenRoboImage("src/test/screenshots/settings/phone_appearance_dropdown_${mode()}.png")
    }

    @Test fun editing() = shot("editing") {
        EditingContent(readEdits = true, author = "Ana Example", copyright = "© 2026 Ana Example", onBack = {})
    }

    @Test fun about() = shot("about") {
        AboutContent(version = "0.10.0 (1000)", licences = SettingsSamples.licences, onBack = {})
    }

    protected fun shot(name: String, content: @Composable () -> Unit) = shoot("phone_${name}_${mode()}", content)

    protected fun shoot(file: String, content: @Composable () -> Unit) {
        composeRule.setContent { RevealTheme(darkTheme = dark) { content() } }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/settings/$file.png")
    }

    private fun mode() = if (dark) "dark" else "light"
}

class SettingsLightScreenshotTest : SettingsScreenshotBase(dark = false) {
    @Test fun foldersNotPaired() = shoot("phone_folders_unpaired_light") {
        FoldersAccessContent(FoldersUiState(gallery = GalleryAccessState.FULL), FoldersActions())
    }

    /** The hub in English (the other locale). */
    @Config(qualifiers = "en-w390dp-h1000dp-xhdpi")
    @Test
    fun hubEnglish() = shoot("phone_settings_en_light") {
        SettingsContent(SettingsSamples.hub, {}, {}, now = SettingsSamples.NOW)
    }
}

class SettingsDarkScreenshotTest : SettingsScreenshotBase(dark = true) {
    @Test fun hubNotPaired() = shoot("phone_settings_unpaired_dark") {
        SettingsContent(SettingsHubState(), {}, {}, now = SettingsSamples.NOW)
    }

    @Test fun foldersOriginalsOff() = shoot("phone_folders_originals_off_dark") {
        FoldersAccessContent(SettingsSamples.folders.copy(originals = false), FoldersActions())
    }
}

/** The hub on a tablet (Tab S9): one column, at most 720 dp wide. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "pt-rBR-w1280dp-h800dp-hdpi")
class TabletSettingsScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test fun hub() {
        composeRule.setContent {
            RevealTheme(darkTheme = true) { SettingsContent(SettingsSamples.hub, {}, {}, now = SettingsSamples.NOW) }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/settings/tablet_settings_dark.png")
    }
}
