package dev.hfps.reveal.designsystem.components.slider

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Gradient tracks of the colour sliders: the direction the photo moves when the thumb goes that way (content
 * colours, not chrome, so they live here rather than in the theme tokens). Muted so they read on the dark panel
 * without glowing.
 */
object SliderGradients {
    /** Temperature: cooler (blue) to warmer (yellow). */
    val Temperature: Brush = Brush.horizontalGradient(listOf(Color(0xFF4F7FD9), Color(0xFFD9D9D9), Color(0xFFE0B341)))

    /** Tint: green to magenta. */
    val Tint: Brush = Brush.horizontalGradient(listOf(Color(0xFF4FB55A), Color(0xFFD9D9D9), Color(0xFFC452B8)))
}
