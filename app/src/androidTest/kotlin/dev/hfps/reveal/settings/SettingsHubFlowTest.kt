package dev.hfps.reveal.settings

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.os.LocaleList
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.feature.settings.SettingsEntry
import dev.hfps.reveal.feature.settings.SettingsTags
import dev.hfps.reveal.feature.settings.appearance.AppearanceTags
import dev.hfps.reveal.feature.settings.appearance.LanguageChoice
import dev.hfps.reveal.feature.settings.editing.EditingTags
import dev.hfps.reveal.feature.settings.folders.FoldersTags
import dev.hfps.reveal.feature.settings.storage.StorageTags
import dev.hfps.reveal.feature.settings.sync.BackupTags
import dev.hfps.reveal.feature.settings.sync.SyncTags
import dev.hfps.reveal.settings.SettingsSteps.backTo
import dev.hfps.reveal.settings.SettingsSteps.openEntry
import dev.hfps.reveal.settings.SettingsSteps.openSettings
import dev.hfps.reveal.ui.about.AboutTags
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Settings as a hub of short entries: each entry opens its screen and Back returns to the hub; the theme applies at
 * once and survives the activity's recreation; the language switches to the other translation and back; the
 * library's sync badge lands on Photo server. Locale-independent: tags, and strings read from the resources of the
 * language being checked.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SettingsHubFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val access get() = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)

    @Before
    fun setUp() {
        runBlocking { access.sync().unpair() }
        access.themeModes().set(ThemeMode.System)
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            access.themeModes().set(ThemeMode.System)
            access.appLanguage().set(LanguageChoice.SYSTEM)
        }
    }

    @Test
    fun everyEntryOpensItsScreenAndBackReturnsToTheHub() {
        val screens = mapOf(
            SettingsEntry.SERVER to SyncTags.SCREEN,
            SettingsEntry.BACKUP to BackupTags.SCREEN,
            SettingsEntry.FOLDERS to FoldersTags.SCREEN,
            SettingsEntry.STORAGE to StorageTags.SCREEN,
            SettingsEntry.APPEARANCE to AppearanceTags.SCREEN,
            SettingsEntry.EDITING to EditingTags.SCREEN,
            SettingsEntry.ABOUT to AboutTags.SCREEN,
        )
        assertThat(screens.keys).containsExactlyElementsIn(SettingsEntry.entries)
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.openSettings()
            for ((entry, screen) in screens) {
                compose.openEntry(entry, TIMEOUT_MS)
                compose.waitUntilAtLeastOneExists(hasTestTag(screen), TIMEOUT_MS)
                compose.backTo(SettingsTags.SCREEN)
            }
            // Not paired: Backup and sync leads to Photo server; Folders shows the gallery access row.
            compose.openEntry(SettingsEntry.BACKUP, TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(BackupTags.PAIR), TIMEOUT_MS)
            compose.onNodeWithTag(BackupTags.PAIR).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.LINK), TIMEOUT_MS)
            compose.backTo(SettingsTags.SCREEN)
            compose.openEntry(SettingsEntry.FOLDERS, TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(FoldersTags.GALLERY), TIMEOUT_MS)
            compose.backTo()
        }
    }

    @Test
    fun theLibrarySyncBadgeLandsOnPhotoServer() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntilAtLeastOneExists(hasTestTag(LibraryTags.SYNC_STATUS), TIMEOUT_MS)
            compose.onNodeWithTag(LibraryTags.SYNC_STATUS).performClick()
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.SCREEN), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(SyncTags.SCAN), TIMEOUT_MS)
            // Back returns to the library, not to the hub (it was never opened).
            compose.backTo()
        }
    }

    @Test
    fun themeAppliesAtOnceAndSurvivesRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openSettings(SettingsEntry.APPEARANCE, TIMEOUT_MS)
            choose(AppearanceTags.THEME, AppearanceTags.theme(ThemeMode.Dark))
            compose.waitUntil(TIMEOUT_MS) { backgroundIsDark() == true }
            assertThat(access.themeModes().mode.value).isEqualTo(ThemeMode.Dark)

            scenario.recreate()
            compose.waitUntilAtLeastOneExists(hasTestTag(AppearanceTags.SCREEN), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(
                hasTestTag(AppearanceTags.THEME) and hasState(text(R.string.appearance_theme_dark)),
                TIMEOUT_MS,
            )
            assertThat(backgroundIsDark()).isTrue()

            choose(AppearanceTags.THEME, AppearanceTags.theme(ThemeMode.Light))
            compose.waitUntil(TIMEOUT_MS) { backgroundIsDark() == false }
            assertThat(access.themeModes().mode.value).isEqualTo(ThemeMode.Light)
        }
    }

    @Test
    fun languageSwitchesToTheOtherTranslationAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.openSettings(SettingsEntry.APPEARANCE, TIMEOUT_MS)
            var shown = ""
            scenario.onActivity { shown = it.resources.configuration.locales[0].language }
            val other = if (shown == "pt") LanguageChoice.ENGLISH else LanguageChoice.PORTUGUESE_BRAZIL
            val before = text(R.string.appearance_title)
            val after = localized(checkNotNull(other.tag)).getString(R.string.appearance_title)
            assertThat(after).isNotEqualTo(before)

            choose(AppearanceTags.LANGUAGE, AppearanceTags.language(other))
            // The activity is recreated in the new language, on the same screen.
            compose.waitUntilAtLeastOneExists(hasText(after), TIMEOUT_MS)
            compose.waitUntilAtLeastOneExists(hasTestTag(AppearanceTags.SCREEN), TIMEOUT_MS)
            assertThat(access.appLanguage().choice.value).isEqualTo(other)

            choose(AppearanceTags.LANGUAGE, AppearanceTags.language(LanguageChoice.SYSTEM))
            compose.waitUntilAtLeastOneExists(hasText(before), TIMEOUT_MS)
            assertThat(access.appLanguage().choice.value).isEqualTo(LanguageChoice.SYSTEM)
        }
    }

    /** Opens the dropdown tagged [field] and picks the option tagged [option]. */
    private fun choose(field: String, option: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(field), TIMEOUT_MS)
        compose.onNodeWithTag(field).performClick()
        compose.waitUntilAtLeastOneExists(hasTestTag(option), TIMEOUT_MS)
        compose.onNodeWithTag(option).performClick()
    }

    private fun hasState(value: String) = SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    /** A string in the language the app shows now (the target context follows the per-app language). */
    private fun text(id: Int): String {
        var value = ""
        instrumentation.runOnMainSync { value = context.getString(id) }
        return value
    }

    private fun localized(tag: String): Context {
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList.forLanguageTags(tag))
        return context.createConfigurationContext(config)
    }

    /** The screen's margin left of the card (the theme's background), dark or not; null before a frame. */
    private fun backgroundIsDark(): Boolean? {
        val shot = instrumentation.uiAutomation.takeScreenshot() ?: return null
        val pixel = shot.getPixel(2, shot.height / 2)
        shot.recycle()
        val luminance = (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
        return luminance < DARK_LUMINANCE
    }

    private companion object {
        const val TIMEOUT_MS = 20_000L
        const val DARK_LUMINANCE = 100
    }
}
