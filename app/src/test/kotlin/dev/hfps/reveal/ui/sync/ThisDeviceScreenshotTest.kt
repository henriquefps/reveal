package dev.hfps.reveal.ui.sync

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.settings.sync.PhotoServerContent
import dev.hfps.reveal.feature.settings.sync.SyncActions
import dev.hfps.reveal.feature.settings.sync.SyncUiState
import dev.hfps.reveal.feature.settings.sync.ThisDeviceActions
import dev.hfps.reveal.feature.settings.sync.ThisDeviceContent
import dev.hfps.reveal.feature.settings.sync.ThisDeviceState
import dev.hfps.reveal.sync.SyncStatus
import dev.hfps.reveal.sync.ThisDevice
import dev.hfps.reveal.sync.account.SyncAccount
import dev.hfps.reveal.sync.api.DeviceCandidate
import dev.hfps.reveal.sync.api.DeviceEntry
import dev.hfps.reveal.sync.api.PhysicalDevice
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** "This device" and the post-pairing question (Hoard ADR 0017, Reveal ADR 0027). Fictional names and models. */
internal object DeviceSamples {
    private val reveal = DeviceEntry("d1", "Galaxy S24 Ultra", "reveal", "android", "samsung", "SM-S928B")
    private val hoard = DeviceEntry("h1", "Galaxy S24 Ultra", "hoard", "android", "samsung", "SM-S928B")
    private val tablet = DeviceEntry("h2", "Galaxy Tab S9", "hoard", "android", "samsung", "SM-X910")

    val grouped = ThisDevice(
        supported = true,
        deviceId = "d1",
        name = "Galaxy S24 Ultra",
        app = "reveal",
        model = "samsung SM-S928B",
        features = listOf("culling"),
        physicalDevice = PhysicalDevice("h1", "Galaxy S24 Ultra", listOf(hoard, reveal)),
        candidates = listOf(DeviceCandidate("h2", "Galaxy Tab S9", "other", listOf(tablet))),
    )

    val alone = grouped.copy(
        physicalDevice = PhysicalDevice("d1", "Galaxy S24 Ultra", listOf(reveal)),
        candidates = listOf(
            DeviceCandidate("h1", "Galaxy S24 Ultra", "model", listOf(hoard)),
            DeviceCandidate("h2", "Galaxy Tab S9", "other", listOf(tablet)),
        ),
    )

    val olderServer =
        ThisDevice(supported = false, deviceId = "d1", name = "Galaxy S24 Ultra", app = null, model = null)

    val samePhone = listOf(DeviceCandidate("h1", "Galaxy S24 Ultra", "model", listOf(hoard)))
}

abstract class ThisDeviceScreenshotBase(private val device: String) {
    @get:Rule
    val composeRule = createComposeRule()

    protected fun shoot(name: String, content: @Composable () -> Unit) {
        composeRule.setContent(content)
        composeRule.onRoot().captureRoboImage("src/test/screenshots/sync/${device}_$name.png")
    }

    protected fun device(name: String, dark: Boolean, state: ThisDeviceState) = shoot(name) {
        RevealTheme(darkTheme = dark) { ThisDeviceContent(state, ThisDeviceActions()) }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w390dp-h844dp-xhdpi")
class PhoneThisDeviceScreenshotTest : ThisDeviceScreenshotBase("phone") {
    @Test fun groupedDark() = device("this_device_dark", dark = true, ThisDeviceState.Loaded(DeviceSamples.grouped))

    @Test fun aloneLight() = device("this_device_light", dark = false, ThisDeviceState.Loaded(DeviceSamples.alone))

    @Test fun olderServer() =
        device("this_device_older_server_dark", dark = true, ThisDeviceState.Loaded(DeviceSamples.olderServer))

    @Test fun unreachable() = device("this_device_unreachable_dark", dark = true, ThisDeviceState.Unreachable)

    @Test fun samePhoneQuestion() = shoot("same_phone_dark") {
        val now = 1_762_851_600_000L
        RevealTheme(darkTheme = true) {
            PhotoServerContent(
                SyncUiState(
                    account = SyncAccount("https://photos.example.org", "858e1b9d", "Home server", "d1", now),
                    status = SyncStatus(lastSyncAt = null),
                    samePhone = DeviceSamples.samePhone,
                ),
                SyncActions(),
                now = now,
            )
        }
    }
}

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1280dp-h800dp-hdpi")
class TabletThisDeviceScreenshotTest : ThisDeviceScreenshotBase("tablet") {
    @Test fun groupedDark() = device("this_device_dark", dark = true, ThisDeviceState.Loaded(DeviceSamples.grouped))
}
