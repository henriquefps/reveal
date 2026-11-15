package dev.hfps.reveal.sync.account

import java.net.URI
import java.util.Locale

/**
 * Where this installation's device key may go without asking (ADR 0018 "W5 (f)"): pairing again with the same server
 * (`server=` matches) at a new address reuses the key only when the address is `https`, or one that only a private
 * network reaches: loopback, Tailscale (`100.64.0.0/10`, `*.ts.net`), a private LAN (`10/8`, `172.16/12`,
 * `192.168/16`, `fd00::/8`) or link-local (`169.254/16`, `fe80::/10`). Anything else (a public `http` address, a plain
 * host name) asks the user first. Pure: never resolves a name.
 */
object ServerAddresses {
    private const val OCTETS = 4
    private const val OCTET_MAX = 255
    private const val LOOPBACK = 127
    private const val PRIVATE_10 = 10
    private const val PRIVATE_172 = 172
    private val PRIVATE_172_B = 16..31
    private const val PRIVATE_192 = 192
    private const val PRIVATE_192_B = 168
    private const val LINK_LOCAL_A = 169
    private const val LINK_LOCAL_B = 254
    private const val TAILSCALE_A = 100
    private val TAILSCALE_B = 64..127
    private const val MAX_OCTET_DIGITS = 3
    private const val LINK_LOCAL_V6_MASK = 0xffc0
    private const val LINK_LOCAL_V6 = 0xfe80
    private const val ULA_V6 = 0xfd
    private const val BYTE_SHIFT = 8
    private const val HEX = 16
    private const val HEXTET_MAX = 0xffff
    private const val V6_CHARS = "0123456789abcdef:."

    /** The host of [url] as shown to the user (IPv6 without brackets), or null when it has none. */
    fun host(url: String): String? =
        runCatching { URI(url).host }.getOrNull()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }

    /** True when the key may be sent to [url] without asking. */
    fun keySafe(url: String): Boolean {
        val scheme = runCatching { URI(url).scheme }.getOrNull()?.lowercase(Locale.ROOT)
        val host = host(url)
        return when {
            scheme == "https" -> true
            scheme != "http" || host == null -> false
            else -> isPrivate(host)
        }
    }

    /** True for a loopback, Tailscale, private LAN or link-local [host] (a literal address, or a `ts.net` name). */
    fun isPrivate(host: String): Boolean {
        val name = host.lowercase(Locale.ROOT).trimEnd('.')
        val v4 = ipv4(name)
        return when {
            name == "localhost" || name.endsWith(".ts.net") -> true
            v4 != null -> privateV4(v4)
            else -> privateV6(name)
        }
    }

    private fun privateV4(o: IntArray): Boolean {
        val (a, b) = o[0] to o[1]
        return when (a) {
            LOOPBACK, PRIVATE_10 -> true
            PRIVATE_172 -> b in PRIVATE_172_B
            PRIVATE_192 -> b == PRIVATE_192_B
            LINK_LOCAL_A -> b == LINK_LOCAL_B
            TAILSCALE_A -> b in TAILSCALE_B
            else -> false
        }
    }

    /** The four octets of a dotted-decimal IPv4 literal, or null. */
    private fun ipv4(host: String): IntArray? {
        val octets = host.split('.').map { part ->
            part.takeIf {
                it.length in 1..MAX_OCTET_DIGITS && it.all(Char::isDigit)
            }?.toInt()?.takeIf { it <= OCTET_MAX }
        }
        return octets.takeIf { it.size == OCTETS && null !in it }?.map { it!! }?.toIntArray()
    }

    /** Loopback, unique local (`fd00::/8`), link-local (`fe80::/10`) or IPv4-mapped private IPv6 literal. */
    @Suppress("ReturnCount")
    private fun privateV6(host: String): Boolean {
        if (':' !in host) return false
        val address = host.substringBefore('%')
        if (!address.all { it in V6_CHARS }) return false
        if (address == "::1") return true
        if (address.startsWith("::ffff:") && '.' in address) {
            return ipv4(address.removePrefix("::ffff:"))?.let(::privateV4) ?: false
        }
        if (address.startsWith("::")) return false
        val first = address.substringBefore(':').toIntOrNull(HEX)?.takeIf { it <= HEXTET_MAX } ?: return false
        return (first shr BYTE_SHIFT) == ULA_V6 || (first and LINK_LOCAL_V6_MASK) == LINK_LOCAL_V6
    }
}
