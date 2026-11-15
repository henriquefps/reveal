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

/** Which offer of the photo permission (ADR 0011 "Gallery access", "Amendment W4"); each is answered once. */
enum class GalleryOffer {
    /** A photo the gallery shared can no longer be read ("selected photos only" is fine). */
    SHARED,

    /** Hoard handed over its MediaStore copies: every photo is needed (the partial grant covers only the selected). */
    HOARD,
}

/**
 * The once-only offers of the "Photos and videos" permission (ADR 0011 "Gallery access", "Amendment W4"): [offer]
 * says which (and its text). Allowing opens the system's permission dialog; "Not now" is remembered, and Settings can
 * turn it on later. Shown by the library, and by the app over the editor for a single photo from Hoard.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryAccessSheet(offer: GalleryOffer, partial: Boolean, onAllow: () -> Unit, onDecline: () -> Unit) {
    RevealBottomSheet(onDismissRequest = onDecline) {
        GalleryAccessContent(onAllow, onDecline, offer = offer, partial = partial)
    }
}

@Composable
fun GalleryAccessContent(
    onAllow: () -> Unit,
    onDecline: () -> Unit,
    modifier: Modifier = Modifier,
    offer: GalleryOffer = GalleryOffer.SHARED,
    partial: Boolean = false,
) {
    val hoard = offer == GalleryOffer.HOARD
    Column(
        modifier = modifier.fillMaxWidth().padding(
            horizontal = 20.dp,
            vertical = 8.dp,
        ).testTag(GalleryAccessTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                if (hoard) R.string.library_gallery_access_hoard_title else R.string.library_gallery_access_title,
            ),
            style = RevealTheme.typography.title,
            color = RevealTheme.colors.foreground,
        )
        Text(
            stringResource(
                when {
                    !hoard -> R.string.library_gallery_access_body
                    partial -> R.string.library_gallery_access_hoard_body_partial
                    else -> R.string.library_gallery_access_hoard_body
                },
            ),
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
