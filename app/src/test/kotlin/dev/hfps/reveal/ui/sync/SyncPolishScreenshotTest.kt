package dev.hfps.reveal.ui.sync

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.data.storage.StorageKind
import dev.hfps.reveal.data.storage.StorageUsage
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.ConflictChoice
import dev.hfps.reveal.feature.library.ConflictContent
import dev.hfps.reveal.feature.settings.storage.StorageContent
import dev.hfps.reveal.feature.settings.storage.StorageUiState
import dev.hfps.reveal.sync.engine.ConflictPair
import dev.hfps.reveal.sync.engine.ConflictSide
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** FIX/W goldens: the conflict chooser and Settings -> Storage. Fictional device names, synthetic pictures. */
internal object PolishSamples {
    private const val NOW = 1_762_851_600_000L

    val conflict = ConflictChoice(
        name = "DSC07834.ARW",
        pair = ConflictPair(
            photoId = 1,
            main = ConflictSide(10, device = "Tablet", editedAt = NOW - 3_600_000, editTime = 1, copy = false),
            copy = ConflictSide(11, device = "", editedAt = NOW - 3_500_000, editTime = 2, copy = true),
        ),
    )

    /** Two differently graded versions of one portrait picture. */
    fun picture(side: ConflictSide): ImageBitmap {
        val bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888)
        val (a, b) = if (side.copy) {
            0xFFE0B080.toInt() to 0xFF402818.toInt()
        } else {
            0xFF90B8E0.toInt() to
                0xFF182840.toInt()
        }
        Canvas(bitmap).drawPaint(
            Paint().apply {
                shader = LinearGradient(0f, 0f, 64f, 96f, a, b, Shader.TileMode.CLAMP)
            },
        )
        return bitmap.asImageBitmap()
    }

    private const val MB = 1_000_000L

    val storage = StorageUiState(
        usage = StorageUsage(
            bytes = mapOf(
                StorageKind.THUMBNAILS to 182 * MB,
                StorageKind.SERVER_FILES to 1_240 * MB,
                StorageKind.HANDOFF_PREVIEWS to 96 * MB,
                StorageKind.OTHER to 14 * MB,
                StorageKind.LIBRARY to 38 * MB,
            ),
            keptPreviews = 41 * MB,
        ),
    )
}

abstract class PolishScreenshotBase(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/sync/${device}_$name.png")
    }

    /** The chooser as its sheet shows it (a bottom sheet on phones, a centred dialog from 840 dp). */
    protected fun chooser(name: String, dark: Boolean, dialog: Boolean) = shoot(name) {
        RevealTheme(darkTheme = dark) {
            Box(
                Modifier.fillMaxSize().background(RevealTheme.colors.background),
                if (dialog) Alignment.Center else Alignment.BottomCenter,
            ) {
                Surface(
                    color = RevealTheme.colors.card,
                    shape = RevealTheme.shapes.sheet,
                    modifier = if (dialog) Modifier.widthIn(max = 560.dp).padding(24.dp) else Modifier,
                ) {
                    ConflictContent(
                        PolishSamples.conflict,
                        thumbnail = { PolishSamples.picture(it) },
                        onKeep = {},
                        modifier = Modifier.padding(top = if (dialog) 16.dp else 24.dp),
                    )
                }
            }
        }
    }

    protected fun storage(name: String, dark: Boolean, state: StorageUiState = PolishSamples.storage) = shoot(name) {
        RevealTheme(darkTheme = dark) { StorageContent(state, onBack = {}, onMaxBytes = {}, onClear = {}) }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class PhonePolishScreenshotTest : PolishScreenshotBase("phone") {
    @Test fun conflictChooserDark() = chooser("conflict_chooser_dark", dark = true, dialog = false)

    @Test fun conflictChooserLight() = chooser("conflict_chooser_light", dark = false, dialog = false)

    @Test fun storageLight() = storage("storage_light", dark = false)

    @Test fun storageDark() = storage("storage_dark", dark = true)

    @Test fun storageFreed() = storage(
        "storage_freed_dark",
        dark = true,
        PolishSamples.storage.copy(freed = 1_336_000_000),
    )
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class TabletPolishScreenshotTest : PolishScreenshotBase("tablet") {
    @Test fun conflictChooserDark() = chooser("conflict_chooser_dark", dark = true, dialog = true)

    @Test fun storageDark() = storage("storage_dark", dark = true)
}
