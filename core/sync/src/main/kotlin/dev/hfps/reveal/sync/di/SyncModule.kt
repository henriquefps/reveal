package dev.hfps.reveal.sync.di

import android.content.Context
import android.os.Build
import android.provider.Settings
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.di.DataScope
import dev.hfps.reveal.data.export.Authorship
import dev.hfps.reveal.data.export.ExportPreferences
import dev.hfps.reveal.data.library.DocumentTreeFactory
import dev.hfps.reveal.sync.SyncRepository
import dev.hfps.reveal.sync.account.KeystoreSecretStore
import dev.hfps.reveal.sync.account.SyncAccountStore
import dev.hfps.reveal.sync.api.SyncApi
import dev.hfps.reveal.sync.blobs.BlobUploader
import dev.hfps.reveal.sync.blobs.SmartPreviewMaker
import dev.hfps.reveal.sync.engine.AuthorshipData
import dev.hfps.reveal.sync.engine.SyncEngine
import dev.hfps.reveal.sync.engine.SyncStore
import dev.hfps.reveal.sync.engine.SyncedSetting
import dev.hfps.reveal.sync.files.ServerFiles
import dev.hfps.reveal.sync.work.SyncController
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement

@Module
@InstallIn(SingletonComponent::class)
object SyncModule {
    @Provides
    @Singleton
    fun provideAccounts(@ApplicationContext context: Context): SyncAccountStore =
        SyncAccountStore(context, KeystoreSecretStore(context))

    /** The author and copyright of exports follow the owner (ADR 0018 "Settings"). */
    @Provides
    @Singleton
    fun provideSettings(preferences: ExportPreferences): List<SyncedSetting> = listOf(
        object : SyncedSetting {
            override val key = "export.authorship"

            override fun read(): JsonElement = SyncApi.json.encodeToJsonElement(
                AuthorshipData.serializer(),
                preferences.authorship.value.let { AuthorshipData(it.author, it.copyright) },
            )

            override fun write(value: JsonElement) {
                val data = SyncApi.json.decodeFromJsonElement(AuthorshipData.serializer(), value)
                preferences.setAuthorship(Authorship(data.author, data.copyright))
            }
        },
    )

    @Provides
    @Singleton
    fun provideStore(database: RevealDatabase, settings: List<@JvmSuppressWildcards SyncedSetting>): SyncStore =
        SyncStore(database, settings)

    @Provides
    @Singleton
    fun provideEngine(database: RevealDatabase, store: SyncStore): SyncEngine = SyncEngine(database, store)

    @Provides
    @Singleton
    fun provideUploader(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        store: SyncStore,
        trees: DocumentTreeFactory,
    ): BlobUploader =
        BlobUploader(database, store, trees, SmartPreviewMaker(File(context.noBackupFilesDir, "sync-previews")))

    @Provides
    @Singleton
    fun provideServerFiles(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        accounts: SyncAccountStore,
    ): ServerFiles = ServerFiles(
        db = database,
        api = {
            val account = accounts.account.value
            val key = accounts.apiKey()
            if (account == null || key == null) null else SyncApi(account.url, key)
        },
        cacheDir = context.cacheDir,
    )

    @Provides
    @Singleton
    @Suppress("LongParameterList")
    fun provideRepository(
        @ApplicationContext context: Context,
        database: RevealDatabase,
        accounts: SyncAccountStore,
        store: SyncStore,
        engine: SyncEngine,
        uploader: BlobUploader,
        files: ServerFiles,
    ): SyncRepository {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull().orEmpty()
        val name = runCatching { Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return SyncRepository(
            db = database,
            accounts = accounts,
            store = store,
            engine = engine,
            uploader = uploader,
            files = files,
            clientInfo = SyncRepository.ClientInfo(name.take(MAX_NAME), "android", version.take(MAX_VERSION)),
        )
    }

    @Provides
    @Singleton
    fun provideController(
        @ApplicationContext context: Context,
        sync: SyncRepository,
        @DataScope scope: CoroutineScope,
    ): SyncController = SyncController(context, sync, scope)

    private const val MAX_NAME = 100
    private const val MAX_VERSION = 40
}
