package dev.hfps.reveal.pipeline.recipe

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RecipeDiffTest {
    private val base = Recipe()

    @Test
    fun `identical recipes have no steps`() {
        assertThat(RecipeDiff.between(base, base.copy())).isEmpty()
    }

    @Test
    fun `exposure is signed with two decimals`() {
        assertThat(RecipeDiff.between(base, base.withExposure(0.7f)))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "+0.70"))
        assertThat(RecipeDiff.between(base, base.withExposure(-1.25f)))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "−1.25"))
    }

    @Test
    fun `sub-epsilon changes are not steps and a returning zero has no sign`() {
        assertThat(RecipeDiff.between(base, base.withExposure(0.00001f))).isEmpty()
        assertThat(RecipeDiff.between(base.withExposure(0.5f), base))
            .containsExactly(HistoryStep(HistoryField.EXPOSURE, "0.00"))
    }

    @Test
    fun `temperature and tint produce their own steps, null means as shot`() {
        val warm = base.withWb(WhiteBalance(5600f, 10f))

        assertThat(RecipeDiff.between(base, warm)).containsExactly(
            HistoryStep(HistoryField.TEMPERATURE, "5600 K"),
            HistoryStep(HistoryField.TINT, "+10"),
        ).inOrder()
        assertThat(RecipeDiff.between(warm, base)).containsExactly(
            HistoryStep(HistoryField.TEMPERATURE, null),
            HistoryStep(HistoryField.TINT, null),
        ).inOrder()
    }

    @Test
    fun `steps follow pipeline order`() {
        val after = base.withExposure(1f).withWb(WhiteBalance(4000f, null))

        assertThat(RecipeDiff.between(base, after).map { it.field })
            .containsExactly(HistoryField.EXPOSURE, HistoryField.TEMPERATURE).inOrder()
    }

    @Test
    fun `every history field has a string resource in both languages`() {
        val english = java.io.File("src/main/res/values/strings.xml").readText()
        val portuguese = java.io.File("src/main/res/values-pt-rBR/strings.xml").readText()

        for (field in HistoryField.entries) {
            val name = "history_${field.name.lowercase()}"
            assertThat(english).contains("name=\"$name\"")
            assertThat(portuguese).contains("name=\"$name\"")
        }
    }

    private fun Recipe.withExposure(ev: Float) = copy(light = Light(ev))

    private fun Recipe.withWb(wb: WhiteBalance) = copy(colour = Colour(wb))
}
