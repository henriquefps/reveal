package dev.hfps.image.source

import java.security.MessageDigest

/**
 * The content fingerprint of a file: what Reveal recognises a photo by, whatever its name or URI (ADR 0006
 * "Identity"), and the key a future sync server will use.
 *
 * `v1:` + lower-case hex SHA-256 over: the size as 8 bytes (big-endian), then the first [EDGE] bytes, then the last
 * [EDGE] bytes; a file of at most 2 x [EDGE] bytes is hashed whole after its size instead (no byte twice). It reads
 * at most 2 MiB, never the whole of a 25-60 MB RAW, and is unique in practice: a camera file's head holds the EXIF
 * capture time (with sub-seconds), the body serial and the shutter count, and its tail the end of the image data.
 *
 * **Known limit:** two files of the same size that differ only in the middle (more than [EDGE] bytes from either end)
 * share a fingerprint. Cameras do not produce such pairs; a tool that rewrites pixels in place without touching the
 * header or the tail could. The version prefix lets a later scheme coexist with stored `v1:` values.
 */
object FileFingerprint {
    const val VERSION = "v1:"
    const val EDGE = 1 shl 20
    private const val CHUNK = 64 * 1024
    private const val LONG_BYTES = 8
    private const val BYTE_MASK = 0xFF
    private const val BITS_PER_BYTE = 8

    /** Fingerprint of [source] (reads at most 2 x [EDGE] bytes; [source] stays open). */
    fun of(source: RandomAccessSource): String {
        val size = source.size
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(longBytes(size))
        val buffer = ByteArray(CHUNK)
        if (size <= 2L * EDGE) {
            digest.range(source, 0, size, buffer)
        } else {
            digest.range(source, 0, EDGE.toLong(), buffer)
            digest.range(source, size - EDGE, EDGE.toLong(), buffer)
        }
        return VERSION + digest.digest().joinToString("") { "%02x".format(it.toInt() and BYTE_MASK) }
    }

    /** Fingerprint of [bytes] (tests, small in-memory files). */
    fun of(bytes: ByteArray): String = of(ByteArraySource(bytes))

    private fun MessageDigest.range(source: RandomAccessSource, start: Long, length: Long, buffer: ByteArray) {
        var position = start
        val end = start + length
        while (position < end) {
            val n = minOf(buffer.size.toLong(), end - position).toInt()
            source.readFully(position, buffer, 0, n)
            update(buffer, 0, n)
            position += n
        }
    }

    private fun longBytes(value: Long) = ByteArray(LONG_BYTES) { i ->
        (value ushr (BITS_PER_BYTE * (LONG_BYTES - 1 - i))).toByte()
    }
}
