package dev.hfps.reveal

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.hfps.reveal.data.library.EditSidecars
import dev.hfps.reveal.feature.export.destination.ShareCache
import dev.hfps.reveal.ml.inpaint.InpaintRemoveMethod
import dev.hfps.reveal.ml.inpaint.InpaintService
import dev.hfps.reveal.pipeline.heal.RemoveFills
import dev.hfps.reveal.sync.work.SyncController
import javax.inject.Inject
import kotlin.concurrent.thread

@HiltAndroidApp
class RevealApplication : Application() {
    /** Sync with the owner's server while paired (ADR 0018). */
    @Inject lateinit var sync: SyncController

    /** Edits written to XMP sidecars after they change (ADR 0026). */
    @Inject lateinit var editSidecars: EditSidecars

    /** AI Remove's model (docs/adr/0024): registered with the process's Remove fills before anything renders. */
    @Inject lateinit var inpaint: InpaintService

    override fun onCreate() {
        super.onCreate()
        InpaintRemoveMethod.registerAll(RemoveFills.shared, inpaint)
        // Only the main process syncs: WorkManager is initialised there, and other processes (the debug documents
        // provider) must never run a second sync.
        if (getProcessName() == packageName) {
            sync.start()
            editSidecars.start()
        }
        // Exports shared to other apps whose share sheet never returned (the app was killed meanwhile): sweep them.
        thread(name = "reveal-share-sweep") { ShareCache.clear(this) }
    }
}
