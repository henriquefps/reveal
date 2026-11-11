package dev.hfps.reveal.feature.editor.edit

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.ColourToolControls
import dev.hfps.reveal.pipeline.recipe.ControlId
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeRanges
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import org.junit.Test

class RecipeControlsTest {
    private val asShot = AsShot(5123f, 7f)

    @Test
    fun everyControlRoundTripsThroughTheRecipe() {
        // The curve splits also stay between their neighbours (ColourToolControls), tested in :core:pipeline.
        for (range in RecipeRanges.all.filter { it.id !in ColourToolControls.splits }) {
            val inside = range.min + (range.max - range.min) * 0.3f
            val r = RecipeControls.set(Recipe.DEFAULT, range.id, inside)
            assertThat(RecipeControls.get(r, range.id, asShot)).isWithin(1e-4f).of(inside)
        }
    }

    @Test
    fun valuesAreClampedToTheirRange() {
        val r = RecipeControls.set(Recipe.DEFAULT, ControlId.EXPOSURE, 9f)
        assertThat(r.light.exposureEv).isEqualTo(5f)
        val t = RecipeControls.set(Recipe.DEFAULT, ControlId.TEMPERATURE, 100f)
        assertThat(t.colour.whiteBalance.temperatureK).isEqualTo(2000f)
    }

    @Test
    fun absoluteWhiteBalanceShowsAsShotUntilSetAndTheModeFollows() {
        assertThat(RecipeControls.get(Recipe.DEFAULT, ControlId.TEMPERATURE, asShot)).isEqualTo(5123f)
        assertThat(RecipeControls.get(Recipe.DEFAULT, ControlId.TINT, asShot)).isEqualTo(7f)
        val warm = RecipeControls.set(Recipe.DEFAULT, ControlId.TEMPERATURE, 6000f)
        assertThat(warm.colour.whiteBalance.mode).isEqualTo(WbMode.CUSTOM)
        assertThat(warm.colour.whiteBalance.tint).isNull()
        val back = RecipeControls.reset(warm, ControlId.TEMPERATURE)
        assertThat(back.colour.whiteBalance.temperatureK).isNull()
        assertThat(back.colour.whiteBalance.mode).isEqualTo(WbMode.AS_SHOT)
        assertThat(back.isDefault()).isTrue()
    }

    @Test
    fun resettingOneOfTwoKeepsCustom() {
        val both = RecipeControls.set(
            RecipeControls.set(Recipe.DEFAULT, ControlId.TEMPERATURE, 6000f),
            ControlId.TINT,
            10f,
        )
        val one = RecipeControls.reset(both, ControlId.TINT)
        assertThat(one.colour.whiteBalance.mode).isEqualTo(WbMode.CUSTOM)
        assertThat(RecipeControls.isNeutral(one, ControlId.TINT)).isTrue()
        assertThat(RecipeControls.isNeutral(one, ControlId.TEMPERATURE)).isFalse()
    }

    @Test
    fun resetPutsTheNeutralValueBack() {
        val r = RecipeControls.set(Recipe.DEFAULT, ControlId.SHADOWS, 40f)
        assertThat(RecipeControls.reset(r, ControlId.SHADOWS).isDefault()).isTrue()
        val amount = RecipeControls.set(Recipe.DEFAULT, ControlId.PROFILE_AMOUNT, 150f)
        assertThat(RecipeControls.reset(amount, ControlId.PROFILE_AMOUNT).profile.amountPercent).isEqualTo(100f)
    }

    @Test
    fun wbModesWriteTheirValues() {
        val daylight = RecipeControls.withWbMode(Recipe.DEFAULT, WbMode.DAYLIGHT, null)
        assertThat(daylight.colour.whiteBalance.temperatureK).isEqualTo(5500f)
        assertThat(daylight.colour.whiteBalance.tint).isEqualTo(0f)
        assertThat(daylight.colour.whiteBalance.mode).isEqualTo(WbMode.DAYLIGHT)
        val auto = RecipeControls.withWbMode(daylight, WbMode.AUTO, WhiteBalance(4800f, -3f, WbMode.AUTO))
        assertThat(auto.colour.whiteBalance.temperatureK).isEqualTo(4800f)
        assertThat(auto.colour.whiteBalance.mode).isEqualTo(WbMode.AUTO)
        assertThat(RecipeControls.withWbMode(daylight, WbMode.AUTO, null)).isEqualTo(daylight)
        val asShotAgain = RecipeControls.withWbMode(auto, WbMode.AS_SHOT, null)
        assertThat(asShotAgain.isDefault()).isTrue()
    }

    @Test
    fun theLookKeepsTheAmount() {
        val r = RecipeControls.set(Recipe.DEFAULT, ControlId.PROFILE_AMOUNT, 140f)
        val vivid = RecipeControls.withLook(r, BaseLook.VIVID)
        assertThat(vivid.profile.base).isEqualTo(BaseLook.VIVID)
        assertThat(vivid.profile.amountPercent).isEqualTo(140f)
    }
}
