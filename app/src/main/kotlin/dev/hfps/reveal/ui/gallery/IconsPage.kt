package dev.hfps.reveal.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import dev.hfps.reveal.R
import dev.hfps.reveal.designsystem.icons.PhosphorFill
import dev.hfps.reveal.designsystem.icons.PhosphorRegular
import dev.hfps.reveal.designsystem.theme.RevealTheme

private val RegularIcons: List<ImageVector> = with(PhosphorRegular) {
    listOf(
        ArrowLeft, ArrowUUpLeft, ArrowUUpRight, CircleHalf, DotsThree, Export, UploadSimple, SlidersHorizontal, Crop,
        Sun, Palette, Sparkle, Aperture, MagicWand, Drop, Images, FolderPlus, Gear, Star, Flag, Check, X, Plus, Minus,
        CaretUp, CaretDown, CaretLeft, CaretRight, Eye, EyeSlash, ChartBar, Info,
    )
}

private val FillIcons: List<ImageVector> = with(PhosphorFill) {
    listOf(
        ArrowLeft, ArrowUUpLeft, ArrowUUpRight, CircleHalf, DotsThree, Export, UploadSimple, SlidersHorizontal, Crop,
        Sun, Palette, Sparkle, Aperture, MagicWand, Drop, Images, FolderPlus, Gear, Star, Flag, Check, X, Plus, Minus,
        CaretUp, CaretDown, CaretLeft, CaretRight, Eye, EyeSlash, ChartBar, Info,
    )
}

@Composable
@Suppress("MultipleEmitters", "ktlint:compose:multiple-emitters-check")
internal fun IconsPage() {
    Section(R.string.gallery_section_icons_regular) { IconGrid(RegularIcons, RevealTheme.colors.foreground) }
    Section(R.string.gallery_section_icons_fill) { IconGrid(FillIcons, RevealTheme.colors.primary) }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconGrid(icons: List<ImageVector>, tint: Color) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icons.forEach {
            Icon(imageVector = it, contentDescription = null, modifier = Modifier.size(28.dp), tint = tint)
        }
    }
}
