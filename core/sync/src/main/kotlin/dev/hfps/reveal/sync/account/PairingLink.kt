package dev.hfps.reveal.sync.account

import java.net.URI
import java.net.URLDecoder

/**
 * The pairing link of the server's QR code (ADR 0017 "Pairing"):
 * `revealsync://pair?v=1&url=<server URL, percent-encoded>&token=<token>&server=<serverId>`.
 */
data class PairingLink(val url: String, val token: String, val serverId: String?) {
    companion object {
        private const val SCHEME = "revealsync"
        private const val SUPPORTED_VERSION = "1"
        private const val MAX_TOKEN = 100

        /**
         * The link in [text] (a scanned QR or a pasted link, surrounding spaces ignored), or null when it is not one.
         */
        @Suppress("ReturnCount")
        fun parse(text: String): PairingLink? {
            val uri = runCatching { URI(text.trim()) }.getOrNull() ?: return null
            if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
            if (uri.host != "pair" && uri.authority != "pair") return null
            val query = uri.rawQuery ?: return null
            val params = query.split('&').mapNotNull { part ->
                val key = part.substringBefore('=', "")
                if (key.isEmpty()) null else key to decode(part.substringAfter('=', ""))
            }.toMap()
            if (params["v"] != SUPPORTED_VERSION) return null
            val url = params["url"]?.takeIf { it.isNotBlank() } ?: return null
            val token = params["token"]?.takeIf { it.isNotBlank() && it.length <= MAX_TOKEN } ?: return null
            val server = runCatching { URI(url) }.getOrNull() ?: return null
            if (server.scheme !in setOf("http", "https") || server.host.isNullOrBlank()) return null
            return PairingLink(url, token, params["server"]?.takeIf { it.isNotBlank() })
        }

        private fun decode(value: String): String = runCatching {
            URLDecoder.decode(value, "UTF-8")
        }.getOrDefault(value)
    }
}
