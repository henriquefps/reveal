package dev.hfps.reveal.navigation

import kotlinx.serialization.Serializable

/**
 * About: version, licence and third-party licences; the only route `:app` owns (feature routes live in their modules:
 * `LibraryRoute`, `EditorRoute`).
 */
@Serializable
data object AboutRoute
