package dev.hfps.reveal.feature.settings.appearance

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.designsystem.components.RevealDropdown
import dev.hfps.reveal.designsystem.components.RevealDropdownOption
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.theme.ThemeMode
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.feature.settings.SettingsBody
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What "Appearance and language" shows. */
@Immutable
data class AppearanceUiState(
    val theme: ThemeMode = ThemeMode.System,
    val language: LanguageChoice = LanguageChoice.SYSTEM,
)

/** What "Appearance and language" can ask for. */
@Immutable
data class AppearanceActions(
    val onBack: () -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onLanguage: (LanguageChoice) -> Unit = {},
)

/**
 * The sections of "Appearance and language", top to bottom. A new appearance setting (the hfps palettes) is one entry
 * here and one branch in [AppearanceSectionContent].
 */
enum class AppearanceSection {
    THEME,
    LANGUAGE,
}

/** Test tags of "Appearance and language". */
object AppearanceTags {
    const val SCREEN = "appearance_screen"
    const val THEME = "appearance_theme"
    const val LANGUAGE = "appearance_language"

    fun theme(mode: ThemeMode) = "appearance_theme_${mode.name.lowercase()}"

    fun language(choice: LanguageChoice) = "appearance_language_${choice.name.lowercase()}"
}

@HiltViewModel
class AppearanceViewModel @Inject constructor(private val themes: ThemeModeStore, private val language: AppLanguage) :
    ViewModel() {
    val state: StateFlow<AppearanceUiState> = combine(themes.mode, language.choice, ::AppearanceUiState)
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_MS),
            AppearanceUiState(themes.mode.value, language.choice.value),
        )

    fun setTheme(mode: ThemeMode) = themes.set(mode)

    /** True when the activity must be recreated by the caller (Android 12 and older). */
    fun setLanguage(choice: LanguageChoice): Boolean = language.set(choice)

    fun refresh() = language.refresh()

    private companion object {
        const val STOP_MS = 5_000L
    }
}

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppearanceViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    AppearanceContent(
        state = state,
        actions = AppearanceActions(
            onBack = onBack,
            onTheme = { mode ->
                viewModel.setTheme(mode)
                // Android 10-11: the activity's night mode is overridden from the stored choice before its first
                // frame (MainActivity); a new choice takes a new activity. 12+ recreates it through the system.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) (context as? Activity)?.recreate()
            },
            onLanguage = { choice ->
                // Android 13+ recreates the activities itself; older versions read the choice in attachBaseContext.
                if (viewModel.setLanguage(choice)) (context as? Activity)?.recreate()
            },
        ),
        modifier = modifier,
    )
}

/** Stateless "Appearance and language" (screenshots, tests). [openSection] shows that section's menu open. */
@Composable
fun AppearanceContent(
    state: AppearanceUiState,
    actions: AppearanceActions,
    modifier: Modifier = Modifier,
    openSection: AppearanceSection? = null,
) {
    RevealScaffold(
        modifier = modifier.testTag(AppearanceTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.appearance_title),
                onBack = actions.onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        SettingsBody {
            for (section in AppearanceSection.entries) {
                AppearanceSectionContent(section, state, actions, open = section == openSection)
            }
        }
    }
}

@Composable
private fun AppearanceSectionContent(
    section: AppearanceSection,
    state: AppearanceUiState,
    actions: AppearanceActions,
    open: Boolean,
) {
    val modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
    when (section) {
        AppearanceSection.THEME -> RevealDropdown(
            label = stringResource(R.string.appearance_theme),
            options = listOf(
                RevealDropdownOption(ThemeMode.System, stringResource(R.string.appearance_theme_system)),
                RevealDropdownOption(ThemeMode.Light, stringResource(R.string.appearance_theme_light)),
                RevealDropdownOption(ThemeMode.Dark, stringResource(R.string.appearance_theme_dark)),
            ).map { it.copy(tag = AppearanceTags.theme(it.value)) },
            selected = state.theme,
            onSelect = actions.onTheme,
            help = stringResource(R.string.appearance_theme_help),
            tag = AppearanceTags.THEME,
            initiallyOpen = open,
            modifier = modifier,
        )

        AppearanceSection.LANGUAGE -> RevealDropdown(
            label = stringResource(R.string.appearance_language),
            options = listOf(
                RevealDropdownOption(LanguageChoice.SYSTEM, stringResource(R.string.appearance_language_system)),
                RevealDropdownOption(LanguageChoice.ENGLISH, stringResource(R.string.appearance_language_en)),
                RevealDropdownOption(
                    LanguageChoice.PORTUGUESE_BRAZIL,
                    stringResource(R.string.appearance_language_pt_br),
                ),
            ).map { it.copy(tag = AppearanceTags.language(it.value)) },
            selected = state.language,
            onSelect = actions.onLanguage,
            help = stringResource(R.string.appearance_language_help),
            tag = AppearanceTags.LANGUAGE,
            initiallyOpen = open,
            modifier = modifier,
        )
    }
}
