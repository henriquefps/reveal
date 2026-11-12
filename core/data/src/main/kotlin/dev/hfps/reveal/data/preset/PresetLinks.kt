package dev.hfps.reveal.data.preset

import java.net.URI

/**
 * Finds the link of a preset or pack in what the user pasted or shared (docs/adr/0019 "Import from a link"): the first
 * `http(s)://` token of the text. Only `https` is accepted; an `http` link is recognised so the user is told why it
 * is refused. Pure (no Android), JVM-tested.
 */
object PresetLinks {
    /** Characters in a link (a pack's own `link` is at most 512; a download URL may carry a signed query). */
    const val MAX_LENGTH: Int = 2048

    private val TOKEN = Regex("""(?i)\bhttps?://[^\s<>"'`]+""")

    /** What the text holds. */
    sealed interface Parsed {
        /** An `https` link to [url] on [host] (shown to the user before anything is downloaded). */
        data class Link(val url: String, val host: String) : Parsed

        /** An `http` (cleartext) link: refused. */
        data object NotHttps : Parsed

        /** No usable link. */
        data object None : Parsed
    }

    fun parse(text: String?): Parsed {
        val token = text?.let { TOKEN.find(it)?.value }?.trimEnd('.', ',', ';', ')', ']', '}', '!', '?')
        val uri = token?.takeIf { it.length <= MAX_LENGTH }?.let { runCatching { URI(it) }.getOrNull() }
        val host = uri?.host?.takeIf { it.isNotBlank() && uri.rawUserInfo == null }
        return when {
            uri == null || host == null -> Parsed.None
            uri.scheme.equals("https", ignoreCase = true) -> Parsed.Link(uri.toASCIIString(), host.lowercase())
            else -> Parsed.NotHttps
        }
    }
}
