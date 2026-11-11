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
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.data.library.LibraryRepository
import dev.hfps.reveal.data.library.SidecarSync
import dev.hfps.reveal.data.storage.SafDocumentTree
import dev.hfps.reveal.data.storage.StorageVolumes
import dev.hfps.reveal.data.thumb.EditedThumbnails
import dev.hfps.reveal.data.thumb.GpuEditedThumbnails
import dev.hfps.reveal.data.thumb.PhotoImages
import dev.hfps.reveal.data.thumb.ThumbnailRepository
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
        Room.databaseBuilder(context, RevealDatabase::class.java, RevealDatabase.NAME).build()

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
        SafDocumentTree(context.contentResolver, source.treeUri.toUri(), source.rootDocumentId)
    }

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
    ): LibraryRepository = LibraryRepository(context.contentResolver, database, volumes, trees, sidecars, scope)

    @Provides
    @Singleton
    fun provideEdits(database: RevealDatabase): EditRepository = EditRepository(database)

    @Provides
    @Singleton
    fun provideEditedThumbnails(@DataScope scope: CoroutineScope): EditedThumbnails = GpuEditedThumbnails(scope)

    @Provides
    @Singleton
    fun provideThumbnails(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        trees: DocumentTreeFactory,
        edits: EditRepository,
        edited: EditedThumbnails,
    ): ThumbnailRepository = ThumbnailRepository(
        db = database,
        trees = trees,
        edits = edits,
        edited = edited,
        dir = File(context.cacheDir, "thumbs"),
    )

    @Provides
    @Singleton
    fun providePhotoImages(
        database: RevealDatabase,
        trees: DocumentTreeFactory,
        edits: EditRepository,
        edited: EditedThumbnails,
    ): PhotoImages = PhotoImages(database, trees, edits, edited)
}
