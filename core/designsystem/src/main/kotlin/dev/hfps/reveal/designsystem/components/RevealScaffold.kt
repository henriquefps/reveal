package dev.hfps.reveal.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * The hfps shell: the fixed [topBar] (56dp on `background`, below the status bar and cutout) and ONE card that
 * fills the rest with an 8dp margin at the sides and bottom (plus the navigation bar inset), rounded 16dp, hairline
 * border. Content scrolls inside the card; the screen never does. Edge-to-edge: the caller enables it
 * (`enableEdgeToEdge()`) and this scaffold consumes the system-bar and cutout insets.
 */
@Composable
fun RevealScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(RevealTheme.colors.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
    ) {
        Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))) {
            topBar()
        }
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            shape = RevealTheme.shapes.card,
            color = RevealTheme.colors.card,
            contentColor = RevealTheme.colors.cardForeground,
            border = BorderStroke(1.dp, RevealTheme.colors.hairline),
        ) {
            Box(content = content)
        }
    }
}
