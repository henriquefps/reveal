package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.synthetic.SyntheticImages
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import org.junit.Test

class HighlightReconstructionTest {
    private val mul = SyntheticImages.TEST_AS_SHOT
    private val params = HighlightParams.from(mul, 1f)

    private fun balanced(input: PipelineInput): FloatArray =
        input.linearRgb.copyOf().also { RawWhiteBalanceOp.apply(it, mul) }

    private fun reconstruct(input: PipelineInput): FloatArray {
        val grid = LowResGrid(input.width, input.height)
        val rgb = balanced(input)
        HighlightReconstructionOp.apply(rgb, grid, params, HighlightReconstructionOp.chroma(rgb, grid, params))
        return rgb
    }

    /** The M0 behaviour (dcraw -H 0): every balanced channel clamped at the lowest clip level. */
    private fun m0Clip(input: PipelineInput): FloatArray {
        val clip = params.clipR.coerceAtMost(params.clipG).coerceAtMost(params.clipB)
        return balanced(input).also { for (i in it.indices) it[i] = it[i].coerceAtMost(clip) }
    }

    @Test
    fun `clip amount ramps from 90 to 99 percent of the clip level`() {
        assertThat(HighlightReconstructionOp.clipAmount(0.5f, 1f)).isEqualTo(0f)
        assertThat(HighlightReconstructionOp.clipAmount(0.9f, 1f)).isEqualTo(0f)
        assertThat(HighlightReconstructionOp.clipAmount(0.945f, 1f)).isWithin(1e-5f).of(0.5f)
        assertThat(HighlightReconstructionOp.clipAmount(0.99f, 1f)).isEqualTo(1f)
        assertThat(HighlightReconstructionOp.clipAmount(2f, 2f)).isEqualTo(1f)
    }

    @Test
    fun `nothing near the clip is the identity, bit exact`() {
        val input = SyntheticImages.rawNoise(40, 30, seed = 3).let {
            PipelineInput(
                it.kind,
                it.width,
                it.height,
                it.linearRgb.map { v -> v * 0.85f }.toFloatArray(),
                it.cameraToXyz,
                it.asShotMultipliers,
                saturation = 1f,
            )
        }

        assertThat(HighlightReconstructionOp.needed(input.linearRgb, 1f)).isFalse()
        assertThat(reconstruct(input)).isEqualTo(balanced(input))
    }

    @Test
    fun `with no unclipped pixel anywhere the chroma is neutral and the result neutral`() {
        val data = FloatArray(16 * 8 * 3) { 1f }
        val input = PipelineInput(
            SyntheticImages.rawRamp(16, 8, 1f).kind,
            16,
            8,
            data,
            SyntheticImages.TEST_CAMERA_TO_XYZ,
            mul,
            saturation = 1f,
        )
        val grid = LowResGrid(16, 8)

        val chroma = HighlightReconstructionOp.chroma(balanced(input), grid, params)
        val out = reconstruct(input)

        for (i in 0 until chroma.size / 4) {
            for (c in 0 until 3) assertThat(chroma[i * 4 + c]).isWithin(1e-6f).of(1f / 3f)
        }
        for (i in out.indices step 3) {
            assertThat(out[i]).isEqualTo(params.maxClip)
            assertThat(out[i + 1]).isEqualTo(params.maxClip)
            assertThat(out[i + 2]).isEqualTo(params.maxClip)
        }
    }

    @Test
    fun `partially clipped highlights come back close to the truth, far closer than the M0 clip`() {
        val input = SyntheticImages.clippedHighlights(192, 128)
        val truth = balanced(SyntheticImages.clippedHighlights(192, 128, truth = true))
        val recon = reconstruct(input)
        val m0 = m0Clip(input)

        // Pixels of the left blob where at least one channel clipped and at least one survived.
        var reconError = 0.0
        var m0Error = 0.0
        var n = 0
        for (y in 0 until 128) {
            for (x in 0 until 96) {
                val i = (y * 192 + x) * 3
                val raw = input.linearRgb
                val clipped = (0 until 3).count { raw[i + it] >= 0.99f }
                if (clipped == 0 || clipped == 3) continue
                for (c in 0 until 3) {
                    reconError += abs(ln(recon[i + c] / truth[i + c]))
                    m0Error += abs(ln(m0[i + c] / truth[i + c]))
                }
                n++
            }
        }
        reconError /= n * 3
        m0Error /= n * 3
        println("REVEAL_HL partially clipped pixels: $n, mean |log error| recon $reconError, M0 clip $m0Error")
        assertThat(n).isGreaterThan(500)
        assertThat(reconError).isLessThan(m0Error / 4)
        assertThat(reconError).isLessThan(0.08)
    }

