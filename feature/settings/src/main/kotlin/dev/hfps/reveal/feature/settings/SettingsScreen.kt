package dev.hfps.reveal.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.theme.RevealTheme
import javax.inject.Inject

/** Settings: for now the author and copyright that exports write (docs/adr/0009, "Metadata"). */
@HiltViewModel
class SettingsViewModel @Inject constructor(private val preferences: ExportPreferences) : ViewModel() {
    val authorship: Authorship get() = preferences.authorship.value

    fun setAuthorship(value: Authorship) = preferences.setAuthorship(value)
}

/** Adds the settings destination. */
fun NavGraphBuilder.settingsScreen(onBack: () -> Unit) {
    composable<SettingsRoute> { SettingsScreen(onBack = onBack) }
}

/** Test tags of the settings screen. */
object SettingsTags {
    const val AUTHOR = "settings_author"
    const val COPYRIGHT = "settings_copyright"
}

@Composable
fun SettingsScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: SettingsViewModel = hiltViewModel()) {
    var author by rememberSaveable { mutableStateOf(viewModel.authorship.author) }
    var copyright by rememberSaveable { mutableStateOf(viewModel.authorship.copyright) }
    fun save() = viewModel.setAuthorship(Authorship(author, copyright))
    SettingsContent(
        author = author,
        copyright = copyright,
        onAuthor = {
            author = it
            save()
        },
        onCopyright = {
            copyright = it
            save()
        },
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless settings content (screenshots). */
@Composable
fun SettingsContent(
    author: String,
    copyright: String,
    onAuthor: (String) -> Unit,
    onCopyright: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    RevealScaffold(
        modifier = modifier,
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Eyebrow(stringResource(R.string.settings_export))
            Field(stringResource(R.string.settings_author), author, onAuthor, SettingsTags.AUTHOR)
            Field(stringResource(R.string.settings_copyright), copyright, onCopyright, SettingsTags.COPYRIGHT)
            Text(
                stringResource(R.string.settings_authorship_note),
                style = RevealTheme.typography.ui,
                color = RevealTheme.colors.mutedForeground,
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    val c = RevealTheme.colors
    TextField(
        value = value,
        onValueChange = { onChange(it.take(MAX_LENGTH)) },
        singleLine = true,
        label = { Text(label, style = RevealTheme.typography.ui) },
        textStyle = RevealTheme.typography.body.copy(color = c.foreground),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            focusedIndicatorColor = c.primary,
            unfocusedIndicatorColor = c.input,
            cursorColor = c.primary,
            focusedLabelColor = c.mutedForeground,
            unfocusedLabelColor = c.mutedForeground,
        ),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

private const val MAX_LENGTH = 120
