package dev.hfps.reveal.theme

import dev.hfps.reveal.designsystem.theme.ThemeMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the user's [ThemeMode] (default System). In memory for now; the settings feature swaps the backing for
 * DataStore without changing readers: they only observe [mode].
 */
@Singleton
class ThemeModeStore @Inject constructor() {
    private val state = MutableStateFlow(ThemeMode.System)
    val mode: StateFlow<ThemeMode> = state.asStateFlow()

    fun set(mode: ThemeMode) {
        state.value = mode
    }
}
