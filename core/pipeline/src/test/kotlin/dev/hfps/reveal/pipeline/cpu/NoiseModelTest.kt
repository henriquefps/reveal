@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.cpu

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.InputKind
import dev.hfps.reveal.pipeline.NoiseInfo
import kotlin.math.sqrt
import org.junit.Test

/** The noise model (docs/adr/0012, "Noise model"): sqrt(ISO) and pixel pitch, documented defaults, clamps. */
class NoiseModelTest {
    private val raw = InputKind.SCENE_REFERRED

    @Test
    fun `the reference capture is factor 1 and noise grows as the square root of ISO`() {
        assertThat(NoiseModel.factor(raw, NoiseInfo(5000, "SONY", "ILCE-7C"))).isWithin(1e-6f).of(1f)
        val low = NoiseModel.factor(raw, NoiseInfo(200, "SONY", "ZV-E10"))
        val high = NoiseModel.factor(raw, NoiseInfo(12800, "SONY", "ZV-E10"))
        assertThat(low).isWithin(1e-4f).of((sqrt(200.0 / 5000.0) * 5.9 / 3.9).toFloat())
        assertThat(high / low).isWithin(1e-4f).of(8f)
        // A smaller pixel is noisier at the same ISO.
        assertThat(NoiseModel.factor(raw, NoiseInfo(3200, "SONY", "ZV-E10")))
            .isGreaterThan(NoiseModel.factor(raw, NoiseInfo(3200, "SONY", "ILCE-7C")))
    }

    @Test
    fun `defaults when the ISO or the camera is unknown`() {
        assertThat(NoiseModel.factor(raw, null)).isEqualTo(1f)
        assertThat(NoiseModel.factor(raw, NoiseInfo(null, "SONY", "ZV-E10"))).isEqualTo(1f)
        assertThat(NoiseModel.factor(InputKind.DISPLAY_REFERRED, NoiseInfo(null, "Google", "Pixel 8")))
            .isEqualTo(NoiseModel.DISPLAY_REFERRED_FACTOR)
        assertThat(NoiseModel.pitch("Some Unknown Camera")).isEqualTo(NoiseModel.DEFAULT_PITCH_UM)
        assertThat(NoiseModel.pitch(null)).isEqualTo(NoiseModel.DEFAULT_PITCH_UM)
    }

    @Test
    fun `model names are matched with or without the maker`() {
        assertThat(NoiseModel.pitch("NIKON D5600")).isEqualTo(3.9)
        assertThat(NoiseModel.pitch("Canon EOS R6")).isEqualTo(6.6)
        assertThat(NoiseModel.pitch("zv-e10")).isEqualTo(3.9)
    }

    @Test
    fun `the factor is clamped`() {
        assertThat(NoiseModel.factor(raw, NoiseInfo(25, "SONY", "ILCE-7SM3"))).isEqualTo(NoiseModel.MIN_FACTOR)
        assertThat(NoiseModel.factor(raw, NoiseInfo(409600, "SONY", "ZV-E10"))).isEqualTo(NoiseModel.MAX_FACTOR)
    }
}