    @Test
    fun `reconstructed highlights keep the hue of the light and are never magenta`() {
        val input = SyntheticImages.clippedHighlights(192, 128)
        val recon = reconstruct(input)
        val colour = SyntheticImages.HIGHLIGHT_COLOUR

        // Core of the left blob: green and red clipped, blue alive; balanced ratios follow the light's colour.
        val i = (64 * 192 + (192 * 0.3f).toInt()) * 3
        val expectedRg = colour[0] * mul[0] / (colour[1] * mul[1])
        assertThat(recon[i] / recon[i + 1]).isWithin(0.08f).of(expectedRg)
        // Fully clipped core of the right blob: exactly neutral, at least at the largest clip level.
        val j = (64 * 192 + 144) * 3
        assertThat(recon[j]).isEqualTo(recon[j + 1])
        assertThat(recon[j + 2]).isEqualTo(recon[j + 1])
        assertThat(recon[j + 1]).isAtLeast(params.maxClip)
        // Nowhere is green the weakest channel where green clipped (the magenta signature of naive WB).
        for (p in 0 until 192 * 128) {
            if (input.linearRgb[p * 3 + 1] < 0.99f) continue
            assertThat(recon[p * 3 + 1]).isAtLeast(minOf(recon[p * 3], recon[p * 3 + 2]) * 0.999f)
        }
    }

    @Test
    fun `luminance rises into a blown core with no dark ring`() {
        val input = SyntheticImages.clippedHighlights(192, 128)
        val developed = CpuDevelop.renderLinear(input, Recipe(light = Light(exposureEv = -2f)))

        for (blobX in listOf((192 * 0.3f).toInt(), 144)) {
            var runningMax = 0f
            var worstDip = 0f
            var y = 0f
            for (x in blobX - 40..blobX) {
                val i = (64 * 192 + x) * 3
                y = Luma.of(developed[i], developed[i + 1], developed[i + 2])
                runningMax = max(runningMax, y)
                worstDip = max(worstDip, 1f - y / runningMax)
            }
            println("REVEAL_HL blob at x=$blobX: core luminance $y, max on the way $runningMax, worst dip $worstDip")
            // Small dips (measured 2.4 % and 6.6 %) come from the chroma estimate varying between the rim and the
            // core (docs/adr/0007, limits); a dark core would be a dip of tens of percent at the centre.
            assertThat(worstDip).isLessThan(0.08f)
            assertThat(y).isAtLeast(runningMax * 0.95f)
        }
    }

    @Test
    fun `minus EV recovers detail where one channel survived`() {
        // Along the left blob's core row, blue varies; the M0 clip makes the whole core one flat value.
        val input = SyntheticImages.clippedHighlights(192, 128)
        val recon = reconstruct(input)
        val m0 = m0Clip(input)
        val cx = (192 * 0.3f).toInt()
        val reconSpan = (recon[(64 * 192 + cx) * 3 + 1] - recon[(64 * 192 + cx - 12) * 3 + 1])
        val m0Span = (m0[(64 * 192 + cx) * 3 + 1] - m0[(64 * 192 + cx - 12) * 3 + 1])

        assertThat(m0Span).isEqualTo(0f)
        assertThat(reconSpan).isGreaterThan(0.2f)
    }

    @Test
    fun `push pull fills holes from the neighbourhood and reports confidence`() {
        // A 4x1 level: one confident reddish cell, three empty ones.
        val level = floatArrayOf(0.5f, 0.3f, 0.2f, 1f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
        val parent = HighlightReconstructionOp.push(level, 4, 1)
        val top = HighlightReconstructionOp.push(parent, 2, 1)
        val filledTop = HighlightReconstructionOp.pull(top, 1, 1, null)
        val filledParent = HighlightReconstructionOp.pull(parent, 2, 1, filledTop)
        val filled = HighlightReconstructionOp.pull(level, 4, 1, filledParent)

        assertThat(filled[0]).isWithin(1e-6f).of(0.5f)
        for (cellIndex in 1 until 4) {
            assertThat(filled[cellIndex * 4]).isWithin(1e-4f).of(0.5f)
            assertThat(filled[cellIndex * 4 + 2]).isWithin(1e-4f).of(0.2f)
        }
    }

    @Test
    fun `reconstruction is deterministic`() {
        val input = SyntheticImages.clippedHighlights(96, 64)

        assertThat(reconstruct(input)).isEqualTo(reconstruct(input))
    }
}
