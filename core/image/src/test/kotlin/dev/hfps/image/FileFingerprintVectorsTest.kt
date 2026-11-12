package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.source.FileFingerprint
import java.io.File
import org.junit.Test

/**
 * The `v1:` fingerprint vectors shared with the sync server (`sync-server/testdata/fingerprint-vectors.txt`, ADR 0017
 * "Tests"): the app and the server must agree byte for byte, edges included (0 bytes, exactly 2 MiB, 2 MiB + 1).
 */
class FileFingerprintVectorsTest {
    private fun vectorsFile(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "sync-server/testdata/fingerprint-vectors.txt")
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        error("sync-server/testdata/fingerprint-vectors.txt not found above ${File("").absolutePath}")
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
