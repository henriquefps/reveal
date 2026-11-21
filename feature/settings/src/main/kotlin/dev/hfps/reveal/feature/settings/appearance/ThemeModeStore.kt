package dev.hfps.reveal.feature.settings.appearance

import android.app.UiModeManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.core.content.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.designsystem.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's [ThemeMode] for the library, the settings and the sheets (the editor and the viewer stay dark),
 * Settings -> Appearance and language. Read synchronously when the store is made, so the first frame already has it
 * (no light-dark flash). On Android 12+ it is also the app's night mode for the system
 * ([UiModeManager.setApplicationNightMode]): the splash screen and the window background follow it; the application
 * applies it at every start so the system's copy never disagrees with the stored choice. Below Android 12 the activity
 * overrides its own night mode from [stored] before its first frame.
 */
@Singleton
class ThemeModeStore internal constructor(private val prefs: SharedPreferences, private val uiModes: UiModeManager?) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE),
        context.getSystemService(UiModeManager::class.java),
    )

    private val state = MutableStateFlow(parse(prefs.getString(KEY_THEME, null)))
    val mode: StateFlow<ThemeMode> = state.asStateFlow()

    fun set(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        state.value = mode
        applyNightMode(mode)
    }

    /** Tells the system the app's night mode (Android 12+); the application calls it at every start. */
    fun applyNightMode(mode: ThemeMode = state.value) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val night = when (mode) {
            ThemeMode.System -> UiModeManager.MODE_NIGHT_AUTO
            ThemeMode.Light -> UiModeManager.MODE_NIGHT_NO
            ThemeMode.Dark -> UiModeManager.MODE_NIGHT_YES
        }
        // A missing service (or a device that refuses it) only costs the splash screen's colours.
        runCatching { uiModes?.setApplicationNightMode(night) }
    }

    companion object {
        const val FILE = "appearance"
        const val KEY_THEME = "theme"

        /** The stored mode, read directly (an activity's `attachBaseContext`, before injection). */
        fun stored(context: Context): ThemeMode =
            parse(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_THEME, null))

        /** A stored name to a mode; anything unknown (a newer app's value) is System. */
        fun parse(stored: String?): ThemeMode = ThemeMode.entries.firstOrNull { it.name == stored } ?: ThemeMode.System
    }
}
