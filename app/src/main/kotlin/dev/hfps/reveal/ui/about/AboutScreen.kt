package dev.hfps.reveal.ui.about

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.BuildConfig
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealListRow
import dev.hfps.reveal.designsystem.components.RevealRowDivider
import dev.hfps.reveal.designsystem.components.RevealScaffold
import dev.hfps.reveal.designsystem.components.RevealTopBar
import dev.hfps.reveal.designsystem.theme.RevealTheme

/**
 * About (Settings -> About, and the library's ⋯ menu): version, Reveal's own licence, what Reveal does with photos and
 * the network (docs/privacy.md, short) and every third-party component shipped in the app with its licence
 * (docs/licences.md). No network.
 */
@Composable
fun AboutScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    AboutContent(
        version = AboutVersion.of(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong(), BuildConfig.BUILD_TYPE),
        licences = parseLicences(stringArrayResource(R.array.about_licences)),
        onBack = onBack,
        modifier = modifier,
    )
}

/** Test tags of About. */
object AboutTags {
    const val SCREEN = "about_screen"
    const val VERSION = "about_version"
    const val PRIVACY = "about_privacy"
}

/** Stateless About content (for screenshots). [version] is the line [AboutVersion.of] makes. */
@Composable
fun AboutContent(version: String, licences: List<LicenceEntry>, onBack: () -> Unit, modifier: Modifier = Modifier) {
    RevealScaffold(
        modifier = modifier.testTag(AboutTags.SCREEN),
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
                    modifier = Modifier.testTag(AboutTags.VERSION),
                )
                RevealRowDivider()
                RevealListRow(
                    title = stringResource(R.string.about_licence),
                    subtitle = stringResource(R.string.about_licence_body),
                )
                Eyebrow(
                    text = stringResource(R.string.about_privacy),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 8.dp),
                )
            }
            items(PRIVACY, key = { it }) { line ->
                Text(
                    stringResource(line),
                    style = RevealTheme.typography.body,
                    color = RevealTheme.colors.cardForeground,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag(AboutTags.PRIVACY),
                )
            }
            item {
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

/** The short privacy notes (docs/privacy.md), one row each. */
private val PRIVACY = listOf(
    R.string.about_privacy_none,
    R.string.about_privacy_originals,
    R.string.about_privacy_device,
    R.string.about_privacy_network,
    R.string.about_privacy_permissions,
)

/** The version line: name and code, plus the build type when it is not the release build. */
object AboutVersion {
    fun of(name: String, code: Long, buildType: String): String =
        if (buildType == "release") "$name ($code)" else "$name ($code, $buildType)"
}
