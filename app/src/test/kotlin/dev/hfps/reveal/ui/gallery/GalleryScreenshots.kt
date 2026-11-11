package dev.hfps.reveal.ui.gallery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.designsystem.theme.ThemeMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Design-system gallery goldens under `app/src/test/screenshots/gallery/`.
 * Record with `./gradlew :app:recordRoborazziDebug`; `./gradlew qa` / `verifyRoborazziDebug` compare against them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class PhoneGalleryScreenshotTest : GalleryScreenshotBase("phone") {
    @Test fun foundationsLight() = shoot(GalleryPage.Foundations, dark = false)

    @Test fun foundationsDark() = shoot(GalleryPage.Foundations, dark = true)

    @Test fun iconsLight() = shoot(GalleryPage.Icons, dark = false)

    @Test fun iconsDark() = shoot(GalleryPage.Icons, dark = true)

    @Test fun controlsLight() = shoot(GalleryPage.Controls, dark = false)

    @Test fun controlsDark() = shoot(GalleryPage.Controls, dark = true)

    @Test fun editor() = shoot(GalleryPage.Editor, dark = true)

    @Test fun foundationsLightFontScale200() = shoot(GalleryPage.Foundations, dark = false, fontScale = 2f)

    @Test fun controlsDarkFontScale200() = shoot(GalleryPage.Controls, dark = true, fontScale = 2f)

    @Test fun editorFontScale200() = shoot(GalleryPage.Editor, dark = true, fontScale = 2f)
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class TabletGalleryScreenshotTest : GalleryScreenshotBase("tablet") {
    @Test fun foundationsLight() = shoot(GalleryPage.Foundations, dark = false)

    @Test fun foundationsDark() = shoot(GalleryPage.Foundations, dark = true)

    @Test fun controlsLight() = shoot(GalleryPage.Controls, dark = false)

    @Test fun controlsDark() = shoot(GalleryPage.Controls, dark = true)

    @Test fun editor() = shoot(GalleryPage.Editor, dark = true)
}

abstract class GalleryScreenshotBase(private val size: String) {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(page: GalleryPage, dark: Boolean, fontScale: Float = 1f) {
        composeRule.setContent {
            RevealTheme(darkTheme = dark) {
                Scaled(fontScale) {
                    DesignSystemGallery(
                        themeMode = if (dark) ThemeMode.Dark else ThemeMode.Light,
                        onThemeModeChange = {},
                        initialPage = page,
                    )
                }
            }
        }
        val theme = if (page == GalleryPage.Editor) "editor" else if (dark) "dark" else "light"
        val scale = if (fontScale == 1f) "" else "_font${(fontScale * 100).toInt()}"
        composeRule.onRoot().captureRoboImage("src/test/screenshots/gallery/${size}_${page.name.lowercase()}_$theme$scale.png")
    }
}

@Composable
private fun Scaled(fontScale: Float, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), content = content)
}
