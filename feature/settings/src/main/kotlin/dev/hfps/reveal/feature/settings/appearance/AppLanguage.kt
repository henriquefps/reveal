package dev.hfps.reveal.feature.settings.appearance

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The app languages Reveal offers (`res/xml/locales_config.xml`): the system's, or one of its translations. */
enum class LanguageChoice(val tag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    PORTUGUESE_BRAZIL("pt-BR"),
    ;

    companion object {
        /**
         * The choice for the app's language tags (BCP 47, comma separated as `LocaleList.toLanguageTags` writes them;
         * empty = the system's). Only the first counts; a Portuguese or English variant maps to Reveal's translation,
         * anything else (set elsewhere, e.g. in the system's App languages) to [SYSTEM].
         */
        fun of(tags: String?): LanguageChoice {
            val first = tags.orEmpty().split(',').first().trim()
            if (first.isEmpty()) return SYSTEM
            return when (Locale.forLanguageTag(first).language) {
                "pt" -> PORTUGUESE_BRAZIL
                "en" -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}

/**
 * The app's language (Settings -> Appearance and language). Android 13+ keeps it as the per-app language
 * ([LocaleManager], also in the system's App languages, which recreates the activities and covers the application
 * context). On Android 10-12 Reveal keeps it itself: [wrap]s each activity's context, [applyToApp] sets it on the
 * application's resources (at start, after every system configuration change and on every choice, so strings resolved
 * through the application context, such as mask and preset names and notifications, follow it too), and the caller
 * recreates the activity.
 */
@Singleton
class AppLanguage @Inject constructor(@ApplicationContext private val context: Context) {
    private val state = MutableStateFlow(read())
    val choice: StateFlow<LanguageChoice> = state.asStateFlow()

    /** Reads the language again (it can change in the system's App languages while Reveal runs). */
    fun refresh() {
        state.value = read()
    }

    /** Sets the language. True when the caller must recreate the activity itself (Android 12 and older). */
    fun set(choice: LanguageChoice): Boolean {
        state.value = choice
        val tags = choice.tag.orEmpty()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tags)
            false
        } else {
            prefs(context).edit(commit = true) { putString(KEY_LANGUAGE, tags) }
            applyToApp(context.applicationContext)
            true
        }
    }

    private fun read(): LanguageChoice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        LanguageChoice.of(context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags())
    } else {
        LanguageChoice.of(prefs(context).getString(KEY_LANGUAGE, null))
    }

    companion object {
        private const val FILE = "appearance"
        private const val KEY_LANGUAGE = "language"

        private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

        /**
         * Android 12 and older: [base] with the chosen language (an activity's `attachBaseContext`). Android 13+ and
         * "System language" return [base] unchanged. Every APK carries both translations (the app's bundle does not
         * split by language), so no language download is involved.
         */
        @SuppressLint("AppBundleLocaleChanges")
        fun wrap(base: Context): Context {
            val tag = chosenTag(base) ?: return base
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tag))
            return base.createConfigurationContext(config)
        }

        /**
         * Android 12 and older: sets the chosen language (the system's for "System language") on [app]'s resources
         * and as the default locale (formatters). The application calls it in `onCreate` and in
         * `onConfigurationChanged` (a system change resets its resources); Android 13+ does nothing.
         */
        @SuppressLint("AppBundleLocaleChanges")
        @Suppress("DEPRECATION") // updateConfiguration is the only way to change the application's own resources.
        fun applyToApp(app: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
            val locales = chosenTag(app)?.let(LocaleList::forLanguageTags)
                ?: Resources.getSystem().configuration.locales
            val resources = app.resources
            if (resources.configuration.locales == locales) return
            val config = Configuration(resources.configuration)
            config.setLocales(locales)
            resources.updateConfiguration(config, resources.displayMetrics)
            Locale.setDefault(locales[0])
        }

        /** The chosen language's tag below Android 13; null on 13+ or for "System language". */
        private fun chosenTag(context: Context): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            null
        } else {
            LanguageChoice.of(prefs(context).getString(KEY_LANGUAGE, null)).tag
        }
    }
}
