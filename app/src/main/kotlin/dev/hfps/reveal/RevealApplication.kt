package dev.hfps.reveal

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.hfps.reveal.feature.export.destination.ShareCache
import kotlin.concurrent.thread

@HiltAndroidApp
class RevealApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Exports shared to other apps whose share sheet never returned (the app was killed meanwhile): sweep them.
        thread(name = "reveal-share-sweep") { ShareCache.clear(this) }
    }
}
