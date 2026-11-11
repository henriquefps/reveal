package dev.hfps.reveal.designsystem.components.slider

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.Test

class NumericInputTest {
    private val ptBr = Locale.forLanguageTag("pt-BR")

    @Test
    fun formatUsesTheLocaleSeparatorAndAPlainMinus() {
        assertThat(NumericInput.format(0.7f, 2, Locale.US)).isEqualTo("0.70")
        assertThat(NumericInput.format(-0.7f, 2, ptBr)).isEqualTo("-0,70")
        assertThat(NumericInput.format(-0.001f, 2, ptBr)).isEqualTo("0,00")
        assertThat(NumericInput.format(5600f, 0, ptBr)).isEqualTo("5600")
    }

    @Test
    fun parseAcceptsEitherSeparatorSignsAndUnits() {
        assertThat(NumericInput.parse("0,7", ptBr)).isWithin(1e-6f).of(0.7f)
        assertThat(NumericInput.parse("0.7", ptBr)).isWithin(1e-6f).of(0.7f)
        assertThat(NumericInput.parse("0,7", Locale.US)).isWithin(1e-6f).of(0.7f)
        assertThat(NumericInput.parse("−12", Locale.US)).isEqualTo(-12f)
        assertThat(NumericInput.parse("+1.25", Locale.US)).isEqualTo(1.25f)
        assertThat(NumericInput.parse(" 5600 K", Locale.US)).isEqualTo(5600f)
        assertThat(NumericInput.parse("1.234,5", ptBr)).isEqualTo(1234.5f)
        assertThat(NumericInput.parse("1,234.5", Locale.US)).isEqualTo(1234.5f)
    }

    @Test
    fun parseRejectsWhatIsNotANumber() {
        assertThat(NumericInput.parse("", Locale.US)).isNull()
        assertThat(NumericInput.parse("abc", Locale.US)).isNull()
        assertThat(NumericInput.parse("-", Locale.US)).isNull()
        assertThat(NumericInput.parse("1.2.3", Locale.US)).isNull()
    }
}
