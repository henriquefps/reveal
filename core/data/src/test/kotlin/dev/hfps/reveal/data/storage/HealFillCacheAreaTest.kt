package dev.hfps.reveal.data.storage

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.heal.RemoveColour
import dev.hfps.reveal.pipeline.heal.RemoveFill
import dev.hfps.reveal.pipeline.heal.RemoveOutput
import dev.hfps.reveal.pipeline.heal.RemoveSpec
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Remove's fills on disk (docs/adr/0023 "FIX/H"): exact round trip, a budgeted area, a damaged entry is a miss. */
class HealFillCacheAreaTest {
    private val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun fill(offsets: Boolean) = RemoveFill(
        RemoveSpec(6000, 4000, 5, 100, 80, 110, 86),
        RemoveColour(identity, identity, 1.7f),
        RemoveOutput(
            FloatArray(10 * 6 * 3) { it * 0.01f },
            if (offsets) FloatArray(10 * 6 * 2) { if (it % 7 == 0) Float.NaN else (it % 5).toFloat() } else null,
        ),
    )

    @Test
    fun `fills round-trip exactly and are counted, evicted and cleared`() = runTest {
        val root = Files.createTempDirectory("heal-fills").toFile()
        val area = HealFillCacheArea(File(root, HealFillCacheArea.DIR))
        assertThat(area.get("a")).isNull()
        area.put("a", fill(offsets = true))
        area.put("b", fill(offsets = false))
        val a = checkNotNull(area.get("a"))
        assertThat(a.spec).isEqualTo(fill(true).spec)
        assertThat(a.output.rgb).isEqualTo(fill(true).output.rgb)
        assertThat(a.output.offsets).isEqualTo(fill(true).output.offsets)
        assertThat(a.colour.gain).isEqualTo(1.7f)
        assertThat(checkNotNull(area.get("b")).output.offsets).isNull()
        val items = area.items()
        assertThat(items).hasSize(2)
        assertThat(area.kind).isEqualTo(StorageKind.MASKS)
        // No key on disk: the file names are hashes.
        assertThat(items.map { it.key }.none { it.startsWith("a") && it.length < 10 }).isTrue()
        area.evict(listOf(items.first().key))
        assertThat(area.items()).hasSize(1)
        area.clear()
        assertThat(area.items()).isEmpty()
        root.deleteRecursively()
    }

    @Test
    fun `a damaged entry is a miss and is deleted`() {
        val root = Files.createTempDirectory("heal-fills").toFile()
        val dir = File(root, HealFillCacheArea.DIR)
        val area = HealFillCacheArea(dir)
        area.put("a", fill(offsets = true))
        val file = dir.listFiles()!!.single()
        file.writeBytes(file.readBytes().copyOf(20))
        assertThat(area.get("a")).isNull()
        assertThat(file.exists()).isFalse()
        root.deleteRecursively()
    }

    @Test
    fun `the cap trims the oldest fills`() {
        val root = Files.createTempDirectory("heal-fills").toFile()
        var now = 1_000_000L
        val area = HealFillCacheArea(File(root, HealFillCacheArea.DIR), cap = { 1_000 }, clock = { now })
        area.put("old", fill(offsets = true))
        now += 10_000
        area.put("new", fill(offsets = true))
        assertThat(area.get("new")).isNotNull()
        assertThat(area.get("old")).isNull()
        root.deleteRecursively()
    }
}
