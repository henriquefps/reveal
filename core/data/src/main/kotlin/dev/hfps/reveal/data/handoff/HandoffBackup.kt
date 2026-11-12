package dev.hfps.reveal.data.handoff

import androidx.room.withTransaction
import dev.hfps.reveal.data.db.MetaEntity
import dev.hfps.reveal.data.db.RevealDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * What the originals backup sends for "Photos opened from other apps" (ADR 0018 amendment FIX/W): only edited photos
 * (the default, like a new folder) or everything the server does not have yet. The choice lives in `meta` (it exists
 * before the hand-off source does) and in that source's `sources.backup`, which the backup queries read.
 */
object HandoffBackup {
    const val KEY = "sync.handoffBackup"

    /** True for "Everything". */
    suspend fun everything(db: RevealDatabase): Boolean = db.metaDao().get(KEY) == ON

    fun observe(db: RevealDatabase): Flow<Boolean> = db.metaDao().observe(KEY).map { it == ON }

    /** Sets the mode; originals already uploaded stay on the server (deleting from it is a separate, manual act). */
    suspend fun set(db: RevealDatabase, everything: Boolean) = db.withTransaction {
        db.metaDao().put(MetaEntity(KEY, if (everything) ON else OFF))
        db.sourceDao().handoff()?.let { db.sourceDao().setBackup(it.id, everything) }
    }

    private const val ON = "1"
    private const val OFF = "0"
}
