package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.ByteArraySource
import dev.hfps.image.source.CountingSource
import dev.hfps.image.source.FileFingerprint
import java.security.MessageDigest
import kotlin.random.Random
import org.junit.Test

class FileFingerprintTest {
    private fun bytes(size: Int, seed: Int = 1) = Random(seed).nextBytes(size)

    private fun sha256Hex(vararg parts: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach(digest::update)
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sizeBytes(size: Long) = java.nio.ByteBuffer.allocate(8).putLong(size).array()

    @Test
    fun `a small file is hashed whole after its size, with the version prefix`() {
        val data = bytes(1000)
        val print = FileFingerprint.of(data)
        assertThat(print).startsWith("v1:")
        assertThat(print).hasLength(3 + 64)
        assertThat(print).isEqualTo("v1:" + sha256Hex(sizeBytes(1000), data))
    }

    @Test
    fun `an empty file has a fingerprint`() {
        assertThat(FileFingerprint.of(ByteArray(0))).isEqualTo("v1:" + sha256Hex(sizeBytes(0)))
    }

    @Test
    fun `exactly two edges is still hashed whole`() {
        val data = bytes(2 * FileFingerprint.EDGE)
        assertThat(FileFingerprint.of(data)).isEqualTo("v1:" + sha256Hex(sizeBytes(data.size.toLong()), data))
    }

    @Test
    fun `a large file hashes its size, head and tail and reads only those`() {
        val edge = FileFingerprint.EDGE
        val data = bytes(25 * edge + 123)
        val source = CountingSource(ByteArraySource(data))
        val print = FileFingerprint.of(source)
        val head = data.copyOfRange(0, edge)
        val tail = data.copyOfRange(data.size - edge, data.size)
        assertThat(print).isEqualTo("v1:" + sha256Hex(sizeBytes(data.size.toLong()), head, tail))
        assertThat(source.bytesRead).isEqualTo(2L * edge)
    }

    @Test
    fun `one changed byte in the head or the tail, or another size, changes the fingerprint`() {
        val data = bytes(5 * FileFingerprint.EDGE)
        val base = FileFingerprint.of(data)
        val head = data.copyOf().also { it[10] = (it[10] + 1).toByte() }
        val tail = data.copyOf().also { it[it.size - 10] = (it[it.size - 10] + 1).toByte() }
        val longer = data.copyOf(data.size + 1)
        assertThat(FileFingerprint.of(head)).isNotEqualTo(base)
        assertThat(FileFingerprint.of(tail)).isNotEqualTo(base)
        assertThat(FileFingerprint.of(longer)).isNotEqualTo(base)
        assertThat(FileFingerprint.of(data.copyOf())).isEqualTo(base)
    }

    @Test
    fun `different small files with the same name and size differ`() {
        assertThat(FileFingerprint.of(bytes(4096, seed = 1))).isNotEqualTo(FileFingerprint.of(bytes(4096, seed = 2)))
    }

    /** Known limit (documented in [FileFingerprint]): a same-size difference only in the middle is not seen. */
    @Test
    fun `known limit - a same-size difference only in the middle keeps the fingerprint`() {
        val data = bytes(3 * FileFingerprint.EDGE)
        val middle = data.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }
        assertThat(FileFingerprint.of(middle)).isEqualTo(FileFingerprint.of(data))
    }
}
