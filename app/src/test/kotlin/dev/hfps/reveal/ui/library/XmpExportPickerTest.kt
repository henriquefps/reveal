package dev.hfps.reveal.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.app.ActivityOptionsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.library.XmpDestination
import dev.hfps.reveal.data.library.XmpExportScope
import dev.hfps.reveal.designsystem.theme.RevealTheme
import dev.hfps.reveal.feature.library.LibraryTags
import dev.hfps.reveal.feature.library.XmpExportHost
import dev.hfps.reveal.feature.library.XmpExportSheetState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Export XMP's folder picker survives the activity being recreated while the system picker is open (rotation): the
 * folder the user then picks is still the one to write next to, not a "Choose a folder" export.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class XmpExportPickerTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val launched = mutableListOf<Int>()
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            launched += requestCode
        }
    }
    private val owner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry = registry
    }

    @Test
    fun aFolderPickedAfterRecreationIsStillTheOneToWriteNextTo() {
        val runs = mutableListOf<XmpDestination>()
        val sheet = XmpExportSheetState(listOf(1L, 2L), XmpExportScope(2, false, listOf(7L to "Shoot")))
        val restoration = StateRestorationTester(composeRule)
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                RevealTheme(darkTheme = true) { XmpExportHost(sheet, onRun = { runs += it }, onDismiss = {}) }
            }
        }
        composeRule.onNodeWithTag(LibraryTags.XMP_NEXT_PICK).performClick()
        composeRule.runOnIdle { assertThat(launched).hasSize(1) }

        restoration.emulateSavedInstanceStateRestore()
        val picked = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADCIM")
        composeRule.runOnIdle {
            registry.dispatchResult(launched.single(), Activity.RESULT_OK, Intent().setData(picked))
        }
        composeRule.runOnIdle {
            assertThat(runs).containsExactly(XmpDestination.NextToPhotos(mapOf(7L to picked.toString())))
        }
    }
}
