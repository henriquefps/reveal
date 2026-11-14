package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.inpaint.FillDiskCache
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** AI Remove's fills and the GPU's compiled programs are one budgeted area (docs/adr/0024). */
class FillCacheAreaTest {
    @Test
    fun `fills and compiled programs are counted, evicted and cleared`() = runTest {
        val root = Files.createTempDirectory("fill-area").toFile()
        val programs = File(root, "litert-gpu").apply { mkdirs() }
        val area = FillCacheArea(FillDiskCache(File(root, "inpaint")), programs)
        area.cache.put("a", FloatArray(30) { it * 0.1f })
        File(programs, "big-lama@1.bin").writeBytes(ByteArray(1000))
        val items = area.items()
        assertThat(items).hasSize(2)
        assertThat(area.kind).isEqualTo(StorageKind.MASKS)
        val gpu = items.single { it.key.startsWith("gpu/") }
        assertThat(gpu.bytes).isEqualTo(1000)
        area.evict(listOf(gpu.key))
        assertThat(programs.listFiles()!!.toList()).isEmpty()
        assertThat(area.items()).hasSize(1)
        File(programs, "again.bin").writeBytes(ByteArray(10))
        area.clear()
        assertThat(area.items()).isEmpty()
        root.deleteRecursively()
    }
}
