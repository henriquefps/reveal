package dev.hfps.reveal.feature.library

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoRow
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PhotoSort
import dev.hfps.reveal.data.model.PickFlag
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test

/** The date grouping of the grid (W3/D): month headers with counts, day sub-headers, "No date", the [DateIndex]. */
class DateGridTest {
    private var next = 1L

    private fun row(y: Int, m: Int, d: Int, h: Int = 12): PhotoRow {
        val time = LocalDateTime.of(y, m, d, h, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        return row(time)
    }

    private fun row(time: Long): PhotoRow {
        val id = next++
        return PhotoRow(
            id = id, sourceId = 1, name = "p$id.jpg", format = PhotoFormat.JPEG, sortTime = time, captureTime = time,
            rating = 0, label = null, flag = PickFlag.NONE, hasEdit = false, versionId = id + 100, editTime = null,
        )
    }

    private val rows = listOf(
        row(2026, 11, 15, 23),
        row(2026, 11, 15, 1),
        row(2026, 11, 1, 0),
        row(2026, 10, 31, 23),
        row(2024, 2, 29),
        row(0),
        row(0),
    )

    private fun shape(entries: List<GridEntry>) = entries.map {
        when (it) {
            is GridEntry.Month -> "M${if (it.month == DateIndex.NO_DATE) {
                "-"
            } else {
                LibraryTags.monthKey(
                    it.month,
                )
            }}:${it.count}"

            is GridEntry.Header -> "D"

            is GridEntry.Photo -> "p"

            is GridEntry.Group -> "G"
        }
    }

    @Test
    fun `grouped by month, each month has one header with its count and no day headers`() {
        val grid = buildGrid(rows, PhotoQuery(), DateGrouping.MONTH)
        assertThat(shape(grid.entries)).containsExactly(
            "M2026-11:3", "p", "p", "p", "M2026-10:1", "p", "M2024-02:1", "p", "M-:2", "p", "p",
        ).inOrder()
    }

    @Test
    fun `grouped by day, the days are sub-headers inside their month`() {
        val grid = buildGrid(rows, PhotoQuery(), DateGrouping.DAY)
        // The camera's date is the stored wall clock: 23:00 and 01:00 on the 15th are one day, the 31st is October.
        assertThat(shape(grid.entries)).containsExactly(
            "M2026-11:3", "D", "p", "p", "D", "p",
            "M2026-10:1", "D", "p",
            "M2024-02:1", "D", "p",
            "M-:2", "p", "p",
        ).inOrder()
    }

    @Test
    fun `the date index finds every month's header and the month of any entry`() {
        val grid = buildGrid(rows, PhotoQuery(), DateGrouping.DAY)
        val index = checkNotNull(grid.dateIndex)
        val nov = DateIndex.of(2026, 11)
        val oct = DateIndex.of(2026, 10)
        assertThat(index.size).isEqualTo(4)
        for (i in 0 until index.size) {
            val header = grid.entries[index.starts[i]] as GridEntry.Month
            assertThat(header.month).isEqualTo(index.months[i])
            assertThat(header.count).isEqualTo(index.counts[i])
        }
        assertThat(index.startOf(nov)).isEqualTo(0)
        assertThat(index.startOf(oct)).isEqualTo(6)
        assertThat(index.startOf(DateIndex.of(2025, 1))).isNull()
        assertThat((0 until grid.entries.size).map { index.monthAt(it) }).containsExactly(
            nov, nov, nov, nov, nov, nov, oct, oct, oct,
            DateIndex.of(2024, 2), DateIndex.of(2024, 2), DateIndex.of(2024, 2),
            DateIndex.NO_DATE, DateIndex.NO_DATE, DateIndex.NO_DATE,
        ).inOrder()
        assertThat(index.countOf(DateIndex.NO_DATE)).isEqualTo(2)
        assertThat(index.positionAt(-1)).isEqualTo(-1)
    }

    @Test
    fun `month keys round-trip and photos without a date have none`() {
        val key = DateIndex.of(2026, 1)
        assertThat(DateIndex.year(key)).isEqualTo(2026)
        assertThat(DateIndex.monthOfYear(key)).isEqualTo(1)
        assertThat(DateIndex.monthOf(0)).isEqualTo(DateIndex.NO_DATE)
        assertThat(DateIndex.monthOf(-5)).isEqualTo(DateIndex.NO_DATE)
        assertThat(DateIndex.monthOf(1)).isEqualTo(DateIndex.of(1970, 1))
        assertThat(LibraryTags.month(DateIndex.of(2026, 3))).isEqualTo("library-month-2026-03")
    }

    @Test
    fun `the name order has no dates, and 25,000 rows group in one pass`() {
        assertThat(buildGrid(rows, PhotoQuery(sort = PhotoSort.NAME)).dateIndex).isNull()
        val many = (0 until 25_000).map { row(1_800_000_000_000L - it * 3_600_000L) }
        val start = System.nanoTime()
        val grid = buildGrid(many, PhotoQuery(), DateGrouping.DAY)
        val ms = (System.nanoTime() - start) / 1_000_000
        val index = checkNotNull(grid.dateIndex)
        assertThat(index.counts.sum()).isEqualTo(25_000)
        println("W3D buildGrid 25000 rows: $ms ms, ${index.size} months, ${grid.entries.size} entries")
    }
}
