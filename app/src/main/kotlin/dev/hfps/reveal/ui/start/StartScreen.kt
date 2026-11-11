package dev.hfps.reveal.ui.start

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * MIME types the picker offers: every image (JPEG, HEIF, PNG, WebP and the `image/x-*` RAW types) plus
 * `application/octet-stream`, which some providers report for ARW and other RAW files.
 */
val OpenPhotoMimeTypes: Array<String> = arrayOf("image/*", "application/octet-stream")

/** Test tags of the start screen. */
object StartTags {
    const val OPEN = "start-open"
    const val MENU = "start-menu"
}

/**
 * The temporary M0 start screen: one "Open photo" action (Storage Access Framework) until the library (M1) replaces
 * it, and a menu with the design-system gallery and About.
 */
@Composable
fun StartScreen(
    onOpenPhoto: (Uri) -> Unit,
    onGallery: () -> Unit,
    onAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onOpenPhoto(uri)
    }
    StartContent(
        onOpen = { picker.launch(OpenPhotoMimeTypes) },
        onGallery = onGallery,
        onAbout = onAbout,
        modifier = modifier,
    )
}

/** Stateless start screen (for screenshots). */
@Composable
fun StartContent(onOpen: () -> Unit, onGallery: () -> Unit, onAbout: () -> Unit, modifier: Modifier = Modifier) {
    val colors = RevealTheme.colors
    RevealScaffold(
        modifier = modifier,
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.app_name),
                actions = { StartMenu(onGallery, onAbout) },
            )
        },
    ) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
            Column(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(
                    imageVector = PhosphorRegular.Aperture,
                    contentDescription = null,
                    tint = colors.primary,
                    modifier = Modifier.size(48.dp),
                )
                Eyebrow(stringResource(R.string.start_eyebrow))
                Text(
                    text = stringResource(R.string.start_title),
                    style = RevealTheme.typography.title,
                    color = colors.foreground,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.start_body),
                    style = RevealTheme.typography.body,
                    color = colors.mutedForeground,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(8.dp))
                PrimaryButton(
                    text = stringResource(R.string.start_open),
                    onClick = onOpen,
                    icon = PhosphorRegular.Images,
                    modifier = Modifier.testTag(StartTags.OPEN),
                )
            }
        }
    }
}

@Composable
private fun StartMenu(onGallery: () -> Unit, onAbout: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        RevealIconButton(
            icon = PhosphorRegular.DotsThree,
            contentDescription = stringResource(R.string.start_menu),
            onClick = { open = true },
            modifier = Modifier.testTag(StartTags.MENU),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            containerColor = RevealTheme.colors.card,
            shape = RevealTheme.shapes.control,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.start_menu_gallery), style = RevealTheme.typography.ui) },
                leadingIcon = { Icon(PhosphorRegular.Palette, contentDescription = null) },
                onClick = {
                    open = false
                    onGallery()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.start_menu_about), style = RevealTheme.typography.ui) },
                leadingIcon = { Icon(PhosphorRegular.Info, contentDescription = null) },
                onClick = {
                    open = false
                    onAbout()
                },
            )
        }
    }
}
