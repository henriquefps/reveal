package dev.hfps.reveal.feature.settings

import kotlinx.serialization.Serializable

/** Settings: the hub of short entries, each opening its own screen (see [SettingsEntry]). */
@Serializable
data object SettingsRoute

/** "Photo server": pairing, status, Sync now, This device, Unpair (ADR 0018). */
@Serializable
data object PhotoServerRoute

/** "Backup and sync": what goes to the server, and when (ADR 0018). */
@Serializable
data object BackupSyncRoute

/** "Folders and photo access": what each folder backs up, gallery access (ADR 0011, ADR 0018). */
@Serializable
data object FoldersAccessRoute

/** "Cache and storage" (ADR 0018 amendment FIX/W). */
@Serializable
data object StorageRoute

/** "Appearance and language": theme and app language. */
@Serializable
data object AppearanceRoute

/** "Editing and XMP": edits from XMP sidecars (ADR 0026), author and copyright in exports (ADR 0009). */
@Serializable
data object EditingRoute

/** "This device" inside Photo server (Hoard ADR 0017, Reveal ADR 0027). */
@Serializable
data object ThisDeviceRoute

/**
 * The places outside Settings that open one of its screens directly. [route] is where each lands; a new door names
 * the screen here, never a route of its own.
 */
enum class SettingsLink {
    /** The library's sync status badge. */
    SYNC_STATUS,

    /** "Pair" in the hand-off sheet for server photos (ADR 0011 "Amendment W3/H"). */
    PAIR,

    /** Anything about the cache or storage use. */
    STORAGE,

    /** Anything about the gallery permission (ADR 0011 "Gallery access"). */
    GALLERY_ACCESS,
    ;

    val route: Any
        get() = when (this) {
            SYNC_STATUS, PAIR -> PhotoServerRoute
            STORAGE -> StorageRoute
            GALLERY_ACCESS -> FoldersAccessRoute
        }
}
