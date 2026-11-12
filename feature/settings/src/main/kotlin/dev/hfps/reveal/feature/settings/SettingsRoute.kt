package dev.hfps.reveal.feature.settings

import kotlinx.serialization.Serializable

/** Type-safe navigation route of the settings feature; the screen and its nav graph builder land next to it. */
@Serializable
data object SettingsRoute

/** The Sync screen inside Settings (ADR 0018). */
@Serializable
data object SyncSettingsRoute

/** The Storage screen inside Settings (ADR 0018 amendment FIX/W). */
@Serializable
data object StorageRoute
