package dev.hfps.reveal.data.handoff

import android.net.Uri

/**
 * Which documents of a hand-off still need the sender's read grant once the receiving activity is gone (ADR 0011
 * "Amendment W4", "Grant service"): only those make Reveal start the grant service, and their number is what its
 * notification counts. A Downloads document is copied while the grant lives (`LocalOriginals`); a `MediaStore.Images`
 * document needs it for its local previews unless the photo permission is FULL (then it stays readable); any other
 * provider's document needs it unless the sender offered a persistable grant (Reveal keeps that one). An ordinary
 * gallery share under FULL access needs nothing.
 */
object HandoffGrants {
    fun needing(uris: List<Uri>, persistable: Boolean, gallery: GalleryAccessState): List<Uri> = uris.filter { uri ->
        when {
            LocalOriginals.isDownloads(uri) -> true
            GalleryAccess.isMediaImages(uri.toString()) -> gallery != GalleryAccessState.FULL
            uri.scheme == "file" -> false
            else -> !persistable
        }
    }
}
