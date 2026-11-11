package dev.hfps.image

import com.google.common.truth.Truth.assertThat
import dev.hfps.image.format.ImageInspector
import dev.hfps.image.format.ListedFile
import dev.hfps.image.format.ShotPairing
import dev.hfps.image.format.SonyLensCorrectionReader
import dev.hfps.image.source.CountingSource
import dev.hfps.image.source.FileSource
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in, read-only checks on a folder of COPIES of real camera files (never committed; the repo is public and these
 * are private photos): `REVEAL_ARW_DIR=/path/to/copies ./gradlew :core:image:testDebugUnitTest --tests '*RealShootTest*' -i`.
 *
 * Only aggregates are printed (counts, sizes, byte counts, timings): no file names, no capture times, no pixels.
 */
class RealShootTest {
    private val dir: File? = System.getenv("REVEAL_ARW_DIR")?.takeIf {
        it.isNotBlank()
    }?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `aggregate header statistics of a real shoot`() {
        val folder = dir
        assumeTrue("REVEAL_ARW_DIR not set", folder != null)
        val files = folder!!.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
        val shots = ShotPairing.pair(files.map { ListedFile(it.name, it.length(), it.lastModified()) })
        val bytesRead = mutableListOf<Long>()
        val readMs = mutableListOf<Double>()
        val previewSizes = mutableListOf<String>()
        val orientations = sortedMapOf<Int, Int>()
        var raws = 0
        var withLensData = 0
        for (file in files) {
            val counting = CountingSource(FileSource(file))
            val started = System.nanoTime()
            val inspection = counting.use { ImageInspector.inspect(it, file.name) }
            readMs += (System.nanoTime() - started) / 1e6
            if (!inspection.type.isRaw) continue
            raws++
            bytesRead += counting.bytesRead
            inspection.largestPreview?.let { previewSizes += "${it.width}x${it.height}" }
            orientations.merge(inspection.metadata.orientation, 1, Int::plus)
            assertThat(inspection.largestPreview).isNotNull()
            assertThat(counting.bytesRead).isLessThan(512L * 1024)
            if (FileSource(file).use { SonyLensCorrectionReader.read(it) } != null) withLensData++
        }
        println(
            "REALSHOOT files=${files.size} shots=${shots.size} pairs=${shots.count {
                it.isPair
            }} rawOnly=${shots.count { it.isRawOnly }} " +
                "raws=$raws headerBytes(min/max)=${bytesRead.minOrNull()}/${bytesRead.maxOrNull()} " +
                "inspectMs(mean)=${"%.1f".format(readMs.average())} previewSizes=${previewSizes.groupingBy {
                    it
                }.eachCount()} " +
                "orientations=$orientations sonyLensData=$withLensData/$raws",
        )
    }
}
