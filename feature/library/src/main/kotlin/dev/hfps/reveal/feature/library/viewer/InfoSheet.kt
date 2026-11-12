package dev.hfps.reveal.feature.library.viewer

import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.hfps.image.format.ExifTime
import dev.hfps.image.format.PhotoMetadata
import dev.hfps.reveal.data.library.OriginalPlace
import dev.hfps.reveal.data.library.PhotoDetail
import dev.hfps.reveal.designsystem.components.Eyebrow
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.RevealIconButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.R
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** The photo's details read from its files: camera, lens, exposure, date, dimensions, file names and sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(photoId: Long, pages: ViewerPages, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val loaded by produceState<LoadedInfo?>(null, photoId) {
        val detail = pages.detail(photoId)
        value = detail?.let { LoadedInfo(it, pages.metadata(it), pages.originalPlace(photoId)) }
    }
    RevealBottomSheet(onDismissRequest = onDismiss, modifier = modifier.testTag(ViewerTags.INFO_SHEET)) {
        val info = loaded
        InfoContent(info?.detail, info?.metadata, place = info?.place)
    }
}

/**
 * The same details as a side panel beside the photo on wide windows (tablets, unfolded foldables): it follows the
 * page shown, and [onClose] hides it.
 */
@Composable
fun InfoPanel(photoId: Long, pages: ViewerPages, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val loaded by produceState<LoadedInfo?>(null, photoId) {
        val detail = pages.detail(photoId)
        value = detail?.let { LoadedInfo(it, pages.metadata(it), pages.originalPlace(photoId)) }
    }
    val colors = RevealTheme.colors
    Surface(
        modifier = modifier
            .padding(8.dp)
            .testTag(ViewerTags.INFO_SHEET),
        shape = RevealTheme.shapes.card,
        color = colors.card,
        contentColor = colors.cardForeground,
        border = BorderStroke(1.dp, colors.hairline),
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp)) {
                Eyebrow(stringResource(R.string.viewer_info), Modifier.weight(1f))
                RevealIconButton(
                    icon = PhosphorRegular.X,
                    contentDescription = stringResource(R.string.viewer_info_close),
                    onClick = onClose,
                )
            }
            val info = loaded
            InfoContent(info?.detail, info?.metadata, place = info?.place)
        }
    }
}

private class LoadedInfo(val detail: PhotoDetail, val metadata: PhotoMetadata?, val place: OriginalPlace?)

/** Stateless info rows (screenshots). [place]: where the original is (the "Original" line, ADR 0011 "Fallbacks"). */
@Composable
fun InfoContent(
    detail: PhotoDetail?,
    metadata: PhotoMetadata?,
    modifier: Modifier = Modifier,
    place: OriginalPlace? = null,
) {
    val unknown = stringResource(R.string.info_unknown)
    val context = LocalContext.current
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
            .navigationBarsPadding()
            .padding(bottom = 16.dp),
    ) {
        Eyebrow(stringResource(R.string.info_title), Modifier.padding(bottom = 8.dp))
        val m = metadata
        val camera = listOfNotNull(m?.make?.trim(), m?.model?.trim()).filter {
            it.isNotEmpty()
        }.joinToString(" ").ifEmpty { unknown }
        InfoRow(stringResource(R.string.info_camera), camera)
        InfoRow(stringResource(R.string.info_lens), m?.lens?.takeIf { it.isNotBlank() } ?: unknown)
        InfoRow(
            stringResource(R.string.info_shutter),
            m?.exposureLabel?.let { stringResource(R.string.info_shutter_value, it) } ?: unknown,
        )
        InfoRow(
            stringResource(R.string.info_aperture),
            m?.fNumber?.let { "f/" + String.format(Locale.getDefault(), "%.1f", it) } ?: unknown,
        )
        InfoRow(stringResource(R.string.info_iso), m?.iso?.toString() ?: unknown)
        InfoRow(stringResource(R.string.info_focal_length), focal(m) ?: unknown)
        InfoRow(stringResource(R.string.info_date), date(m) ?: unknown)
        InfoRow(stringResource(R.string.info_dimensions), dimensions(m) ?: unknown)
        detail?.photo?.rating?.takeIf { it > 0 }?.let { stars ->
            InfoRow(stringResource(R.string.info_rating), pluralStringResource(R.plurals.library_stars, stars, stars))
        }
        place?.let {
            InfoRow(stringResource(R.string.info_original), stringResource(it.meaning()), ViewerTags.INFO_ORIGINAL)
        }
        detail?.files?.forEachIndexed { index, file ->
            InfoRow(
                if (index == 0) stringResource(R.string.info_files) else "",
                stringResource(R.string.info_file_value, file.name, Formatter.formatShortFileSize(context, file.size)),
            )
        }
    }
}

@Composable
private fun focal(m: PhotoMetadata?): String? {
    val focal = m?.focalLength ?: return null
    val locale = LocalConfiguration.current.locales[0]
    val value = if (focal % 1.0 == 0.0) focal.toInt().toString() else String.format(locale, "%.1f", focal)
    val equivalent = m.focalLength35
    return if (equivalent != null && equivalent != focal.toInt()) {
        stringResource(R.string.info_focal_value_35, value, equivalent)
    } else {
        stringResource(R.string.info_focal_value, value)
    }
}

private fun date(m: PhotoMetadata?): String? {
    val millis = m?.captureSortMillis ?: return null
    val format = DateFormat.getDateTimeInstance(DateFormat.LONG, DateFormat.MEDIUM).apply {
        timeZone =
            TimeZone.getTimeZone("UTC")
    }
    val sub = ExifTime.subSecondMillis(m.subSecond)
    val text = format.format(Date(millis))
    return if (sub > 0) "$text (.${sub.toString().padStart(MILLIS_DIGITS, '0')})" else text
}

@Composable
private fun dimensions(m: PhotoMetadata?): String? {
    val w = m?.width
    val h = m?.height
    if (w == null || h == null) return null
    val (uw, uh) = if (m.isTransposed) h to w else w to h
    val mp = String.format(LocalConfiguration.current.locales[0], "%.1f", uw.toLong() * uh / PIXELS_PER_MEGAPIXEL)
    return stringResource(R.string.info_dimensions_value, uw, uh, mp)
}

private const val MILLIS_DIGITS = 3
private const val PIXELS_PER_MEGAPIXEL = 1_000_000.0

/** What each place means for the owner: where the original is, and what opens and exports. */
private fun OriginalPlace.meaning(): Int = when (this) {
    OriginalPlace.DEVICE -> R.string.info_original_device
    OriginalPlace.DEVICE_AND_SERVER -> R.string.info_original_backed_up
    OriginalPlace.SERVER -> R.string.info_original_server
    OriginalPlace.ELSEWHERE -> R.string.info_original_elsewhere
    OriginalPlace.HANDOFF_SERVER -> R.string.info_original_handoff_server
    OriginalPlace.HANDOFF_PREVIEW -> R.string.info_original_handoff_preview
    OriginalPlace.HANDOFF_UNREADABLE -> R.string.info_original_handoff_unreadable
}

@Composable
private fun InfoRow(label: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).then(if (tag != null) Modifier.testTag(tag) else Modifier)) {
        Text(
            label,
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.mutedForeground,
            modifier = Modifier.width(120.dp),
        )
        Text(
            value,
            style = RevealTheme.typography.ui,
            color = RevealTheme.colors.foreground,
            modifier = Modifier.weight(1f),
        )
    }
}
