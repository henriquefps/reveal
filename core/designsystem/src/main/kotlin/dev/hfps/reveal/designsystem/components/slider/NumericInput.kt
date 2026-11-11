package dev.hfps.reveal.designsystem.components.slider

import java.text.DecimalFormatSymbols
import java.util.Locale
import kotlin.math.abs

/**
 * Text of the numeric input of a slider: locale-aware decimal separator for editing (pt-BR types `0,7`, en `0.7`),
 * tolerant parsing (either separator, U+2212 or `-` as minus, a leading `+`, units and spaces ignored). Pure,
 * JVM-tested.
 */
object NumericInput {
    private const val MINUS_SIGN = '−'
    private const val EN_DASH = '–'

    /** [value] with [decimals] decimals and the [locale]'s decimal separator, `-` for negatives, no grouping. */
    fun format(value: Float, decimals: Int, locale: Locale): String {
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        val text = String.format(Locale.ROOT, "%.${decimals}f", abs(value)).replace('.', separator)
        val zero = text.all { it == '0' || it == separator }
        return if (value < 0f && !zero) "-$text" else text
    }

    /** The number typed in [text], or `null` when it is not a number. */
    fun parse(text: String, locale: Locale): Float? {
        val symbols = DecimalFormatSymbols.getInstance(locale)
        var t = text.trim()
            .replace(MINUS_SIGN, '-')
            .replace(EN_DASH, '-')
            .filter { it.isDigit() || it == '-' || it == '+' || it == '.' || it == ',' }
        if (t.isEmpty()) return null
        val hasDot = '.' in t
        val hasComma = ',' in t
        t = when {
            // Both present: the locale's grouping separator is noise, its decimal separator is the point.
            hasDot && hasComma -> t.replace(symbols.groupingSeparator.toString(), "")
                .replace(symbols.decimalSeparator, '.')

            hasComma -> t.replace(',', '.')

            else -> t
        }
        return t.toFloatOrNull()?.takeIf { it.isFinite() }
    }
}
