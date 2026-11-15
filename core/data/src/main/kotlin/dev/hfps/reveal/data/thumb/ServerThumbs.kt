package dev.hfps.reveal.data.thumb

import android.graphics.Bitmap

/** What [ServerThumbs] answered for one file. */
sealed interface ServerThumb {
    /** The thumbnail (upright, sRGB). */
    class Image(val bitmap: Bitmap) : ServerThumb

    /** Not now (the server is making it, or cannot be reached): show nothing, ask again later; never read the file. */
    data object Later : ServerThumb

    /** The server does not serve thumbnails for it (no previews, unsupported file): read the file as before. */
    data object NotServed : ServerThumb
}

/**
 * The server's grid thumbnails for photos it holds (ADR 0018 "W3/C", Hoard ADR 0015), implemented by `:core:sync`'s
 * library thumbnails: stored small on this device (the grid works offline), fetched in batches, never a range read of
 * the original and never a content provider round trip per cell.
 */
interface ServerThumbs {
    /** The thumbnail of the server file [fingerprint] (a photo's main file the server holds). */
    suspend fun thumbnail(fingerprint: String): ServerThumb

    /**
     * The server's preview of version [versionUuid] of the photo whose server file is [fingerprint] (Hoard ADR 0020:
     * an edit made on another device), or null when the server has none for it.
     */
    suspend fun editedThumbnail(fingerprint: String, versionUuid: String): Bitmap?
}
