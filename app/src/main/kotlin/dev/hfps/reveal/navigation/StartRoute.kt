package dev.hfps.reveal.navigation

import kotlinx.serialization.Serializable

/** Routes owned by `:app`. Feature routes live in their feature modules (for example `EditorRoute`). */
@Serializable
data object StartRoute

/** The design-system gallery, reachable from the start screen's menu. */
@Serializable
data object GalleryRoute

/** About: version, licence and third-party licences. */
@Serializable
data object AboutRoute
