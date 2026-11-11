@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.calibration

import dev.hfps.reveal.pipeline.cpu.CpuDevelop
import dev.hfps.reveal.pipeline.cpu.NoiseModel
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Opt-in check of the noise model (docs/adr/0012, "Noise model") on public CC0 RAWs: the measured noise of the default
 * render (OkLab L of a 1000 x 1000 centre crop of a full-size dump: per 16 x 16 block the RMS of horizontal
 * differences over sqrt 2, the 5th percentile over the mid-tone blocks (mean L 0.45-0.65), i.e. the flattest mid-tones)
 * against the model's factor. Prints `NOISE <file> ISO <iso> sigma <s> factor <f>
 * ratio <s / f>`: a good model gives similar ratios.
 *
 * `REVEAL_NOISE_DUMPS=<a.rdump>,<b.rdump> ./gradlew :core:pipeline:testDebugUnitTest --tests '*NoiseCalibrationTest*'
 * --rerun -i`
 */
class NoiseCalibrationTest {
    @Test
    @Suppress("NestedBlockDepth")
    fun `measure noise against the model`() {
        val dumps = System.getenv("REVEAL_NOISE_DUMPS")?.split(',')?.map { File(it.trim()) }?.filter { it.isFile }
        assumeTrue("REVEAL_NOISE_DUMPS not set", !dumps.isNullOrEmpty())
        for (file in dumps!!) {
            val raw = RawDump.read(file)
            val size = minOf(CROP, raw.width, raw.height)
            val input = raw.crop((raw.width - size) / 2, (raw.height - size) / 2, size, size)
            val rgb = CpuDevelop.renderLinear(input, Recipe())
            val t = FloatArray(3)
            val l = FloatArray(size * size) {
                OkLab.fromRgb(rgb[it * 3], rgb[it * 3 + 1], rgb[it * 3 + 2], t)
                t[0]
            }
            // Per 16 x 16 block: the RMS of horizontal differences / sqrt 2 (the noise where the block is flat);
            // the 5th percentile over blocks is the noise of the flattest areas, free of scene detail.
            val blocks = ArrayList<Float>()
            for (by in 0 until size / BLOCK) {
                for (bx in 0 until size / BLOCK) {
                    var s2 = 0.0
                    var sum = 0.0
                    var n = 0
                    for (y in by * BLOCK until (by + 1) * BLOCK) {
                        for (x in bx * BLOCK until (bx + 1) * BLOCK - 1) {
                            val d = l[y * size + x + 1] - l[y * size + x]
                            s2 += d * d
                            sum += l[y * size + x]
                            n++
                        }
                    }
                    val mean = sum / n
                    // Mid-tones only: OkLab L's noise depends on the brightness (the cube root is steep in the dark).
                    if (mean in MID_LOW..MID_HIGH) blocks += kotlin.math.sqrt(s2 / n / 2.0).toFloat()
                }
            }
            if (blocks.isEmpty()) continue
            blocks.sort()
            val sigma = blocks[blocks.size / 20]
            val factor = NoiseModel.factor(input.kind, input.noise)
            println(
                "NOISE ${file.name} (${blocks.size} mid-tone blocks) ${input.noise?.model} ISO ${input.noise?.iso} " +
                    "sigma $sigma factor $factor ratio ${sigma / factor}",
            )
        }
    }

    private companion object {
        const val CROP = 1000
        const val BLOCK = 16
        const val MID_LOW = 0.45
        const val MID_HIGH = 0.65
    }
}
