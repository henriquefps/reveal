package dev.hfps.reveal.feature.library

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.handoff.HandoffApps
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.designsystem.components.GhostButton
import dev.hfps.reveal.designsystem.components.PrimaryButton
import dev.hfps.reveal.designsystem.components.RevealBottomSheet
import dev.hfps.reveal.designsystem.components.SecondaryButton
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme
import javax.inject.Inject

/** Test tags of the "unavailable photo" explanation. */
object UnavailableTags {
    const val SHEET = "unavailable-sheet"
    const val OPEN_APP = "unavailable-open-app"
    const val GALLERY = "unavailable-gallery"
    const val TRY_AGAIN = "unavailable-try-again"
    const val REMOVE = "unavailable-remove"
    const val FORGET_CONFIRM = "unavailable-forget-confirm"
    const val FORGET_CANCEL = "unavailable-forget-cancel"
    const val SELECTION_TRY_AGAIN = "library-unavailable-try-again"
    const val SELECTION_REMOVE = "library-unavailable-remove"
    const val VIEWER_DETAILS = "viewer-unavailable-details"

    fun cell(id: Long) = "library-unavailable-$id"
}

/** Why Reveal cannot show a photo (it picks the explanation's text). */
enum class UnavailableReason {
    /** An app handed it over and its temporary grant ended. */
    SENDER,

    /** A folder photo whose file is gone or moved. */
    FILE,

    /** Its folder is not mounted or its grant was lost. */
    FOLDER,
}

/** What the explanation sheet shows for one unavailable photo. */
@Immutable
data class UnavailableInfo(
    val photoId: Long,
    val name: String,
    val hasEdit: Boolean,
    val reason: UnavailableReason,
    /** The app that shared it (to open and share again), when known. */
    val appLabel: String? = null,
    val appLaunch: Intent? = null,
    /** Gallery access would make its MediaStore URI readable again. */
    val gallery: Boolean = false,
)

/** Builds [UnavailableInfo] and opens the sending app; shared by the grid's and the viewer's ViewModels. */
class UnavailableHelper @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val library: LibraryRepository,
    private val gallery: GalleryAccess,
) {
    suspend fun info(photoId: Long): UnavailableInfo? {
        val detail = library.unavailableDetail(photoId) ?: return null
        val sender = detail.uri?.let { HandoffApps.sender(context.packageManager, it, context.packageName) }
        val reason = when {
            detail.sourceKind == SourceKind.HANDOFF -> UnavailableReason.SENDER
            detail.sourceState != SourceState.AVAILABLE -> UnavailableReason.FOLDER
            else -> UnavailableReason.FILE
        }
        return UnavailableInfo(
            photoId = photoId,
            name = detail.name,
            hasEdit = detail.hasEdit,
            reason = reason,
            appLabel = sender?.label,
            appLaunch = sender?.launch,
            gallery = detail.uri?.let { GalleryAccess.isMediaStore(it.toString()) } == true &&
                gallery.state.value != GalleryAccessState.FULL,
        )
    }

    /** Opens the app that shared the photo, so the owner can share it to Reveal again. */
    fun openApp(info: UnavailableInfo) {
        val launch = info.appLaunch ?: return
        runCatching { context.startActivity(Intent(launch).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/** What happened to the photo and what can be done, as a sheet (a centred dialog on wide windows). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnavailableSheet(
    info: UnavailableInfo,
    onOpenApp: () -> Unit,
    onGallery: () -> Unit,
    onTryAgain: () -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    RevealBottomSheet(onDismissRequest = onDismiss) {
        UnavailableContent(info, onOpenApp, onGallery, onTryAgain, onRemove)
    }
}

@Composable
fun UnavailableContent(
    info: UnavailableInfo,
    onOpenApp: () -> Unit,
    onGallery: () -> Unit,
    onTryAgain: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = RevealTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag(UnavailableTags.SHEET),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                PhosphorRegular.ImageBroken,
                contentDescription = null,
                tint = colors.mutedForeground,
                modifier = Modifier.size(28.dp),
            )
            Text(
                stringResource(R.string.library_unavailable_title),
                style = RevealTheme.typography.title,
                color = colors.foreground,
            )
        }
        Text(info.name, style = RevealTheme.typography.mono, color = colors.foreground, maxLines = 1)
        Text(reasonText(info), style = RevealTheme.typography.body, color = colors.mutedForeground)
        Text(
            stringResource(R.string.library_unavailable_keeps),
            style = RevealTheme.typography.body,
            color = colors.mutedForeground,
        )
        if (info.appLabel != null && info.appLaunch != null) {
            PrimaryButton(
                text = stringResource(R.string.library_unavailable_open_app, info.appLabel),
                onClick = onOpenApp,
                icon = PhosphorRegular.ArrowSquareOut,
                modifier = Modifier.fillMaxWidth().testTag(UnavailableTags.OPEN_APP),
            )
        }
        if (info.gallery) {
            PrimaryButton(
                text = stringResource(R.string.library_unavailable_gallery),
                onClick = onGallery,
                icon = PhosphorRegular.Image,
                modifier = Modifier.fillMaxWidth().testTag(UnavailableTags.GALLERY),
            )
        }
        SecondaryButton(
            text = stringResource(R.string.library_unavailable_try_again),
            onClick = onTryAgain,
            icon = PhosphorRegular.ArrowsClockwise,
            modifier = Modifier.fillMaxWidth().testTag(UnavailableTags.TRY_AGAIN),
        )
        GhostButton(
            text = stringResource(R.string.library_unavailable_remove),
            onClick = onRemove,
            icon = PhosphorRegular.Trash,
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag(UnavailableTags.REMOVE),
        )
    }
}

@Composable
private fun reasonText(info: UnavailableInfo): String = when (info.reason) {
    UnavailableReason.SENDER ->
        if (info.appLabel != null) {
            stringResource(R.string.library_unavailable_reason_sender_named, info.appLabel)
        } else {
            stringResource(R.string.library_unavailable_reason_sender)
        }

    UnavailableReason.FILE -> stringResource(R.string.library_unavailable_reason_file)

    UnavailableReason.FOLDER -> stringResource(R.string.library_unavailable_reason_folder)
}

/** The photos "Remove from Reveal" is about to forget; [edited] of them have edits, which go too. */
@Immutable
data class ForgetPrompt(val ids: List<Long>, val edited: Int)

/** Asked before forgetting photos that have edits (the edits go too and cannot come back). */
@Composable
fun ForgetDialog(prompt: ForgetPrompt, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = RevealTheme.colors.card,
        shape = RevealTheme.shapes.card,
        title = {
            Text(
                pluralStringResource(R.plurals.library_forget_question, prompt.ids.size, prompt.ids.size),
                style = RevealTheme.typography.title,
            )
        },
        text = {
            Text(
                stringResource(R.string.library_forget_body_edited),
                style = RevealTheme.typography.body,
                color = RevealTheme.colors.mutedForeground,
            )
        },
        confirmButton = {
            PrimaryButton(
                stringResource(R.string.library_forget_confirm),
                onConfirm,
                modifier = Modifier.testTag(UnavailableTags.FORGET_CONFIRM),
            )
        },
        dismissButton = {
            GhostButton(
                stringResource(R.string.library_cancel),
                onDismiss,
                modifier = Modifier.testTag(UnavailableTags.FORGET_CANCEL),
            )
        },
    )
}
