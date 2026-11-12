package dev.hfps.reveal.data.sync

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Versions whose edit another device changed, announced by sync after it applied them (ADR 0018 "Live updates"): an
 * open editor on one of them reloads its history. The library needs nothing: its grid observes the database.
 */
object RemoteEdits {
    private val changes = MutableSharedFlow<Long>(extraBufferCapacity = BUFFER)

    val versions: SharedFlow<Long> = changes.asSharedFlow()

    fun announce(versionIds: Collection<Long>) {
        versionIds.forEach { changes.tryEmit(it) }
    }

    private const val BUFFER = 256
}
