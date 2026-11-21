package dev.hfps.reveal.feature.settings.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.xmp.XmpPreferences
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.Hint
import dev.hfps.reveal.feature.settings.R
import dev.hfps.reveal.feature.settings.SectionTitle
import dev.hfps.reveal.feature.settings.SettingsBody
import dev.hfps.reveal.feature.settings.SwitchRow
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/** "Editing and XMP": edits read from XMP sidecars (ADR 0026), the author and copyright exports write (ADR 0009). */
@HiltViewModel
class EditingViewModel @Inject constructor(
    private val preferences: ExportPreferences,
    private val xmp: XmpPreferences,
) : ViewModel() {
    val readEdits: StateFlow<Boolean> = xmp.readEdits

    fun setReadEdits(on: Boolean) = xmp.setReadEdits(on)

    val authorship: Authorship get() = preferences.authorship.value

    fun setAuthorship(value: Authorship) = preferences.setAuthorship(value)
}

/** Test tags of "Editing and XMP". */
object EditingTags {
    const val SCREEN = "editing_screen"
    const val AUTHOR = "settings_author"
    const val COPYRIGHT = "settings_copyright"
    const val XMP_NOTE = "settings_xmp_note"
    const val XMP_READ = "settings_xmp_read"
}

@Composable
fun EditingScreen(onBack: () -> Unit, modifier: Modifier = Modifier, viewModel: EditingViewModel = hiltViewModel()) {
    val readEdits by viewModel.readEdits.collectAsStateWithLifecycle()
    var author by rememberSaveable { mutableStateOf(viewModel.authorship.author) }
    var copyright by rememberSaveable { mutableStateOf(viewModel.authorship.copyright) }
    fun save() = viewModel.setAuthorship(Authorship(author, copyright))
    EditingContent(
        readEdits = readEdits,
        onReadEdits = viewModel::setReadEdits,
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

/** Stateless "Editing and XMP" (screenshots, tests). */
@Composable
fun EditingContent(
    readEdits: Boolean,
    author: String,
    copyright: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onReadEdits: (Boolean) -> Unit = {},
    onAuthor: (String) -> Unit = {},
    onCopyright: (String) -> Unit = {},
) {
    RevealScaffold(
        modifier = modifier.testTag(EditingTags.SCREEN),
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.editing_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.settings_back),
            )
        },
    ) {
        SettingsBody {
            SectionTitle(stringResource(R.string.settings_section_xmp), first = true)
            SwitchRow(
                title = stringResource(R.string.settings_xmp_read),
                subtitle = stringResource(R.string.settings_xmp_read_note),
                checked = readEdits,
                onChange = onReadEdits,
                tag = EditingTags.XMP_READ,
            )
            Hint(stringResource(R.string.settings_xmp_note), Modifier.testTag(EditingTags.XMP_NOTE))

            SectionTitle(stringResource(R.string.settings_export))
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Field(stringResource(R.string.settings_author), author, onAuthor, EditingTags.AUTHOR)
                Field(stringResource(R.string.settings_copyright), copyright, onCopyright, EditingTags.COPYRIGHT)
                Text(
                    stringResource(R.string.settings_authorship_note),
                    style = RevealTheme.typography.ui,
                    color = RevealTheme.colors.mutedForeground,
                )
            }
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
