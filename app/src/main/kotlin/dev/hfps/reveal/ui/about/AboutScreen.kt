package dev.hfps.reveal.ui.about

import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar

/** About: version, Reveal's own licence and every third-party component shipped in the app with its licence. */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val version = remember(context) { versionName(context) }
    AboutContent(
        version = version,
        licences = parseLicences(stringArrayResource(R.array.about_licences)),
        onBack = onBack,
        modifier = modifier,
    )
}

/** Stateless About content (for screenshots). */
@Composable
fun AboutContent(version: String, licences: List<LicenceEntry>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    RevealScaffold(
        modifier = modifier,
        topBar = {
            RevealTopBar(
                title = stringResource(R.string.about_title),
                onBack = onBack,
                backContentDescription = stringResource(R.string.about_back),
            )
        },
    ) {
        LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
            item {
                RevealListRow(
                    title = stringResource(R.string.app_name),
                    subtitle = stringResource(R.string.about_version, version),
                )
                RevealRowDivider()
                RevealListRow(
                    title = stringResource(R.string.about_licence),
                    subtitle = stringResource(R.string.about_licence_body),
                )
                Eyebrow(
                    text = stringResource(R.string.about_third_party),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            items(licences, key = { it.component }) { entry ->
                RevealListRow(title = entry.component, subtitle = entry.licence)
            }
        }
    }
}

private fun versionName(context: Context): String = try {
    context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()
} catch (_: PackageManager.NameNotFoundException) {
    ""
}
