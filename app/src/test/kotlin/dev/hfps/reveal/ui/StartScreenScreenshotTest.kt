package dev.hfps.reveal.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RobolectricDeviceQualifiers
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden image: `app/src/test/screenshots/start_screen.png`.
 * Record with `./gradlew :app:recordRoborazziDebug`; `./gradlew qa` / `verifyRoborazziDebug` compare against it.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = RobolectricDeviceQualifiers.Pixel7)
class StartScreenScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun startScreen() {
        composeRule.setContent {
            MaterialTheme { StartScreen() }
        }

        composeRule.onRoot().captureRoboImage("src/test/screenshots/start_screen.png")
    }
}
