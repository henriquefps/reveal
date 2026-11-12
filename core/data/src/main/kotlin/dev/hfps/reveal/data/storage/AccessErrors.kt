package dev.hfps.reveal.data.storage

import java.io.FileNotFoundException

/**
 * Whether [error] means the document cannot be read by Reveal any more (ADR 0011 "Unreadable hand-offs"), also when
 * wrapped. Every shape a provider uses for "no access" or "no such document" counts:
 *
 * - `SecurityException` (`Permission Denial: opening provider ...` once a temporary grant ended, MediaStore's
 *   `... has no access to content://media/...`);
 * - `FileNotFoundException` (the document is gone, or `ImageInput.fromUri` turned a refusal into one);
 * - an `IllegalArgumentException` / `IllegalStateException` / `UnsupportedOperationException` a provider threw across
 *   Binder (it was rethrown by `DatabaseUtils.readExceptionFromParcel` or raised by `ContentResolver`): a provider that
 *   no longer serves the URI (`Unknown URI`, `Invalid document id`);
 * - any message that says so (`Permission Denial`, `no access to`), whatever the type.
 *
 * Other failures (a damaged file, no memory, a decoder's own `IllegalArgumentException`) are not about access. One
 * helper for the editor's opener, thumbnails, the viewer, sync and export.
 */
@Suppress("ReturnCount")
fun isAccessError(error: Throwable): Boolean {
    var e: Throwable? = error
    var depth = 0
    while (e != null && depth++ < MAX_DEPTH) {
        if (e is SecurityException || e is FileNotFoundException) return true
        if (deniedMessage(e.message)) return true
        if (isProviderRefusal(e)) return true
        e = e.cause
    }
    return false
}

private fun deniedMessage(message: String?): Boolean =
    message != null && DENIED_MESSAGES.any { message.contains(it, ignoreCase = true) }

/** A runtime exception a content provider raised for this URI (it crossed Binder or came from `ContentResolver`). */
private fun isProviderRefusal(e: Throwable): Boolean {
    if (e !is IllegalArgumentException && e !is IllegalStateException && e !is UnsupportedOperationException) {
        return false
    }
    return e.stackTrace.take(PROVIDER_FRAMES).any { frame -> PROVIDER_CLASSES.any { frame.className.startsWith(it) } }
}

private const val MAX_DEPTH = 8

/** How far up the stack a provider frame may be (the throw site is a few frames inside `ContentResolver`). */
private const val PROVIDER_FRAMES = 12

private val DENIED_MESSAGES = listOf("Permission Denial", "no access to", "permission denied")

private val PROVIDER_CLASSES = listOf(
    "android.database.DatabaseUtils",
    "android.content.ContentResolver",
    "android.content.ContentProviderProxy",
    "android.content.ContentProviderClient",
)
