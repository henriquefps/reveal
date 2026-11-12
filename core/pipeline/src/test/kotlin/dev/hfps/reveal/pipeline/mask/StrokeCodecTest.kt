@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.mask

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BrushStroke
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Test

/** Brush strokes are stored as compact vectors (docs/adr/0022, "Brush strokes are vectors, compact"). */
class StrokeCodecTest {
    /** A hand-like scribble: a wandering curve sampled every ~2 screen pixels, pressure varying. */
    private fun scribble(n: Int, seed: Int = 7): List<StrokePoint> {
        val random = Random(seed)
        var x = 0.3f
        var y = 0.4f
        var angle = 0.0
        return List(n) { i ->
            angle += (random.nextDouble() - 0.5) * 0.3
            x += (0.0009 * cos(angle)).toFloat()
            y += (0.0009 * sin(angle)).toFloat()
            StrokePoint(x, y, (0.6 + 0.4 * sin(i * PI / 300)).toFloat())
        }
    }

    @Test
    fun `round trip gives the quantised points`() {
        val points = scribble(500) + StrokePoint(-0.2f, 1.3f, 0f) + StrokePoint(0f, 0f, 1f)
        val decoded = StrokeCodec.decode(StrokeCodec.encode(points))
        assertThat(decoded).isEqualTo(points.map(StrokeCodec::quantise))
        for ((a, b) in points.zip(decoded)) {
            assertThat(abs(a.x - b.x)).isAtMost(0.5f / StrokeCodec.POSITION_STEPS + 1e-7f)
            assertThat(abs(a.pressure - b.pressure)).isAtMost(0.5f / StrokeCodec.PRESSURE_STEPS + 1e-7f)
        }
    }

    @Test
    fun `a 5000-point stroke is compact JSON`() {
        val stroke = BrushStroke.raw(scribble(5000), size = 0.03f)
        val recipe = Recipe(masks = listOf(Mask("m", components = listOf(MaskComponent.Brush(strokes = listOf(stroke))))))
        val json = RecipeCodec.Default.encode(recipe)
        val bytes = json.toByteArray().size
        println("STROKE 5000 points raw: ${stroke.points.length} chars, recipe $bytes bytes")
        assertThat(bytes).isLessThan(24_000)
        assertThat(RecipeCodec.Default.decode(json)).isEqualTo(recipe)
        // Simplified when the stroke ends: far fewer points, still within a fraction of the dab radius.
        val simplified = BrushStroke.of(scribble(5000), size = 0.03f)
        println("STROKE 5000 points simplified: ${simplified.decoded.size} points, ${simplified.points.length} chars")
        assertThat(simplified.decoded.size).isLessThan(2500)
    }

    @Test
    fun `simplification keeps the ends, the shape and the pressure`() {
        val points = scribble(2000)
        val radius = 0.02f
        val kept = StrokeCodec.simplify(points, radius)
        assertThat(kept.first()).isEqualTo(points.first())
        assertThat(kept.last()).isEqualTo(points.last())
        assertThat(kept.size).isLessThan(points.size)
        // Every dropped point lies within the tolerance of the kept polyline.
        val tolerance = radius * StrokeCodec.SIMPLIFY_TOLERANCE
        for (p in points) {
            val d = kept.zipWithNext().minOf { (a, b) -> distance(p, a, b) }
            assertThat(d).isAtMost(tolerance * 1.001f)
        }
        // A straight line with constant pressure keeps its two ends only; a pressure ramp keeps more.
        val line = List(100) { StrokePoint(it / 100f, 0.5f, 1f) }
        assertThat(StrokeCodec.simplify(line, radius)).hasSize(2)
        val ramp = List(100) { StrokePoint(it / 100f, 0.5f, if (it < 50) 1f else 0.2f) }
        assertThat(StrokeCodec.simplify(ramp, radius).size).isGreaterThan(2)
    }

    @Test
    fun `malformed text decodes to what it can`() {
        assertThat(StrokeCodec.decode("")).isEmpty()
        assertThat(StrokeCodec.decode("!!not base64!!")).isEmpty()
        val text = StrokeCodec.encode(scribble(10))
        assertThat(StrokeCodec.decode(text.dropLast(3)).size).isAtMost(10)
    }

    private fun distance(p: StrokePoint, a: StrokePoint, b: StrokePoint): Float {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 > 0f) (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2).coerceIn(0f, 1f) else 0f
        val ex = p.x - a.x - t * dx
        val ey = p.y - a.y - t * dy
        return kotlin.math.sqrt(ex * ex + ey * ey)
    }
}
