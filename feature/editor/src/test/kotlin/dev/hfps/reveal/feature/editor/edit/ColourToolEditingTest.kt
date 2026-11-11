package dev.hfps.reveal.feature.editor.edit

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.CurveChannel
import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.Treatment
import kotlin.math.hypot
import org.junit.Test

/** The curve editor's and grading wheels' maths (docs/adr/0013). */
class ColourToolEditingTest {
    private val line = CurveEditing.shown(emptyList())

    @Test
    fun addingAPointPutsItOnTheCurveSoNothingChangesYet() {
        val (points, index) = checkNotNull(CurveEditing.add(Curves.MEDIUM_CONTRAST, 0.4f))
        assertThat(index).isEqualTo(3)
        assertThat(points[index].y).isWithin(1e-4f).of(CurveEditing.valueAt(Curves.MEDIUM_CONTRAST, 0.4f))
        for (i in 0..100) {
            val x = i / 100f
            assertThat(
                CurveEditing.valueAt(points, x),
            ).isWithin(2e-2f).of(CurveEditing.valueAt(Curves.MEDIUM_CONTRAST, x))
        }
    }

    @Test
    fun addingIsRefusedNextToAPointOrWhenFull() {
        assertThat(CurveEditing.add(line, 0.005f)).isNull()
        var points = line
        while (true) points = CurveEditing.add(points, CurveEditing.widestGapMiddle(points))?.first ?: break
        assertThat(points).hasSize(Curves.MAX_POINTS)
    }

    @Test
    fun movingKeepsOrderAndTheSquare() {
        val (points, i) = checkNotNull(CurveEditing.add(line, 0.5f))
        val moved = CurveEditing.move(points, i, 1.5f, -0.2f)
        assertThat(moved[i].x).isWithin(1e-6f).of(1f - CurveEditing.MIN_GAP)
        assertThat(moved[i].y).isEqualTo(0f)
        // End points move too (black/white points) but stay at their side of the neighbours.
        val black = CurveEditing.move(points, 0, 0.7f, 0.1f)
        assertThat(black[0].x).isWithin(1e-6f).of(0.5f - CurveEditing.MIN_GAP)
        assertThat(black[0].y).isEqualTo(0.1f)
    }

    @Test
    fun onlyInteriorPointsCanBeRemovedAndDraggingOffIsDetected() {
        val (points, i) = checkNotNull(CurveEditing.add(line, 0.5f))
        assertThat(CurveEditing.remove(points, 0)).isEqualTo(points)
        assertThat(CurveEditing.remove(points, i)).isEqualTo(line)
        assertThat(CurveEditing.draggedOff(0.5f, 1.2f, 0.1f)).isTrue()
        assertThat(CurveEditing.draggedOff(0.5f, 1.05f, 0.1f)).isFalse()
    }

    @Test
    fun nearestHonoursTheGrabRadius() {
        val points = listOf(CurvePoint(0f, 0f), CurvePoint(0.5f, 0.5f), CurvePoint(1f, 1f))
        assertThat(CurveEditing.nearest(points, 0.52f, 0.49f, 0.05f, 0.05f)).isEqualTo(1)
        assertThat(CurveEditing.nearest(points, 0.6f, 0.4f, 0.05f, 0.05f)).isNull()
        assertThat(CurveEditing.level(0.5f)).isEqualTo(128)
    }

    @Test
    fun aStraightLineIsStoredAsTheEmptyList() {
        val r = RecipeControls.withCurve(Recipe.DEFAULT, CurveChannel.RED, Curves.LINEAR)
        assertThat(r.light.curve.red).isEmpty()
        assertThat(r).isEqualTo(Recipe.DEFAULT)
        assertThat(RecipeControls.withTreatment(Recipe.DEFAULT, Treatment.BLACK_WHITE).profile.treatment)
            .isEqualTo(Treatment.BLACK_WHITE)
    }

    @Test
    fun wheelPuckAndAnglesRoundTrip() {
        for (hue in listOf(0f, 45f, 90f, 210f, 359f)) {
            val (x, y) = WheelMath.puck(hue, 60f, 100f)
            assertThat(hypot(x, y)).isWithin(1e-3f).of(60f)
            assertThat(WheelMath.fromPuck(x, y, 100f, 0f)).isEqualTo(hue to 60f)
        }
        // Up on screen is hue 90 (counter-clockwise from the right), the rim caps saturation, the centre keeps hue.
        assertThat(WheelMath.fromPuck(0f, -50f, 100f, 0f)).isEqualTo(90f to 50f)
        assertThat(WheelMath.fromPuck(300f, 0f, 100f, 0f).second).isEqualTo(100f)
        assertThat(WheelMath.fromPuck(0.1f, 0f, 100f, 210f)).isEqualTo(210f to 0f)
    }
}
