@file:Suppress("MatchingDeclarationName")

package dev.hfps.reveal.feature.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.theme.RevealTheme

/** Test tags of the gallery access offer. */
object GalleryAccessTags {
    const val SHEET = "gallery-access-sheet"
    const val ALLOW = "gallery-access-allow"
    const val DECLINE = "gallery-access-decline"
}

/**
 * The once-only offer of the "Photos and videos" permission (ADR 0011 "Gallery access"): shown when a photo the gallery
 * shared can no longer be read. Allowing opens the system's permission dialog (where "selected photos only" is fine);
 * "Not now" is remembered, and Settings can turn it on later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GalleryAccessSheet(onAllow: () -> Unit, onDecline: () -> Unit) {
    RevealBottomSheet(onDismissRequest = onDecline) {
        GalleryAccessContent(onAllow, onDecline)
    }
}

@Composable
fun GalleryAccessContent(onAllow: () -> Unit, onDecline: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = 20.dp,
            vertical = 8.dp,
        ).testTag(GalleryAccessTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(R.string.library_gallery_access_title),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
        )
        Text(
            stringResource(R.string.library_gallery_access_body),
            style = RevealTheme.typography.body,
            color = RevealTheme.colors.mutedForeground,
        )
        PrimaryButton(
            text = stringResource(R.string.library_gallery_access_allow),
            onClick = onAllow,
            modifier = Modifier.fillMaxWidth().testTag(GalleryAccessTags.ALLOW),
        )
        GhostButton(
            text = stringResource(R.string.library_gallery_access_decline),
            onClick = onDecline,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(GalleryAccessTags.DECLINE),
        )
    }
}
