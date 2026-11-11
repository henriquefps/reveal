package dev.hfps.reveal.navigation

import android.content.Intent
import android.net.Uri
import androidx.core.content.IntentCompat

/**
 * The photo another app asks Reveal to open: `ACTION_VIEW` / `ACTION_EDIT` (the intent data) or `ACTION_SEND`
 * (`EXTRA_STREAM`, else the first ClipData item). The full hand-off contract (explicit action, several URIs, RAW+JPEG
 * pairs) is M1.
 */
object OpenIntents {
    private val schemes = setOf("content", "file")

    fun photoUri(intent: Intent?): Uri? {
        val uri = when (intent?.action) {
            Intent.ACTION_VIEW, Intent.ACTION_EDIT -> intent.data

            Intent.ACTION_SEND ->
                IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri

            else -> null
        }
        return uri?.takeIf { it.scheme in schemes }
    }
}
