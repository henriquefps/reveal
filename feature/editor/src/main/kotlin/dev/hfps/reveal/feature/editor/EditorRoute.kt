package dev.hfps.reveal.feature.editor

import kotlinx.serialization.Serializable

/**
 * Type-safe navigation route of the editor: the photo to develop as a content (or file) URI string. The full hand-off
 * contract (explicit action, ClipData, RAW+JPEG pairs) is M1; in M0 one URI from the picker or another app.
 */
@Serializable
data class EditorRoute(val uri: String)
