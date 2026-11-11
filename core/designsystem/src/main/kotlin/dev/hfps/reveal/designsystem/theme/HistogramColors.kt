package dev.hfps.reveal.designsystem.theme

import androidx.compose.ui.graphics.Color

/**
 * Content colours of the editor's data displays (histogram channels), not chrome: they encode the photo's own red,
 * green and blue, so they are fixed hues, softened to sit on the dark panel. Luminance uses the editor text grey.
 */
object HistogramColors {
    val Red = Color(0xFFE5484D)
    val Green = Color(0xFF46A758)
    val Blue = Color(0xFF3E83F0)
    val Luma = EditorNeutrals.TextMuted
}
