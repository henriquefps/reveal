package dev.hfps.reveal.data.handoff

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The one last-writer-wins comparator (Hoard ADR 0016 step 5): time first, then the ordinal device id. */
class CullingStampTest {
    private fun stamp(at: Long, device: String? = null) = CullingStamp(at, device)

    @Test
    fun `the later time wins whatever the device ids`() {
        assertThat(stamp(2, "a").beats(stamp(1, "z"))).isTrue()
        assertThat(stamp(1, "z").beats(stamp(2, "a"))).isFalse()
    }

    @Test
    fun `at equal times the greater device id wins, and an equal stamp wins over nothing but itself`() {
        assertThat(stamp(5, "b").beats(stamp(5, "a"))).isTrue()
        assertThat(stamp(5, "a").beats(stamp(5, "b"))).isFalse()
        assertThat(stamp(5, "a").beats(stamp(5, "a"))).isFalse()
        assertThat(stamp(5, "a").beats(stamp(5))).isTrue()
        assertThat(stamp(5).beats(stamp(5, "a"))).isFalse()
        assertThat(stamp(5).beats(stamp(5))).isFalse()
    }

    @Test
    fun `device ids compare byte-wise, upper case before lower case and shorter before longer`() {
        // A locale collator would put "a" before "B"; Go's string order (bytes) puts "B" (0x42) before "a" (0x61).
        assertThat(stamp(1, "a").beats(stamp(1, "B"))).isTrue()
        assertThat(stamp(1, "ab").beats(stamp(1, "a"))).isTrue()
        assertThat(CullingStamp.compareIds("0000", "0001")).isLessThan(0)
        // UTF-8 order, not UTF-16: U+FF5E (BMP, 0xEF...) sorts before U+1F600 (0xF0...) in bytes, and in UTF-16
        // the surrogate 0xD83D would sort it first.
        val bmp = "～"
        val astral = String(Character.toChars(0x1F600))
        assertThat(CullingStamp.compareIds(astral, bmp)).isGreaterThan(0)
    }

    @Test
    fun `uuids compare like Go strings`() {
        val low = "0a000000-0000-4000-8000-000000000000"
        val high = "ff000000-0000-4000-8000-000000000000"
        assertThat(stamp(9, high).beats(stamp(9, low))).isTrue()
    }
}
