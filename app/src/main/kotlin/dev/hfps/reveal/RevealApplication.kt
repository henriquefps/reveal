package dev.hfps.reveal

import android.app.Application
import android.content.res.Configuration
import android.util.Log
import dagger.hilt.android.HiltAndroidApp
import dev.hfps.reveal.data.storage.HealFillCacheArea
import dev.hfps.reveal.feature.export.destination.ShareCache
import dev.hfps.reveal.feature.library.xmpexport.XmpExportWorker
import dev.hfps.reveal.feature.settings.appearance.AppLanguage
import dev.hfps.reveal.feature.settings.appearance.ThemeModeStore
import dev.hfps.reveal.ml.inpaint.InpaintRemoveMethod
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.sync.work.SyncController
import javax.inject.Inject
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class RevealApplication : Application() {
    /** Sync with the owner's server while paired (ADR 0018). */
    @Inject lateinit var sync: SyncController

    /** AI Remove's model (docs/adr/0024): registered with the process's Remove fills before anything renders. */
    @Inject lateinit var inpaint: InpaintService

    /** Remove's fills on disk (docs/adr/0023 "FIX/H"): the editor's fills reach the thumbnails and survive restarts. */
    @Inject lateinit var healFills: HealFillCacheArea

    /** The theme choice: told to the system at every start (Android 12+, its own copy of the app's night mode). */
    @Inject lateinit var themeModes: ThemeModeStore

    override fun onCreate() {
        // Android 12 and older: the language chosen in Settings, for strings resolved through the application context
        // (mask and preset names, notifications) too. Android 13+ applies it itself.
        AppLanguage.applyToApp(this)
        super.onCreate()
        themeModes.applyNightMode()
        InpaintRemoveMethod.registerAll(RemoveFills.shared, inpaint)
        RemoveFills.shared.store = healFills
        // Only the main process syncs: WorkManager is initialised there, and other processes (the debug documents
        // provider) must never run a second sync.
        if (getProcessName() == packageName) {
            sync.start()
            // Export XMP jobs whose work is gone (cancelled or failed while queued, a kill before the work was
            // enqueued): their files are deleted and their folder grants released.
            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                runCatching { XmpExportWorker.sweep(this@RevealApplication) }
                    .onFailure { Log.w(XmpExportWorker.TAG, "xmp export sweep failed", it) }
            }
        }
        // Exports shared to other apps whose share sheet never returned (the app was killed meanwhile): sweep them.
        thread(name = "reveal-share-sweep") { ShareCache.clear(this) }
    }

    /** A system configuration change resets the application's resources to the system language (Android 12-). */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        AppLanguage.applyToApp(this)
    }
}
