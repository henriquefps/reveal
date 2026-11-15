package dev.hfps.reveal.data.di

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
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
import dev.hfps.reveal.data.edit.VersionRepository
import dev.hfps.reveal.data.editor.EditorLayoutPreferences
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.export.ExportPresetRepository
import dev.hfps.reveal.data.handoff.GalleryAccess
import dev.hfps.reveal.data.handoff.GalleryAccessState
import dev.hfps.reveal.data.handoff.HandoffBatches
import dev.hfps.reveal.data.handoff.HandoffIngests
import dev.hfps.reveal.data.handoff.HandoffPreviewer
import dev.hfps.reveal.data.handoff.HandoffRepository
import dev.hfps.reveal.data.handoff.HandoffTree
import dev.hfps.reveal.data.handoff.LocalOriginals
import dev.hfps.reveal.data.handoff.LocalPreviews
import dev.hfps.reveal.data.handoff.ServerItems
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.library.XmpExport
import dev.hfps.reveal.data.library.XmpGrants
import dev.hfps.reveal.data.mask.AiMasks
import dev.hfps.reveal.data.model.SourceKind
import dev.hfps.reveal.data.preset.AppBuiltInPresets
import dev.hfps.reveal.data.preset.BuiltInPresetSource
import dev.hfps.reveal.data.preset.PresetDownloader
import dev.hfps.reveal.data.preset.PresetRepository
import dev.hfps.reveal.data.storage.AppStorage
import dev.hfps.reveal.data.storage.CacheBudget
import dev.hfps.reveal.data.storage.FillCacheArea
import dev.hfps.reveal.data.storage.HealFillCacheArea
import dev.hfps.reveal.data.storage.LibraryThumbsPreferences
import dev.hfps.reveal.data.storage.LocalCopies
import dev.hfps.reveal.data.storage.MaskCacheArea
import dev.hfps.reveal.data.storage.SafDocumentTree
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.storage.readGrants
import dev.hfps.reveal.data.sync.ServerTree
import dev.hfps.reveal.data.thumb.EditedThumbnails
import dev.hfps.reveal.data.thumb.GpuEditedThumbnails
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.ServerThumbs
import dev.hfps.reveal.data.thumb.ThumbnailRepository
import dev.hfps.reveal.data.xmp.XmpPreferences
import dev.hfps.reveal.ml.MaskModels
import dev.hfps.reveal.ml.inpaint.FillDiskCache
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.ml.service.EngineLoader
import dev.hfps.reveal.ml.service.MaskDiskCache
import dev.hfps.reveal.ml.service.MaskService
import dev.hfps.reveal.pipeline.preset.SmartCorrection
import java.io.File
import java.util.Optional
import javax.inject.Provider
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

    /** Originals of hand-offs that only a temporary grant covers (ADR 0011 "Amendment W4"): kept, never evicted. */
    @Provides
    @Singleton
    fun provideLocalOriginals(@ApplicationContext context: Context): LocalOriginals {
        val storage = context.getSystemService(StorageManager::class.java)
        return LocalOriginals(
            File(context.noBackupFilesDir, LocalOriginals.DIR_NAME),
            // Unknown (the volume cannot say): the copy is tried, and a write that fails leaves no partial file.
            usableSpace = { dir ->
                runCatching { storage.getAllocatableBytes(storage.getUuidForPath(dir)) }.getOrDefault(Long.MAX_VALUE)
            },
        )
    }

    @Provides
    @Singleton
    fun provideHandoffPreviewer(
        @ApplicationContext context: Context,
        previews: LocalPreviews,
        @DataScope scope: CoroutineScope,
    ): HandoffPreviewer = HandoffPreviewer(HandoffTree(context.contentResolver), previews, scope)

    /** Hand-offs that arrive as several intents (ADR 0011 "Batches over 500"). */
    @Provides
    @Singleton
    fun provideHandoffBatches(database: RevealDatabase): HandoffBatches = HandoffBatches(database)

    @Provides
    @Singleton
    fun provideHandoffs(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        previewer: HandoffPreviewer,
        server: ServerItems,
        originals: LocalOriginals,
        gallery: GalleryAccess,
        @DataScope scope: CoroutineScope,
    ): HandoffRepository = HandoffRepository(
        context.contentResolver,
        database,
        LibraryIndexer(database),
        previewer = previewer,
        originals = originals,
        server = server,
        background = scope,
        mediaReadable = {
            gallery.refresh()
            gallery.state.value == GalleryAccessState.FULL
        },
    )

    /** Hand-offs received in the app's scope, re-attached by a recreated activity (ADR 0011 "Amendment W4"). */
    @Provides
    @Singleton
    fun provideHandoffIngests(@DataScope scope: CoroutineScope): HandoffIngests = HandoffIngests(scope)

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
        return SidecarSync(
            database,
            trees,
            "Reveal $version".trim(),
            // Only Export XMP calls the writer (ADR 0026): it always writes the edit with the culling fields.
            writeEdits = { true },
            device = Build.MODEL.orEmpty(),
        )
    }

    /** Export XMP: the one explicit writer of sidecars (ADR 0026). */
    @Provides
    @Singleton
    fun provideXmpExport(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        sidecars: SidecarSync,
    ): XmpExport = XmpExport(database, sidecars) { SafDocumentTree(context.contentResolver, it.toUri()) }

    /** Export XMP's folder grants (ADR 0026 "Export XMP"). */
    @Provides
    @Singleton
    fun provideXmpGrants(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        exportPreferences: ExportPreferences,
    ): XmpGrants = XmpGrants(context.contentResolver, database) { exportPreferences.folderUri }

    /** The XMP switch of Settings (ADR 0026). */
    @Provides
    @Singleton
    fun provideXmpPreferences(@ApplicationContext context: Context): XmpPreferences = XmpPreferences(context)

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideLibrary(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        volumes: StorageVolumes,
        trees: DocumentTreeFactory,
        sidecars: SidecarSync,
        @DataScope scope: CoroutineScope,
        previews: LocalPreviews,
        xmp: XmpPreferences,
        originals: LocalOriginals,
    ): LibraryRepository = LibraryRepository(
        context.contentResolver,
        database,
        volumes,
        trees,
        sidecars,
        scope,
        serverAuthority = ServerTree.authority(context.packageName),
        previews = previews,
        readEdits = { xmp.readEdits.value },
        grants = readGrants(context),
        // The debug build's synthetic documents provider (app/src/debug) needs no grant; absent in release.
        grantFreeAuthorities = setOf("${context.packageName}.synthetic"),
        originals = originals,
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

    /** Virtual copies and snapshots (docs/adr/0025). */
    @Provides
    @Singleton
    fun provideVersions(database: RevealDatabase, edits: EditRepository): VersionRepository =
        VersionRepository(database, edits)

    /** The user's export presets (docs/adr/0025). */
    @Provides
    @Singleton
    fun provideExportPresets(database: RevealDatabase): ExportPresetRepository = ExportPresetRepository(database)

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
    fun provideEditedThumbnails(@DataScope scope: CoroutineScope, healFills: HealFillCacheArea): EditedThumbnails =
        GpuEditedThumbnails(scope, healFills)

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
        serverThumbs: Optional<Provider<ServerThumbs>>,
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
        serverThumbs = { serverThumbs.orElse(null)?.get() },
    ).also(budget::register)

    /** Settings -> Storage's maximum cache size and the LRU across caches (ADR 0018 FIX/W). */
    @Provides
    @Singleton
    fun provideCacheBudget(@ApplicationContext context: Context, @DataScope scope: CoroutineScope): CacheBudget =
        CacheBudget(context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE), scope)

    /** Settings -> Storage -> "Keep thumbnails of the whole library" (ADR 0018 "W3/C"). */
    @Provides
    @Singleton
    fun provideLibraryThumbsPreferences(@ApplicationContext context: Context): LibraryThumbsPreferences =
        LibraryThumbsPreferences(context.getSharedPreferences(STORAGE_PREFS, Context.MODE_PRIVATE))

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

    /** AI Remove's fills on disk (docs/adr/0024), counted with the AI masks by the budget. */
    @Provides
    @Singleton
    fun provideFillCacheArea(@ApplicationContext context: Context, budget: CacheBudget): FillCacheArea = FillCacheArea(
        FillDiskCache(File(context.cacheDir, FillCacheArea.DIR), cap = budget::cap, onWrite = budget::requestTrim),
        programs = File(context.codeCacheDir, FillCacheArea.GPU_PROGRAMS),
    ).also(budget::register)

    /**
     * Remove's fills on disk (docs/adr/0023 "FIX/H"): written by the process's fills ([RemoveFills.shared], set by the
     * app at start), read by the thumbnails; counted with the AI masks by the budget.
     */
    @Provides
    @Singleton
    fun provideHealFillCacheArea(@ApplicationContext context: Context, budget: CacheBudget): HealFillCacheArea =
        HealFillCacheArea(
            File(context.cacheDir, HealFillCacheArea.DIR),
            cap = budget::cap,
            onWrite = budget::requestTrim,
        )
            .also(budget::register)

    /**
     * AI Remove's inpainting (docs/adr/0024): the bundled model from the APK's assets (GPU, its compiled programs kept
     * in the code cache; else the CPU), released after each fill, loaded only when the system reports enough free
     * memory (else Remove falls back to the patch fill), fills cached on disk.
     */
    @Provides
    @Singleton
    fun provideInpaintService(
        @ApplicationContext context: Context,
        fills: FillCacheArea,
        budget: CacheBudget,
    ): InpaintService = InpaintService(
        loader = InpaintService.assets(
            context.assets,
            File(context.codeCacheDir, FillCacheArea.GPU_PROGRAMS).path,
            onLoaded = budget::requestTrim,
        ),
        cache = fills.cache,
        hasMemoryFor = { model ->
            val activity = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
            val need = runCatching { context.assets.openFd(MaskModels.assetPath(model)).use { it.length } }
                .getOrDefault(0L) * InpaintService.MEMORY_PER_FILE_BYTE
            !info.lowMemory && info.availMem - info.threshold > need
        },
    )

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideAppStorage(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        budget: CacheBudget,
        previews: LocalPreviews,
        originals: LocalOriginals,
        // The areas register when they are made: make sure the thumbnails, the masks and the fills are.
        @Suppress("UNUSED_PARAMETER") thumbnails: ThumbnailRepository,
        @Suppress("UNUSED_PARAMETER") masks: MaskCacheArea,
        @Suppress("UNUSED_PARAMETER") fills: FillCacheArea,
        @Suppress("UNUSED_PARAMETER") healFills: HealFillCacheArea,
    ): AppStorage = AppStorage(
        db = database,
        budget = budget,
        previews = previews,
        originals = originals,
        cacheDir = context.cacheDir,
        uploadsDir = File(context.noBackupFilesDir, "sync-previews"),
        database = {
            val main = context.getDatabasePath(RevealDatabase.NAME)
            listOf(main, File(main.path + "-wal"), File(main.path + "-shm"))
        },
    )

    private const val STORAGE_PREFS = "storage"

    /** Short-lived local copies of other apps' documents (`LocalCopies`), swept at start. */
    const val LOCAL_COPIES_DIR = "local-copies"

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
        copiesDir = File(context.cacheDir, LOCAL_COPIES_DIR).also(LocalCopies::sweepAfterFirstFrame),
    )
}

/** Bindings `:core:sync` may provide (ADR 0018 "W3/C"): absent in a build or test without sync. */
@dagger.Module
@dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
abstract class OptionalDataBindings {
    @dagger.BindsOptionalOf
    abstract fun serverThumbs(): ServerThumbs
}
