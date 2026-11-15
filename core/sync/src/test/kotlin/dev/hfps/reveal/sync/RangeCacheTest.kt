package dev.hfps.reveal.sync

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.sync.files.RangeCache
import java.nio.file.Files
import kotlin.random.Random
import org.junit.Test

class RangeCacheTest {
    private val size = 40 * RangeCache.BLOCK.toInt() + 123
    private val data = Random(1).nextBytes(size)
    private val requests = ArrayList<Int>()
    private val fetch = { offset: Long, length: Int ->
        requests += length
        data.copyOfRange(offset.toInt(), offset.toInt() + length)
    }

    @Test
    fun `a header read costs one block, a forward read grows its read-ahead, and bytes are exact`() {
        val cache = RangeCache(Files.createTempDirectory("ranges").toFile())
        val buffer = ByteArray(64 * 1024)
        val n = cache.read("a", size.toLong(), 0, buffer.size, buffer, fetch)
        assertThat(buffer.copyOf(n)).isEqualTo(data.copyOf(n))
        assertThat(requests).containsExactly(RangeCache.BLOCK.toInt())
        // Reading the whole file forward: requests of 1, 2, 4, 8, 16, 16... blocks, never one per block.
        val whole = ByteArray(size)
        val step = ByteArray(1_000_000)
        var offset = 0
        while (offset < size) {
            val k = cache.read("a", size.toLong(), offset.toLong(), step.size, step, fetch)
            System.arraycopy(step, 0, whole, offset, k)
            offset += k
        }
        assertThat(whole).isEqualTo(data)
        assertThat(requests.size).isLessThan(10)
        assertThat(requests.sum()).isEqualTo(size)
        val check = ByteArray(5000)
        val m = cache.read("a", size.toLong(), size - 5000L, 5000, check, fetch)
        assertThat(check.copyOf(m)).isEqualTo(data.copyOfRange(size - 5000, size))
    }

    @Test
    fun `a complete file assembles into one exact copy, an incomplete one does not`() {
        val dir = Files.createTempDirectory("ranges").toFile()
        val cache = RangeCache(dir)
        val buffer = ByteArray(1_000_000)
        cache.read("a", size.toLong(), 0, buffer.size, buffer, fetch)
        assertThat(cache.complete("a", size.toLong())).isFalse()
        val partial = java.io.File(dir, "partial")
        assertThat(cache.assemble("a", size.toLong(), partial)).isFalse()
        assertThat(partial.exists()).isFalse()
        var offset = 0L
        while (offset < size) offset += cache.read("a", size.toLong(), offset, buffer.size, buffer, fetch)
        assertThat(cache.complete("a", size.toLong())).isTrue()
        val whole = java.io.File(dir, "whole")
        assertThat(cache.assemble("a", size.toLong(), whole)).isTrue()
        assertThat(whole.readBytes()).isEqualTo(data)
    }

    @Test
    fun `the trim drops files that are not pinned first`() {
        val dir = Files.createTempDirectory("ranges").toFile()
        var cap = Long.MAX_VALUE
        val cache = RangeCache(dir) { cap }
        val buffer = ByteArray(RangeCache.BLOCK.toInt())
        for (key in listOf("pinned", "recent")) {
            cache.read(key, size.toLong(), 0, buffer.size, buffer, fetch)
            Thread.sleep(20)
        }
        java.io.File(dir, "pinned").setLastModified(1_000)
        cap = RangeCache.BLOCK
        cache.trim(pinned = setOf("pinned"))
        assertThat(cache.files().map { it.first }).containsExactly("pinned")
    }
}
