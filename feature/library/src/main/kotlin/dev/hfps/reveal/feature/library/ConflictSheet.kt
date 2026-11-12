package dev.hfps.reveal.feature.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.sync.engine.ConflictPair
import dev.hfps.reveal.sync.engine.ConflictSide
import java.text.DateFormat
import java.util.Date

/** Test tags of the conflict chooser. */
object ConflictTags {
    const val SHEET = "conflict-sheet"
    const val KEEP_MAIN = "conflict-keep-main"
    const val KEEP_COPY = "conflict-keep-copy"
    const val VIEWER_RESOLVE = "viewer-resolve-conflict"
}

/** What the chooser shows: the photo's [name] and its two versions. */
@Immutable
data class ConflictChoice(val name: String, val pair: ConflictPair)

/**
 * The conflict chooser (ADR 0018 amendment FIX/W): the photo's main version and its conflict copy side by side, each
 * rendered with its own edit and named by the device and time of its last change, with "Keep this one" under each.
 * Keeping one deletes the other on every device; keeping the copy makes its edit the main version's.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConflictSheet(
    choice: ConflictChoice,
    thumbnail: suspend (ConflictSide) -> ImageBitmap?,
    onKeep: (keepCopy: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        ConflictContent(choice, thumbnail, onKeep)
    }
}

@Composable
fun ConflictContent(
    choice: ConflictChoice,
    thumbnail: suspend (ConflictSide) -> ImageBitmap?,
    onKeep: (keepCopy: Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(ConflictTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.conflict_title), style = RevealTheme.typography.title, color = colors.foreground)
        Text(choice.name, style = RevealTheme.typography.mono, color = colors.foreground, maxLines = 1)
        Text(
            stringResource(R.string.conflict_body),
            style = RevealTheme.typography.body,
            color = colors.mutedForeground,
        )
        Row(
            Modifier.fillMaxWidth().padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ConflictCard(choice.pair.main, thumbnail, { onKeep(false) }, ConflictTags.KEEP_MAIN, Modifier.weight(1f))
            ConflictCard(choice.pair.copy, thumbnail, { onKeep(true) }, ConflictTags.KEEP_COPY, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConflictCard(
    side: ConflictSide,
    thumbnail: suspend (ConflictSide) -> ImageBitmap?,
    onKeep: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    var image by remember(side.versionId, side.editTime) { mutableStateOf<ImageBitmap?>(null) }
    val load by rememberUpdatedState(thumbnail)
    LaunchedEffect(side.versionId, side.editTime) { image = load(side) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Eyebrow(stringResource(if (side.copy) R.string.conflict_copy else R.string.conflict_main))
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(8.dp))
                .background(colors.muted),
        ) {
            image?.let {
                Image(
                    it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
        Text(
            deviceText(side.device),
            style = RevealTheme.typography.body,
            color = colors.foreground,
            maxLines = 1,
        )
        side.editedAt?.let { at ->
            val locale = LocalConfiguration.current.locales[0]
            val text = remember(at, locale) {
                DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(at))
            }
            Text(text, style = RevealTheme.typography.ui, color = colors.mutedForeground, maxLines = 1)
        }
        PrimaryButton(
            text = stringResource(R.string.conflict_keep),
            onClick = onKeep,
            icon = PhosphorRegular.Check,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}

@Composable
private fun deviceText(device: String?): String = when {
    device == null -> stringResource(R.string.conflict_device_unknown)
    device.isEmpty() -> stringResource(R.string.conflict_this_device)
    else -> device
}
