package dev.hfps.reveal.feature.editor.heal

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Heal
import dev.hfps.reveal.pipeline.recipe.HealKind
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.StrokePoint
import org.junit.Test

/** The Heal mode's pure edits (docs/adr/0023 "Editor"). */
class HealEditsTest {
    private val settings = HealSettings(kind = HealKind.CLONE, size = 0.02f, feather = 30f, opacity = 80f)

    @Test
    fun `a circle takes the settings`() {
        val spot = HealEdits.circle(0.3f, 0.4f, settings, id = "a")
        assertThat(spot.kind).isEqualTo(Heal.KIND_CLONE)
        assertThat(spot.radius).isEqualTo(0.02f)
        assertThat(spot.feather).isEqualTo(30f)
        assertThat(spot.opacity).isEqualTo(80f)
        assertThat(spot.isStroke).isFalse()
        assertThat(HealEdits.settingsOf(spot, HealSettings())).isEqualTo(settings.copy(darken = spot.darken))
    }

    @Test
    fun `a stroke is simplified and keeps its ends`() {
        val points = (0..100).map { StrokePoint(0.2f + it * 0.004f, 0.5f) }
        val spot = HealEdits.stroke(points, settings, aspect = 1.5f, id = "s")
        assertThat(spot.isStroke).isTrue()
        assertThat(spot.strokePoints.size).isLessThan(10)
        assertThat(spot.strokePoints.first().x).isWithin(1e-4f).of(0.2f)
        assertThat(spot.strokePoints.last().x).isWithin(1e-4f).of(0.6f)
    }

    @Test
    fun `add, update, delete and the limit`() {
        val a = HealEdits.circle(0.3f, 0.4f, settings, id = "a")
        var recipe = checkNotNull(HealEdits.add(Recipe(), a))
        recipe = HealEdits.update(recipe, "a") { it.copy(dx = 0.1f) }
        assertThat(HealEdits.find(recipe, "a")?.dx).isEqualTo(0.1f)
        assertThat(HealEdits.delete(recipe, "a").heals).isEmpty()
        val full = Recipe(heals = List(Heal.MAX_HEALS) { a.copy(id = "$it") })
        assertThat(HealEdits.add(full, a)).isNull()
    }

    @Test
    fun `moving a stroke translates its points and keeps the source offset`() {
        val points = listOf(StrokePoint(0.2f, 0.2f), StrokePoint(0.4f, 0.2f))
        val spot = HealEdits.stroke(points, settings, 1f, id = "s").copy(dx = 0.1f)
        val (cx, cy) = HealEdits.centre(spot)
        val moved = HealEdits.moved(spot, cx + 0.1f, cy + 0.05f)
        assertThat(moved.strokePoints.first().x).isWithin(1e-4f).of(0.3f)
        assertThat(moved.strokePoints.first().y).isWithin(1e-4f).of(0.25f)
        assertThat(moved.dx).isEqualTo(0.1f)
        val circle = HealEdits.moved(HealEdits.circle(0.5f, 0.5f, settings), 0.6f, 0.7f)
        assertThat(circle.x to circle.y).isEqualTo(0.6f to 0.7f)
    }

    @Test
    fun `the fallback source stays in the image`() {
        val right = HealEdits.circle(0.5f, 0.5f, settings)
        assertThat(HealEdits.fallbackSource(right, 1.5f).first).isGreaterThan(0f)
        val edge = HealEdits.circle(0.97f, 0.5f, settings)
        assertThat(HealEdits.fallbackSource(edge, 1.5f).first).isLessThan(0f)
    }

    @Test
    fun `settings resize in steps within the range`() {
        assertThat(HealSettings(size = 0.01f).resized(1).size).isWithin(1e-6f).of(0.0115f)
        assertThat(HealSettings(size = HealSettings.MAX_SIZE).resized(3).size).isEqualTo(HealSettings.MAX_SIZE)
        assertThat(HealSettings(size = HealSettings.MIN_SIZE).resized(-2).size).isEqualTo(HealSettings.MIN_SIZE)
    }

    @Test
    fun `a stroke's length is isotropic`() {
        val points = listOf(StrokePoint(0f, 0f), StrokePoint(0f, 0.5f))
        // Portrait 2:3: half the height is a third of the long edge... the height is the long edge: 0.5.
        assertThat(HealEdits.length(points, aspect = 2f / 3f)).isWithin(1e-5f).of(0.5f)
        assertThat(HealEdits.length(points, aspect = 1.5f)).isWithin(1e-5f).of(0.5f / 1.5f)
    }

    @Test
    fun `remove spots store the chosen method, other kinds the patch fill`() {
        val ai = "model:big-lama@1"
        val remove = HealSettings(kind = HealKind.REMOVE)
        // default: the app's model when it has one, else Patch
        assertThat(remove.method(ai)).isEqualTo(ai)
        assertThat(remove.method(null)).isEqualTo(Heal.METHOD_PATCH)
        assertThat(remove.copy(removeMethod = Heal.METHOD_PATCH).method(ai)).isEqualTo(Heal.METHOD_PATCH)
        val spot = HealEdits.circle(0.5f, 0.5f, remove.copy(removeMethod = remove.method(ai)))
        assertThat(spot.method).isEqualTo(ai)
        val heal = HealEdits.circle(0.5f, 0.5f, HealSettings(kind = HealKind.HEAL, removeMethod = ai))
        assertThat(heal.method).isEqualTo(Heal.METHOD_PATCH)
        // selecting a Remove spot shows its method; another kind keeps the current choice
        assertThat(HealEdits.settingsOf(spot, HealSettings()).removeMethod).isEqualTo(ai)
        assertThat(HealEdits.settingsOf(heal, remove.copy(removeMethod = ai)).removeMethod).isEqualTo(ai)
    }
}
