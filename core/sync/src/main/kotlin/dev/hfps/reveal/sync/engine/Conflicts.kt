package dev.hfps.reveal.sync.engine

/**
 * One side of a sync conflict as the chooser shows it (ADR 0018 amendment FIX/W): the version, who changed it last
 * ([device]: "" = this device, null = not known) and when ([editedAt], Unix ms, null = not known), and the recipe's
 * `updated_at` when it is edited ([editTime], the thumbnail's key; null = unedited).
 */
data class ConflictSide(
    val versionId: Long,
    val device: String?,
    val editedAt: Long?,
    val editTime: Long?,
    val copy: Boolean,
)

/** A photo's main version and one conflict copy of it. */
data class ConflictPair(val photoId: Long, val main: ConflictSide, val copy: ConflictSide)
