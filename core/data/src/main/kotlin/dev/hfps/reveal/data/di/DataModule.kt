package dev.hfps.reveal.data.di

import android.content.Context
import androidx.core.net.toUri
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.db.MetaDao
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.edit.HistoryWriter
import dev.hfps.reveal.data.editor.EditorLayoutPreferences
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.HandoffPreviewer
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.HandoffTree
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.preset.AppBuiltInPresets
import dev.hfps.reveal.data.preset.BuiltInPresetSource
import dev.hfps.reveal.data.preset.PresetDownloader
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.storage.AppStorage
import dev.hfps.reveal.data.storage.CacheBudget
import dev.hfps.reveal.data.storage.MaskCacheArea
import dev.hfps.reveal.data.storage.SafDocumentTree
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.data.thumb.EditedThumbnails
import dev.hfps.reveal.data.thumb.GpuEditedThumbnails
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.ml.service.EngineLoader
import dev.hfps.reveal.ml.service.MaskDiskCache
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.preset.SmartCorrection
import java.io.File
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** The application-lifetime scope of background data work (indexing, sidecar writes, thumbnail renderer). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DataScope

@Module
@InstallIn(SingletonComponent::class)
object DataModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): RevealDatabase =
        RevealDatabase.configure(Room.databaseBuilder(context, RevealDatabase::class.java, RevealDatabase.NAME))
            .build()

    @Provides
    fun provideMetaDao(database: RevealDatabase): MetaDao = database.metaDao()

    @Provides
    @Singleton
    @DataScope
    fun provideScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Provides
    @Singleton
    fun provideVolumes(@ApplicationContext context: Context): StorageVolumes = StorageVolumes(context)

    @Provides
    @Singleton
    fun provideTrees(@ApplicationContext context: Context): DocumentTreeFactory = DocumentTreeFactory { source ->
        when (source.kind) {
            SourceKind.HANDOFF -> HandoffTree(context.contentResolver)
            SourceKind.SERVER -> ServerTree(context.contentResolver, ServerTree.authority(context.packageName))
            SourceKind.FOLDER -> SafDocumentTree(context.contentResolver, source.treeUri.toUri(), source.rootDocumentId)
        }
    }

    /** The optional gallery permission (ADR 0011 "Gallery access"). */
    @Provides
    @Singleton
    fun provideGalleryAccess(@ApplicationContext context: Context): GalleryAccess = GalleryAccess(context)

    /** Local previews of hand-offs (ADR 0011 "Unreadable hand-offs"): app storage, never backed up. */
    @Provides
    @Singleton
    fun provideLocalPreviews(@ApplicationContext context: Context): LocalPreviews =
        LocalPreviews(File(context.noBackupFilesDir, "handoff-previews"))

    @Provides
    @Singleton
    fun provideHandoffPreviewer(
        @ApplicationContext context: Context,
        previews: LocalPreviews,
        @DataScope scope: CoroutineScope,
    ): HandoffPreviewer = HandoffPreviewer(HandoffTree(context.contentResolver), previews, scope)

    @Provides
    @Singleton
    fun provideHandoffs(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        sidecars: SidecarSync,
        previewer: HandoffPreviewer,
    ): HandoffRepository = HandoffRepository(
        context.contentResolver,
        database,
        LibraryIndexer(database),
        sidecars = sidecars,
        previewer = previewer,
    )

    @Provides
    @Singleton
    fun provideSidecars(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        trees: DocumentTreeFactory,
    ): SidecarSync {
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty().substringBefore('-')
        return SidecarSync(database, trees, "Reveal $version".trim())
    }

    @Provides
    @Singleton
    fun provideLibrary(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        volumes: StorageVolumes,
        trees: DocumentTreeFactory,
        sidecars: SidecarSync,
        @DataScope scope: CoroutineScope,
        previews: LocalPreviews,
    ): LibraryRepository = LibraryRepository(
        context.contentResolver,
        database,
        volumes,
        trees,
        sidecars,
        scope,
        serverAuthority = ServerTree.authority(context.packageName),
        previews = previews,
    )

    @Provides
    @Singleton
    fun provideExportPreferences(@ApplicationContext context: Context): ExportPreferences = ExportPreferences(context)

    @Provides
    @Singleton
    fun provideEditorLayoutPreferences(@ApplicationContext context: Context): EditorLayoutPreferences =
        EditorLayoutPreferences(context)

    @Provides
    @Singleton
    fun provideEdits(database: RevealDatabase): EditRepository = EditRepository(database)

    /**
     * The fallback smart correction where no photo is known (docs/adr/0020): the editor and the batch make a
     * per-photo `SmartCorrector` from each photo's features instead, so nothing app-wide can correct a photo.
     */
    @Provides
    @Singleton
    fun provideSmartCorrection(): SmartCorrection = SmartCorrection.NONE

    /** Downloads a preset or pack the user asked for by link (docs/adr/0019 "Import from a link"). */
    @Provides
    @Singleton
    fun providePresetDownloader(): PresetDownloader = PresetDownloader()

    /** The app's own presets: the built-in looks and smart presets (docs/adr/0020). */
    @Provides
    @Singleton
    fun provideBuiltInPresets(@ApplicationContext context: Context): BuiltInPresetSource = AppBuiltInPresets(context)

    @Provides
    @Singleton
    fun providePresets(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        builtIns: BuiltInPresetSource,
    ): PresetRepository = PresetRepository(database, File(context.filesDir, "preset-covers"), builtIns)

    @Provides
    @Singleton
    fun provideHistoryWriter(edits: EditRepository, @DataScope scope: CoroutineScope): HistoryWriter =
        HistoryWriter(edits, scope)

    @Provides
    @Singleton
    fun provideEditedThumbnails(@DataScope scope: CoroutineScope): EditedThumbnails = GpuEditedThumbnails(scope)

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideThumbnails(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        trees: DocumentTreeFactory,
        edits: EditRepository,
        edited: EditedThumbnails,
        previews: LocalPreviews,
        budget: CacheBudget,
        aiMasks: AiMasks,
    ): ThumbnailRepository = ThumbnailRepository(
        db = database,
        trees = trees,
        edits = edits,
        edited = edited,
        dir = File(context.cacheDir, "thumbs"),
        maxBytes = budget::cap,
        previews = previews,
        resolver = context.contentResolver,
        serverAuthority = ServerTree.authority(context.packageName),
        onTrimmed = budget::requestTrim,
        aiMasks = aiMasks,
    ).also(budget::register)

    /** Settings -> Storage's maximum cache size and the LRU across caches (ADR 0018 FIX/W). */
    @Provides
    @Singleton
    fun provideCacheBudget(@ApplicationContext context: Context, @DataScope scope: CoroutineScope): CacheBudget =
        CacheBudget(context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE), scope)

    /** The AI masks' disk cache (docs/adr/0021 "M4/C"), one of the budget's areas. */
    @Provides
    @Singleton
    fun provideMaskCacheArea(@ApplicationContext context: Context, budget: CacheBudget): MaskCacheArea = MaskCacheArea(
        MaskDiskCache(File(context.cacheDir, MaskCacheArea.DIR), cap = budget::cap, onWrite = budget::requestTrim),
    ).also(budget::register)

    /** Every AI mask kind (docs/adr/0021 "M4/C"): the bundled models from the APK's assets, cached on disk. */
    @Provides
    @Singleton
    fun provideMaskService(@ApplicationContext context: Context, masks: MaskCacheArea): MaskService =
        MaskService(EngineLoader.assets(context.assets), masks.cache)

    @Provides
    @Singleton
    fun provideAppStorage(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        budget: CacheBudget,
        previews: LocalPreviews,
        // The areas register when they are made: make sure the thumbnails and the masks are.
        @Suppress("UNUSED_PARAMETER") thumbnails: ThumbnailRepository,
        @Suppress("UNUSED_PARAMETER") masks: MaskCacheArea,
    ): AppStorage = AppStorage(
        db = database,
        budget = budget,
        previews = previews,
        cacheDir = context.cacheDir,
        uploadsDir = File(context.noBackupFilesDir, "sync-previews"),
        database = {
            val main = context.getDatabasePath(RevealDatabase.NAME)
            listOf(main, File(main.path + "-wal"), File(main.path + "-shm"))
        },
    )

    private const val STORAGE_PREFS = "storage"

    @Provides
    @Singleton
    fun providePhotoImages(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        trees: DocumentTreeFactory,
        edits: EditRepository,
        edited: EditedThumbnails,
        previews: LocalPreviews,
    ): PhotoImages = PhotoImages(
        database,
        trees,
        edits,
        edited,
        previews,
        resolver = context.contentResolver,
        serverAuthority = ServerTree.authority(context.packageName),
    )
}
