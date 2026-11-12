package dev.hfps.reveal.data.storage

import java.io.FileNotFoundException

/**
 * Whether [error] means the document cannot be read by Reveal any more (ADR 0011 "Unreadable hand-offs"): access
 * refused (a temporary grant that ended: `SecurityException`) or the document is gone (`FileNotFoundException`), also
 * when wrapped. Other failures (a damaged file, no memory) are not about access.
 */
fun isAccessError(error: Throwable): Boolean {
    var e: Throwable? = error
    var depth = 0
    while (e != null && depth++ < MAX_DEPTH) {
        if (e is SecurityException || e is FileNotFoundException) return true
        e = e.cause
    }
    return false
}

private const val MAX_DEPTH = 8
