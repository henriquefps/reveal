package dev.hfps.reveal.data.preset

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.core.content.IntentCompat
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetPacks

/**
 * Recognises a preset or pack handed to Reveal (docs/adr/0019 "Import and export"): "Open with Reveal" (`VIEW`) or a
 * share (`SEND`) of a `.revealpreset` / `.revealpack`, by MIME type or, since most providers do not know these
 * types, by the document's name. Anything else is left to the photo hand-off (ADR 0011).
 */
object PresetIntents {
    private val TYPES = setOf(PresetFiles.MIME, PresetPacks.MIME)
    private val EXTENSIONS = listOf(".${PresetFiles.EXTENSION}", ".${PresetPacks.EXTENSION}")

    /** The preset or pack document of [intent], or null when it carries none. */
    @Suppress("ReturnCount")
    fun presetFile(intent: Intent?, resolver: ContentResolver): Uri? {
        if (intent == null) return null
        val uri = when (intent.action) {
            Intent.ACTION_VIEW -> intent.data
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            else -> null
        } ?: return null
        val type = intent.type ?: runCatching { resolver.getType(uri) }.getOrNull()
        if (type in TYPES) return uri
        val name = displayName(resolver, uri) ?: uri.lastPathSegment.orEmpty()
        return uri.takeIf { EXTENSIONS.any { name.endsWith(it, ignoreCase = true) } }
    }

    /**
     * The link of a preset or pack handed to Reveal (docs/adr/0019 "Import from a link"): shared text (`SEND`
     * `text/plain`, e.g. a browser's Share of the page or link) or an `https` link opened with Reveal (`VIEW`). Null
     * when [intent] is not that kind of intent; [PresetLinks.Parsed.None] when it is but holds no link. Nothing is
     * downloaded here: the user confirms first.
     */
    fun presetLink(intent: Intent?): PresetLinks.Parsed? {
        if (intent == null) return null
        return when {
            intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true &&
                !intent.hasExtra(Intent.EXTRA_STREAM) ->
                PresetLinks.parse(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString())

            intent.action == Intent.ACTION_VIEW && intent.data?.scheme.equals("https", ignoreCase = true) ->
                PresetLinks.parse(intent.dataString)

            else -> null
        }
    }

    private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}
