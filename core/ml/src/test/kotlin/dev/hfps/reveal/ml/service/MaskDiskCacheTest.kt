package dev.hfps.reveal.ml.service

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.Mask
import java.io.File
import java.nio.file.Files
import org.junit.Test

class MaskDiskCacheTest {
    private val dir: File = Files.createTempDirectory("masks").toFile()
    private var now = 1_000L
    private var writes = 0
    private val gradient = Mask(16, 8, FloatArray(128) { it / 127f })

    private fun cache(cap: Long = Long.MAX_VALUE) = MaskDiskCache(dir, { cap }, { writes++ }, { now })

    @Test
    fun `a stored mask comes back quantised to 8 bits, the same as quantised()`() {
        val c = cache()
        c.put("a", gradient)
        val back = c.get("a")!!
        assertThat(back.width).isEqualTo(16)
        assertThat(back.height).isEqualTo(8)
        assertThat(back.values.toList()).isEqualTo(MaskDiskCache.quantised(gradient).values.toList())
        for (i in back.values.indices) assertThat(back.values[i]).isWithin(0.5f / 255f).of(gradient.values[i])
        assertThat(writes).isEqualTo(1)
        assertThat(c.get("b")).isNull()
    }

    @Test
    fun `file names are hashes of the key, never the key`() {
        cache().put("photo-fingerprint|selfie-multiclass@1|HAIR", gradient)
        val names = dir.list()!!.toList()
        assertThat(names).hasSize(1)
        assertThat(names.single()).matches("[0-9a-f]{64}\\.mask")
    }

    @Test
    fun `writes trim to the cap, least recently used first, and a read refreshes an entry`() {
        val one = MaskDiskCache.encode(gradient).size.toLong()
        val c = cache(cap = one * 2)
        c.put("a", gradient)
        now += 10
        c.put("b", gradient)
        now += 10
        assertThat(c.get("a")).isNotNull() // a is now the most recent
        now += 10
        c.put("c", gradient)
        assertThat(c.get("b")).isNull()
        assertThat(c.get("a")).isNotNull()
        assertThat(c.get("c")).isNotNull()
    }

    @Test
    fun `evict, clear and entries`() {
        val c = cache()
        c.put("a", gradient)
        c.put("b", gradient)
        assertThat(c.entries()).hasSize(2)
        c.evict(listOf(MaskDiskCache.name("a")))
        assertThat(c.get("a")).isNull()
        assertThat(c.get("b")).isNotNull()
        c.clear()
        assertThat(c.entries()).isEmpty()
    }

    @Test
    fun `a damaged entry is a miss and is deleted`() {
        val c = cache()
        c.put("a", gradient)
        File(dir, MaskDiskCache.name("a")).writeBytes(byteArrayOf(1, 2, 3))
        assertThat(c.get("a")).isNull()
        assertThat(File(dir, MaskDiskCache.name("a")).exists()).isFalse()
    }
}
