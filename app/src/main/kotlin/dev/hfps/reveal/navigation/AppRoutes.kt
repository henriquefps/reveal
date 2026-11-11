package dev.hfps.reveal.navigation

import kotlinx.serialization.Serializable

/** Routes owned by `:app`. Feature routes live in their feature modules (`LibraryRoute`, `EditorRoute`). */
@Serializable
data object GalleryRoute

/** About: version, licence and third-party licences. */
@Serializable
data object AboutRoute
