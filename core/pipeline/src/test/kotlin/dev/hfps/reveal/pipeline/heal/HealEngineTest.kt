@file:Suppress(
    "MagicNumber",
    "NestedBlockDepth",
    "ComplexCondition",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.pipeline.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.PipelineInput
import dev.hfps.reveal.pipeline.input.colourOnly
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.synthetic.HealScenes
import org.junit.Test

/** The healing stage's CPU reference (docs/adr/0023): heal, clone, layering and incremental updates. */
class HealEngineTest {
    private val w = 160
    private val h = 120
    private val clean = HealScenes.texturedGradient(w, h)
    private val dusty = HealScenes.withDust(clean, 60f, 50f, 8f)

    private fun render(input: PipelineInput, heals: List<Heal>): FloatArray = HealOp.apply(input, heals).linearRgb

    @Test
    fun `no spot leaves the input untouched`() {
        assertThat(HealOp.apply(dusty, emptyList())).isSameInstanceAs(dusty)
        assertThat(HealOp.apply(dusty, listOf(Heal("placeholder")))).isSameInstanceAs(dusty)
    }

    @Test
    fun `clone copies the source inside the shape and nothing outside`() {
        val spot = HealScenes.spot("c", Heal.KIND_CLONE, w, h, 60f, 50f, 10f, ox = 40f, oy = 0f, feather = 0f)
        val out = render(dusty, listOf(spot))
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = x + 0.5f - 60f
                val dy = y + 0.5f - 50f
                val d2 = dx * dx + dy * dy
                val i = (y * w + x) * 3
                if (d2 < 9.5f * 9.5f) {
                    val s = (y * w + x + 40) * 3
                    for (c in 0..2) assertThat(out[i + c]).isEqualTo(dusty.linearRgb[s + c])
                } else if (d2 > 10.5f * 10.5f) {
                    for (c in 0..2) assertThat(out[i + c]).isEqualTo(dusty.linearRgb[i + c])
                }
            }
        }
    }

    @Test
    fun `heal removes a dust spot seamlessly`() {
        val spot = HealScenes.spot("h", Heal.KIND_HEAL, w, h, 60f, 50f, 11f, ox = 30f, oy = 10f)
        val out = render(dusty, listOf(spot))
        val before = HealScenes.meanError(dusty.linearRgb, clean.linearRgb, w, 50, 40, 70, 60)
        val after = HealScenes.meanError(out, clean.linearRgb, w, 50, 40, 70, 60)
        // The gradient differs between source and target: the membrane carries the target's light.
        assertThat(after).isLessThan(before * 0.15f)
        assertThat(after).isLessThan(0.012f)
    }

    @Test
    fun `heal is better than clone where the light differs`() {
        val heal = HealScenes.spot("h", Heal.KIND_HEAL, w, h, 60f, 50f, 11f, ox = 50f, oy = 30f)
        val clone = heal.copy(kind = Heal.KIND_CLONE)
        val e1 = HealScenes.meanError(render(dusty, listOf(heal)), clean.linearRgb, w, 50, 40, 70, 60)
        val e2 = HealScenes.meanError(render(dusty, listOf(clone)), clean.linearRgb, w, 50, 40, 70, 60)
        assertThat(e1).isLessThan(e2 * 0.5f)
    }

    @Test
    fun `a stroke heals along its path`() {
        val line = HealScenes.withDust(HealScenes.withDust(dusty, 90f, 50f, 6f), 110f, 52f, 6f)
        val spot = HealScenes.stroke(
            "s",
            Heal.KIND_HEAL,
            w,
            h,
            listOf(90f to 50f, 100f to 51f, 110f to 52f),
            radius = 9f,
            oy = 30f,
        )
        val out = render(line, listOf(spot))
        val before = HealScenes.meanError(line.linearRgb, clean.linearRgb, w, 84, 44, 116, 58)
        val after = HealScenes.meanError(out, clean.linearRgb, w, 84, 44, 116, 58)
        assertThat(after).isLessThan(before * 0.2f)
    }

    @Test
    fun `later spots read what earlier spots wrote`() {
        val first = HealScenes.spot("a", Heal.KIND_CLONE, w, h, 100f, 50f, 10f, ox = -40f, oy = 0f, feather = 0f)
        // The second copies from where the first wrote.
        val second = HealScenes.spot("b", Heal.KIND_CLONE, w, h, 100f, 90f, 6f, ox = 0f, oy = -40f, feather = 0f)
        val out = render(dusty, listOf(first, second))
        val centre = (90 * w + 100) * 3
        val dustCentre = (50 * w + 60) * 3
        for (c in 0..2) assertThat(out[centre + c]).isEqualTo(dusty.linearRgb[dustCentre + c])
    }

    @Test
    fun `incremental updates equal a fresh engine`() {
        val spots = listOf(
            HealScenes.spot("a", Heal.KIND_HEAL, w, h, 60f, 50f, 9f, ox = 30f, oy = 0f),
            HealScenes.spot("b", Heal.KIND_CLONE, w, h, 120f, 80f, 7f, ox = -20f, oy = -10f),
            HealScenes.spot("c", Heal.KIND_HEAL, w, h, 30f, 90f, 6f, ox = 0f, oy = -25f),
            HealScenes.spot("d", Heal.KIND_HEAL, w, h, 92f, 52f, 5f, ox = -30f, oy = 0f),
        )
        val engine = HealEngine(w, h, PixelReader.of(dusty), dusty.colourOnly())
        val steps = listOf(
            spots,
            spots.map { if (it.id == "b") it.copy(dx = it.dx + 0.05f) else it },
            spots.filter { it.id != "a" },
            spots.filter { it.id != "a" } + spots[0],
            listOf(spots[1], spots[0], spots[2], spots[3]),
            spots.map { if (it.id == "a") it.copy(radius = it.radius * 1.3f) else it },
            emptyList(),
            spots,
        )
        for (list in steps) {
            engine.update(list)
            val fresh = HealEngine(w, h, PixelReader.of(dusty), dusty.colourOnly()).also { it.update(list) }
            val a = dusty.linearRgb.copyOf().also { engine.overlay(0, 0, w, h, it) }
            val b = dusty.linearRgb.copyOf().also { fresh.overlay(0, 0, w, h, it) }
            assertThat(a).isEqualTo(b)
        }
    }

    @Test
    fun `an update reports where it changed`() {
        val a = HealScenes.spot("a", Heal.KIND_HEAL, w, h, 60f, 50f, 9f, ox = 30f, oy = 0f)
        val b = HealScenes.spot("b", Heal.KIND_CLONE, w, h, 130f, 90f, 7f, ox = -20f, oy = -10f)
        val engine = HealEngine(w, h, PixelReader.of(dusty), dusty.colourOnly())
        val first = checkNotNull(engine.update(listOf(a, b)))
        assertThat(first[0]).isAtMost(51)
        assertThat(first[2]).isAtLeast(137)
        assertThat(engine.update(listOf(a, b))).isNull()
        val moved = checkNotNull(engine.update(listOf(a, b.copy(dx = b.dx - 0.02f))))
        // Only b's box: a is untouched.
        assertThat(moved[0]).isAtLeast(120)
    }

    @Test
    fun `rendering is deterministic`() {
        val spots = listOf(HealScenes.spot("a", Heal.KIND_HEAL, w, h, 60f, 50f, 9f, ox = 30f, oy = 0f))
        assertThat(render(dusty, spots)).isEqualTo(render(dusty, spots))
    }

    @Test
    fun `opacity scales the repair`() {
        val full = HealScenes.spot("a", Heal.KIND_CLONE, w, h, 60f, 50f, 9f, ox = 30f, oy = 0f, feather = 0f)
        val half = full.copy(opacity = 50f)
        val o1 = render(dusty, listOf(full))
        val o2 = render(dusty, listOf(half))
        val i = (50 * w + 60) * 3
        for (c in 0..2) {
            val expected = dusty.linearRgb[i + c] + 0.5f * (o1[i + c] - dusty.linearRgb[i + c])
            assertThat(o2[i + c]).isWithin(1e-6f).of(expected)
        }
    }

    @Test
    fun `a spot at the image edge stays finite`() {
        val spot = HealScenes.spot("e", Heal.KIND_HEAL, w, h, 2f, 3f, 10f, ox = 30f, oy = 20f)
        val out = render(dusty, listOf(spot))
        assertThat(out.all { it.isFinite() }).isTrue()
    }
}
