package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

/** The leftover sweep runs after the first frame and spares this process's copies (ADR 0018 "W3/C"). */
class LocalCopiesSweepTest {
    @Test
    fun `sweep deletes only files older than the process start`() {
        val dir = kotlin.io.path.createTempDirectory("copies").toFile()
        val old = File(dir, "old").apply { writeText("x"); setLastModified(1_000) }
        val fresh = File(dir, "fresh").apply { writeText("x") }
        LocalCopies.sweep(dir, before = System.currentTimeMillis() - 10_000)
        assertThat(old.exists()).isFalse()
        assertThat(fresh.exists()).isTrue()
        dir.deleteRecursively()
    }

    @Test
    fun `the provider path returns at once and does not touch the disk before the first frame`() {
        val dir = kotlin.io.path.createTempDirectory("copies").toFile()
        val old = File(dir, "old").apply { writeText("x"); setLastModified(1_000) }
        val before = System.nanoTime()
        LocalCopies.sweepAfterFirstFrame(dir)
        assertThat((System.nanoTime() - before) / 1_000_000).isLessThan(500)
        // The sweep waits for the first frame ([AppStart]): nothing is deleted yet.
        assertThat(old.exists()).isTrue()
        dir.deleteRecursively()
    }
}
