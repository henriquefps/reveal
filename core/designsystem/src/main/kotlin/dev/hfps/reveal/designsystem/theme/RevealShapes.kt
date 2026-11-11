package dev.hfps.reveal.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/** hfps radii: base 0.75rem; cards (rounded-xl) 16dp, controls (rounded-md) 10dp, chips fully round. */
@Immutable
data class RevealShapes(
    val card: Shape = RoundedCornerShape(16.dp),
    val control: Shape = RoundedCornerShape(10.dp),
    val chip: Shape = CircleShape,
    val sheet: Shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
)

internal val LocalRevealShapes = staticCompositionLocalOf { RevealShapes() }

internal val MaterialShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(16.dp),
)
