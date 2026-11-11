package dev.hfps.reveal.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.library.LibraryRepository

/**
 * Debug builds only: the app's singletons for instrumented tests (`EntryPointAccessors.fromApplication`), which add
 * the synthetic SAF folder through the same repository call the folder picker's result goes to.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface DebugAccess {
    fun library(): LibraryRepository

    fun edits(): EditRepository

    fun handoffs(): HandoffRepository
}
