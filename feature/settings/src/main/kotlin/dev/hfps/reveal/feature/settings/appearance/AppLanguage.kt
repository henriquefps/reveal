package dev.hfps.reveal.feature.settings.appearance

import android.annotation.SuppressLint
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
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
 * ([LocaleManager], also in the system's App languages, which recreates the activities); on Android 10-12 Reveal keeps
 * it itself and [wrap]s each activity's context, and the caller recreates the activity.
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
            val tag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                null
            } else {
                LanguageChoice.of(prefs(base).getString(KEY_LANGUAGE, null)).tag
            }
            if (tag == null) return base
            val config = Configuration(base.resources.configuration)
            config.setLocales(LocaleList.forLanguageTags(tag))
            return base.createConfigurationContext(config)
        }
    }
}
