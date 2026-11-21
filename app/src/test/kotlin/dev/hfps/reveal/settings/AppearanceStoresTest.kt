package dev.hfps.reveal.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.settings.appearance.AppLanguage
import dev.hfps.reveal.feature.settings.appearance.LanguageChoice
import dev.hfps.reveal.feature.settings.appearance.ThemeModeStore
import dev.hfps.reveal.ui.about.AboutVersion
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Settings -> Appearance and language: the theme and the language survive a restart (a new store). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class AppearanceStoresTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun themeIsSystemUntilChosenAndPersists() {
        assertThat(ThemeModeStore(context).mode.value).isEqualTo(ThemeMode.System)
        ThemeModeStore(context).set(ThemeMode.Light)
        assertThat(ThemeModeStore(context).mode.value).isEqualTo(ThemeMode.Light)
        ThemeModeStore(context).set(ThemeMode.Dark)
        assertThat(ThemeModeStore(context).mode.value).isEqualTo(ThemeMode.Dark)
    }

    @Test
    fun unknownStoredThemeIsSystem() {
        assertThat(ThemeModeStore.parse(null)).isEqualTo(ThemeMode.System)
        assertThat(ThemeModeStore.parse("Sepia")).isEqualTo(ThemeMode.System)
        assertThat(ThemeModeStore.parse("Dark")).isEqualTo(ThemeMode.Dark)
    }

    /** Android 13+: the per-app language of the system (`LocaleManager`); Reveal does not recreate itself. */
    @Test
    fun languageUsesThePerAppLocaleOnAndroid13() {
        val language = AppLanguage(context)
        assertThat(language.set(LanguageChoice.PORTUGUESE_BRAZIL)).isFalse()
        assertThat(AppLanguage(context).choice.value).isEqualTo(LanguageChoice.PORTUGUESE_BRAZIL)
        language.set(LanguageChoice.SYSTEM)
        assertThat(AppLanguage(context).choice.value).isEqualTo(LanguageChoice.SYSTEM)
    }

    /** Android 10-12: Reveal keeps the choice and wraps the activity's context; the caller recreates it. */
    @Config(sdk = [32])
    @Test
    fun languageIsKeptAndAppliedByWrappingOnAndroid12() {
        val language = AppLanguage(context)
        assertThat(language.set(LanguageChoice.ENGLISH)).isTrue()
        assertThat(AppLanguage(context).choice.value).isEqualTo(LanguageChoice.ENGLISH)
        assertThat(AppLanguage.wrap(context).resources.configuration.locales[0].language).isEqualTo("en")
        language.set(LanguageChoice.PORTUGUESE_BRAZIL)
        assertThat(AppLanguage.wrap(context).resources.configuration.locales[0].toLanguageTag()).isEqualTo("pt-BR")
        language.set(LanguageChoice.SYSTEM)
        assertThat(AppLanguage.wrap(context)).isSameInstanceAs(context)
    }

    @Test
    fun aboutVersionNamesTheBuildTypeOutsideRelease() {
        assertThat(AboutVersion.of("0.10.0", 1000, "release")).isEqualTo("0.10.0 (1000)")
        assertThat(AboutVersion.of("0.10.0", 1000, "debug")).isEqualTo("0.10.0 (1000, debug)")
    }
}
