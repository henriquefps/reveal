package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import java.io.File
import org.junit.Test

/**
 * The `v1:` fingerprint vectors shared with the sync server (ADR 0017 "Tests"): the app and the server must agree
 * byte for byte, edges included (0 bytes, exactly 2 MiB, 2 MiB + 1). `fingerprint-vectors.txt` in the test resources
 * is a copy of Hoard's `server/testdata/fingerprint-vectors.txt`; the generator lives in the Hoard server's tests
 * (`REVEAL_WRITE_VECTORS=1 go test ./internal/fingerprint/` there), so change both copies together.
 */
class FileFingerprintVectorsTest {
    private fun vectorsFile(): File {
        val url = checkNotNull(javaClass.classLoader?.getResource("fingerprint-vectors.txt")) {
            "fingerprint-vectors.txt not found in the test resources"
        }
        return File(url.toURI())
    }

    /** Byte i = ((i * 2654435761 + seed * 97) >> 13) & 0xff, the generator the Go test uses. */
    private fun content(size: Int, seed: Long) = ByteArray(size) { i ->
        ((i * 2654435761L + seed * 97L) ushr 13).toByte()
    }

    @Test
    fun `the app computes the same fingerprints as the sync server`() {
        val vectors = vectorsFile().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        assertThat(vectors.size).isAtLeast(8)
        vectors.forEach { line ->
            val (size, seed, expected) = line.split(Regex("\\s+"))
            assertThat(FileFingerprint.of(content(size.toInt(), seed.toLong()))).isEqualTo(expected)
        }
    }
}
