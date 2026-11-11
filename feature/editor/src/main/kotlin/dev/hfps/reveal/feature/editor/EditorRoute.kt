package dev.hfps.reveal.feature.editor

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation route of the editor. From the library: [versionId] (a photo's version; the editor opens the
 * RAW of a RAW+JPEG pair and its recipe). From another app or "Open a photo": [uri], a content (or file) URI string,
 * not in the library. Exactly one of the two is set.
 */
@Serializable
data class EditorRoute(val uri: String? = null, val versionId: Long? = null) {
    init {
        require((uri == null) != (versionId == null)) { "EditorRoute needs a uri or a versionId" }
    }
}
