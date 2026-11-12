package dev.hfps.reveal.editor

import android.content.Intent
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.MainActivity
import dev.hfps.reveal.data.preset.PresetDownloader
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.editor.EditorTags
import dev.hfps.reveal.feature.editor.tools.ToolId
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetIds
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Importing presets from a link (docs/adr/0019 "Import from a link"): a link shared to Reveal asks first (nothing is
 * fetched before the confirmation), then downloads from a local https server (a test certificate) and lands a pack
 * on trial; a link pasted in the presets panel imports a preset; cancelling a slow download imports nothing; a
 * cleartext link is refused in the field. Locale-independent (tags only).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class PresetLinkFlowTest {
    @get:org.junit.Rule
    val compose = createEmptyComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val presets = EntryPointAccessors.fromApplication(context, DebugAccess::class.java).presets()

    private val localhost = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(localhost).build().sslSocketFactory())
    }

    @Before
    fun setUp() {
        server.start()
        val trust = HandshakeCertificates.Builder().addTrustedCertificate(localhost.certificate).build()
        PresetDownloader.testBaseClient = OkHttpClient.Builder()
            .sslSocketFactory(trust.sslSocketFactory(), trust.trustManager)
            .build()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun link(path: String) = server.url(path).toString().replace("127.0.0.1", "localhost")

    @Test
    fun aSharedLinkIsConfirmedThenItsPackLandsOnTrial() {
        val pack = pack()
        server.enqueue(MockResponse.Builder().body(Buffer().write(packBytes(pack))).build())
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Try my pack: ${link("/golden.revealpack")}")
            .setClass(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ActivityScenario.launch<MainActivity>(intent).use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_LINK_HOST), TIMEOUT_MS)
                // Nothing is fetched before the user confirms.
                assertThat(server.requestCount).isEqualTo(0)
                click(EditorTags.PRESET_LINK_CONFIRM)
                compose.waitUntil(TIMEOUT_MS) { trial(pack.id) }
                compose.waitUntil(TIMEOUT_MS) {
                    compose.onAllNodes(hasTestTag(EditorTags.PRESET_LINK_HOST)).fetchSemanticsNodes().isEmpty()
                }
            }
            assertThat(server.requestCount).isEqualTo(1)
        } finally {
            removePack(pack.id)
        }
    }

    @Test
    fun aPastedLinkImportsAPresetAndCleartextIsRefused() {
        val group = "Linked ${System.nanoTime()}"
        val preset = Preset(PresetIds.newId(), "Linked", contrast, group = group)
        server.enqueue(MockResponse.Builder().body(Buffer().write(PresetFiles.encode(preset))).build())
        val photo = TestPhotos.insert("reveal-link-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            TestPhotos.launchView(photo, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                openLinkField()
                compose.onNodeWithTag(
                    EditorTags.PRESET_LINK_FIELD,
                ).performTextReplacement("http://example.com/a.revealpreset")
                click(EditorTags.PRESET_LINK_CONTINUE)
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_LINK_ERROR), TIMEOUT_MS)
                assertThat(server.requestCount).isEqualTo(0)

                type(link("/soft.revealpreset"))
                click(EditorTags.PRESET_LINK_CONTINUE)
                click(EditorTags.PRESET_LINK_CONFIRM)
                compose.waitUntil(TIMEOUT_MS) {
                    runBlocking { presets.library().first() }.groups.any { g -> g.presets.any { it.uuid == preset.id } }
                }
            }
        } finally {
            TestPhotos.delete(photo)
            runBlocking {
                presets.library().first().groups.firstOrNull {
                    it.name == group
                }?.rowId?.let { presets.deleteGroup(it) }
            }
        }
    }

    @Test
    fun cancellingADownloadImportsNothing() {
        val pack = pack()
        val bytes = packBytes(pack)
        // About 30 s for the whole file: cancelled long before it ends.
        server.enqueue(
            MockResponse.Builder().body(Buffer().write(bytes))
                .throttleBody((bytes.size / SLOW_SECONDS).toLong().coerceAtLeast(1), 1, TimeUnit.SECONDS).build(),
        )
        val photo = TestPhotos.insert("reveal-link-${System.nanoTime()}.jpg", "image/jpeg", TestPhotos.syntheticJpeg())
        try {
            TestPhotos.launchView(photo, "image/jpeg").use {
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.DEVELOPED), TIMEOUT_MS)
                openLinkField()
                type(link("/slow.revealpack"))
                click(EditorTags.PRESET_LINK_CONTINUE)
                click(EditorTags.PRESET_LINK_CONFIRM)
                compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_LINK_PROGRESS), TIMEOUT_MS)
                click(EditorTags.PRESET_LINK_CANCEL)
                compose.waitUntil(TIMEOUT_MS) { gone(EditorTags.PRESET_LINK_PROGRESS) }
                Thread.sleep(SETTLE_MS)
                assertThat(trial(pack.id)).isFalse()
            }
        } finally {
            TestPhotos.delete(photo)
            removePack(pack.id)
        }
    }

    private val contrast = PresetPatch.fromSections(
        Recipe(light = Light(contrast = 30f)),
        listOf(PresetSection.CONTRAST),
    )

    private fun type(text: String) = compose.onNodeWithTag(EditorTags.PRESET_LINK_FIELD).performTextReplacement(text)

    private fun gone(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()

    private fun openLinkField() {
        click(EditorTags.tool(ToolId.PRESETS))
        click(EditorTags.PRESET_MENU)
        click(EditorTags.PRESET_IMPORT_LINK)
        compose.waitUntilAtLeastOneExists(hasTestTag(EditorTags.PRESET_LINK_FIELD), TIMEOUT_MS)
    }

    private fun trial(packId: String): Boolean =
        runBlocking { presets.library().first() }.trialPacks.any { it.uuid == packId }

    private fun removePack(packId: String) = runBlocking {
        presets.library().first().groups.firstOrNull { it.pack?.uuid == packId }?.pack?.let {
            presets.removePack(it.rowId)
        }
    }

    private fun click(tag: String) {
        compose.waitUntilAtLeastOneExists(hasTestTag(tag), TIMEOUT_MS)
        compose.onNodeWithTag(tag).apply { runCatching { performScrollTo() } }.performClick()
        compose.waitForIdle()
    }

    private fun pack(): PresetPack {
        val group = PackGroup(PresetIds.newId(), "Linked group")
        val list = (1..2).map { Preset(PresetIds.newId(), "Linked $it", contrast, group = group.name) }
        return PresetPack(
            id = PresetIds.newId(), name = "Linked pack", author = "Ana Example", version = 1,
            licenceName = "CC0", licenceText = "No rights reserved.", link = "https://example.com/pack",
            groups = listOf(group), presets = list, groupOf = list.associate { it.id to group.id }, cover = null,
        )
    }

    private fun packBytes(pack: PresetPack): ByteArray =
        ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()

    private companion object {
        const val TIMEOUT_MS = 30_000L
        const val SETTLE_MS = 1_000L
        const val SLOW_SECONDS = 30
    }
}
