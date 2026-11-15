package dev.hfps.reveal.debug

import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.data.editor.EditorLayoutPreferences
import dev.hfps.reveal.data.export.ExportPresetRepository
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.HandoffPreviewer
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.LocalOriginals
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.XmpExport
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.data.xmp.XmpPreferences
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.thumbs.LibraryThumbs

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

    fun editorLayout(): EditorLayoutPreferences

    fun sync(): SyncRepository

    fun database(): RevealDatabase

    fun presets(): PresetRepository

    fun thumbnails(): ThumbnailRepository

    fun photoImages(): PhotoImages

    fun handoffPreviewer(): HandoffPreviewer

    fun aiMasks(): AiMasks

    fun versions(): VersionRepository

    fun exportPresets(): ExportPresetRepository

    fun xmpExport(): XmpExport

    fun galleryAccess(): GalleryAccess

    fun localOriginals(): LocalOriginals

    fun xmpPreferences(): XmpPreferences

    fun libraryThumbs(): LibraryThumbs
}
