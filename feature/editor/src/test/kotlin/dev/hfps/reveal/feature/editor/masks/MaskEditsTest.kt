package dev.hfps.reveal.feature.editor.masks

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.MaskKind
import dev.hfps.reveal.pipeline.mask.MaskPlan
import dev.hfps.reveal.pipeline.mask.MaskRaster
import dev.hfps.reveal.pipeline.recipe.LocalAdjustment
import dev.hfps.reveal.pipeline.recipe.LocalAdjustments
import dev.hfps.reveal.pipeline.recipe.Mask
import dev.hfps.reveal.pipeline.recipe.MaskComponent
import dev.hfps.reveal.pipeline.recipe.MaskOp
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import org.junit.Test

/** The mask edits of the Masks panel (docs/adr/0022 "M4/B (editor)"). */
class MaskEditsTest {
    private val template = "Mask %d"

    @Test
    fun `a new mask is named, appended and has one component of its kind`() {
        var r = Recipe()
        val (r1, first) = checkNotNull(MaskEdits.create(r, NewComponent.Radial, MaskEdits.nextName(r, template), "a"))
        r = r1
        assertThat(first.name).isEqualTo("Mask 1")
        assertThat(first.components.single()).isInstanceOf(MaskComponent.Radial::class.java)
        val (r2, second) = checkNotNull(MaskEdits.create(r, NewComponent.Brush, MaskEdits.nextName(r, template), "b"))
        assertThat(second.name).isEqualTo("Mask 2")
        assertThat(r2.masks.map { it.id }).containsExactly("a", "b").inOrder()
        // A freed number is used again.
        assertThat(MaskEdits.nextName(MaskEdits.delete(r2, "a"), template)).isEqualTo("Mask 1")
    }

    @Test
    fun `an AI mask records today's model and its refinement`() {
        val c = MaskEdits.component(NewComponent.Ai(MaskKind.SKY)) as MaskComponent.Ai
        assertThat(c.kind).isEqualTo("SKY")
        assertThat(c.model).isEqualTo("deeplab-v3-ade20k-sky@1")
        assertThat(c.refine).isNotNull()
        val hair = MaskEdits.component(NewComponent.Ai(MaskKind.HAIR), MaskOp.SUBTRACT) as MaskComponent.Ai
        assertThat(hair.model).startsWith("selfie-multiclass@")
        assertThat(hair.op).isEqualTo(MaskOp.SUBTRACT)
    }

    @Test
    fun `no more than the engine renders`() {
        var r = Recipe()
        repeat(MaskEdits.MAX_MASKS) { r = checkNotNull(MaskEdits.create(r, NewComponent.Linear, "m$it")).first }
        assertThat(MaskEdits.create(r, NewComponent.Linear, "one too many")).isNull()
        assertThat(MaskEdits.duplicate(r, r.masks.first().id, "copy")).isNull()
    }

    @Test
    fun `duplicate, move, rename, toggle and delete are one recipe change each, labelled by RecipeDiff`() {
        var r = Recipe(masks = listOf(Mask("a", "Sky"), Mask("b", "Face")))
        val (dup, copy) = checkNotNull(MaskEdits.duplicate(r, "a", "Sky copy", "c"))
        assertThat(dup.masks.map { it.id }).containsExactly("a", "c", "b").inOrder()
        assertThat(copy.name).isEqualTo("Sky copy")
        r = MaskEdits.move(dup, "b", 0)
        assertThat(r.masks.map { it.id }).containsExactly("b", "a", "c").inOrder()
        assertThat(MaskEdits.move(r, "b", 99).masks.last().id).isEqualTo("b")
        val renamed = MaskEdits.rename(r, "a", "  Clouds  ")
        assertThat(MaskEdits.find(renamed, "a")!!.name).isEqualTo("Clouds")
        assertThat(MaskEdits.find(MaskEdits.rename(r, "a", "   "), "a")!!.name).isEqualTo("Sky")
        val hidden = MaskEdits.setEnabled(r, "a", false)
        assertThat(MaskEdits.find(hidden, "a")!!.enabled).isFalse()
        assertThat(RecipeDiff.between(r, hidden).map { it.field.name }).containsExactly("MASK_HIDE")
        assertThat(RecipeDiff.between(r, renamed).map { it.field.name }).containsExactly("MASK_RENAME")
        assertThat(MaskEdits.delete(r, "zzz")).isEqualTo(r)
    }

    @Test
    fun `adjustments are clamped to their range and reset with the amount`() {
        var r = Recipe(masks = listOf(Mask("a")))
        r = MaskEdits.setAdjustment(r, "a", LocalAdjustment.EXPOSURE, 9f)
        r = MaskEdits.setAmount(r, "a", 140f)
        assertThat(MaskEdits.find(r, "a")!!.adjustments.exposure).isEqualTo(4f)
        assertThat(MaskEdits.find(r, "a")!!.amount).isEqualTo(100f)
        r = MaskEdits.setAmount(r, "a", 30f)
        r = MaskEdits.resetAdjustments(r, "a")
        assertThat(MaskEdits.find(r, "a")!!.adjustments).isEqualTo(LocalAdjustments())
        assertThat(MaskEdits.find(r, "a")!!.amount).isEqualTo(100f)
    }

    @Test
    fun `components are added, recombined, inverted and removed`() {
        var r = Recipe(masks = listOf(Mask("a", components = listOf(MaskComponent.Radial()))))
        r = MaskEdits.addComponent(r, "a", NewComponent.LuminanceRange, MaskOp.INTERSECT)
        val mask = MaskEdits.find(r, "a")!!
        assertThat(mask.components[1]).isInstanceOf(MaskComponent.LuminanceRange::class.java)
        assertThat(mask.components[1].op).isEqualTo(MaskOp.INTERSECT)
        r = MaskEdits.setComponentOp(r, "a", 1, MaskOp.SUBTRACT)
        r = MaskEdits.toggleComponentInvert(r, "a", 1)
        assertThat(MaskEdits.find(r, "a")!!.components[1].op).isEqualTo(MaskOp.SUBTRACT)
        assertThat(MaskEdits.find(r, "a")!!.components[1].invert).isTrue()
        r = MaskEdits.removeComponent(r, "a", 0)
        assertThat(MaskEdits.find(r, "a")!!.components.single()).isInstanceOf(MaskComponent.LuminanceRange::class.java)
        assertThat(MaskEdits.removeComponent(r, "a", 7)).isEqualTo(r)
    }

    @Test
    fun `invert with one component flips it`() {
        val r = Recipe(masks = listOf(Mask("a", components = listOf(MaskComponent.Linear()))))
        assertThat(MaskEdits.find(MaskEdits.invert(r, "a"), "a")!!.components.single().invert).isTrue()
    }

    @Test
    fun `invert of hard-edged combinations is exactly one minus the coverage, and twice is the same mask`() {
        val disc = MaskComponent.Radial(cx = 0.4f, cy = 0.5f, rx = 0.25f, ry = 0.2f, feather = 0f)
        val band = MaskComponent.Linear(x0 = 0f, y0 = 0.3f, x1 = 0f, y1 = 0.6f, feather = 0f)
        val corner = MaskComponent.Radial(cx = 0.8f, cy = 0.2f, rx = 0.2f, ry = 0.2f, feather = 0f)
        val combos = listOf(
            listOf(disc),
            listOf(disc, band.copy(op = MaskOp.ADD)),
            listOf(disc, band.copy(op = MaskOp.SUBTRACT)),
            listOf(disc, band.copy(op = MaskOp.INTERSECT)),
            listOf(disc, band.copy(op = MaskOp.INTERSECT, invert = true), corner.copy(op = MaskOp.ADD)),
            listOf(disc.copy(invert = true), corner.copy(op = MaskOp.SUBTRACT), band.copy(op = MaskOp.ADD)),
        )
        for (components in combos) {
            val r = Recipe(masks = listOf(Mask("a", components = components)))
            val once = MaskEdits.invert(r, "a")
            val twice = MaskEdits.invert(once, "a")
            val original = coverage(r)
            val inverted = coverage(once)
            for (i in original.indices) assertThat(inverted[i]).isEqualTo(1f - original[i])
            assertThat(coverage(twice).toList()).isEqualTo(original.toList())
        }
    }

    @Test
    fun `invert of soft overlaps stays within a quarter of the exact complement`() {
        val r = Recipe(
            masks = listOf(
                Mask(
                    "a",
                    components = listOf(
                        MaskComponent.Radial(cx = 0.4f, cy = 0.5f, rx = 0.3f, ry = 0.3f, feather = 80f),
                        MaskComponent.Linear(x0 = 0f, y0 = 0.2f, x1 = 0f, y1 = 0.8f, op = MaskOp.ADD),
                    ),
                ),
            ),
        )
        val original = coverage(r)
        val inverted = coverage(MaskEdits.invert(r, "a"))
        for (i in original.indices) assertThat(inverted[i]).isWithin(0.2501f).of(1f - original[i])
    }

    @Test
    fun `brush settings resize by steps within their range`() {
        val b = BrushSettings(size = 0.05f)
        assertThat(b.resized(1).size).isGreaterThan(0.05f)
        assertThat(b.resized(-1).size).isLessThan(0.05f)
        assertThat(b.resized(100).size).isEqualTo(BrushSettings.MAX_SIZE)
        assertThat(b.resized(-100).size).isEqualTo(BrushSettings.MIN_SIZE)
    }

    private fun coverage(recipe: Recipe): FloatArray {
        val w = 48
        val h = 32
        val plan = MaskPlan.of(recipe.masks.map { it.copy(adjustments = LocalAdjustments(exposure = 1f)) }, w, h)
        return MaskRaster.coverage(plan, MaskRaster.Region(0, 0, w, h), null).single()
    }
}
