package dev.hfps.reveal.library

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.EntryPointAccessors
import dev.hfps.reveal.debug.DebugAccess
import dev.hfps.reveal.feature.library.presets.PresetBatchWorker
import dev.hfps.reveal.pipeline.preset.ApplyContext
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetIds
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.preset.SmartPart
import dev.hfps.reveal.pipeline.preset.SmartRules
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.WbMode
import dev.hfps.reveal.pipeline.recipe.WhiteBalance
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The library's batch apply blends an absolute white balance from **each photo's own** as-shot value
 * (docs/adr/0019 "Strength" and "Batch"), for a plain preset and for a smart preset whose white-balance rule resets
 * to as shot. Runs on the public CC0 RAWs in `files/samples` (`scripts/fetch-samples.sh`, pushed as in CLAUDE.md):
 * it needs two of them shot at different white balances, and skips otherwise.
 */
@RunWith(AndroidJUnit4::class)
class PresetBatchWhiteBalanceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val access = EntryPointAccessors.fromApplication(context, DebugAccess::class.java)
    private val presets = access.presets()
    private lateinit var folder: SyntheticFolder
    private var groupId: Long? = null

    /** A preset that moves the white balance to 5000 K / +10 at 50 %: half way from each photo's as-shot value. */
    private val warm = PresetPatch.fromSections(
        Recipe(colour = Colour(WhiteBalance(TARGET_K, TARGET_TINT, WbMode.CUSTOM))),
        listOf(PresetSection.WHITE_BALANCE),
    )

    @Before
    fun setUp() {
        val samples = SAMPLES.mapNotNull { name -> File(context.filesDir, "samples/$name").takeIf { it.isFile } }
        assumeTrue("push at least two of $SAMPLES into files/samples (CLAUDE.md)", samples.size >= 2)
        folder = SyntheticFolder("preset-wb-${System.nanoTime()}")
        // A unique trailer per run (a TIFF-based RAW ignores bytes after its data): each run is a new photo.
        val trailer = UUID.randomUUID().toString().toByteArray()
        for (sample in samples) File(folder.dir, sample.name.uppercase()).writeBytes(sample.readBytes() + trailer)
        groupId = runBlocking { presets.createGroup("WB ${System.nanoTime()}") }
    }

    @After
    fun tearDown() {
        if (::folder.isInitialized) folder.delete()
        groupId?.let { runBlocking { presets.deleteGroup(it) } }
    }

    @Test
    fun aPlainPresetBlendsEachPhotoFromItsOwnAsShotWhiteBalance() {
        val uuid = runBlocking { presets.create("Warm ${System.nanoTime()}", warm, checkNotNull(groupId)) }
        val versions = indexed()
        val contexts = contexts(versions)
        run(uuid, versions)
        assertEachFromItsOwnAsShot(versions, contexts)
    }

    @Test
    fun aSmartPresetsAsShotRuleBlendsEachPhotoFromItsOwnAsShotWhiteBalance() {
        // The rule resets the white balance to as shot, then the style (kept in the file on purpose) moves it.
        val preset = Preset(
            id = PresetIds.newId(),
            name = "Smart warm ${System.nanoTime()}",
            patch = warm,
            group = "WB smart ${System.nanoTime()}",
            smart = SmartPart(
                SmartRules(whiteBalance = SmartRules.WhiteBalanceRule(SmartRules.WhiteBalanceRule.Mode.AS_SHOT))
                    .toJson(),
            ),
        )
        runBlocking { presets.importPreset(PresetFiles.encode(preset)) }
        try {
            val versions = indexed()
            val contexts = contexts(versions)
            // Each photo starts from a custom white balance the rule must reset to its own as shot.
            runBlocking {
                access.edits().applyEach(versions, HistoryAction.PRESET, "start") {
                    it.copy(colour = it.colour.copy(whiteBalance = WhiteBalance(START_K, 0f, WbMode.CUSTOM)))
                }
            }
            run(preset.id, versions)
            assertEachFromItsOwnAsShot(versions, contexts)
        } finally {
            runBlocking {
                presets.library().first().groups.firstOrNull { it.name == preset.group }
                    ?.rowId?.let { presets.deleteGroup(it) }
            }
        }
    }

    /** Indexes the folder and returns the first version of each RAW. */
    private fun indexed(): List<Long> {
        val (sourceId, _) = folder.addAndIndex()
        val db = access.database()
        return runBlocking {
            db.photoDao().indexedPhotos(sourceId).mapNotNull { db.photoDao().firstVersion(it.id) }
        }.also { assertThat(it.size).isAtLeast(2) }
    }

    /** Each photo's context, read from its header and checked against its decoded input (the editor's source). */
    private fun contexts(versions: List<Long>): Map<Long, ApplyContext> = runBlocking {
        val thumbnails = access.thumbnails()
        versions.associateWith { v ->
            val header = thumbnails.applyContext(v)
            val decoded = ApplyContext.of(checkNotNull(thumbnails.analysisInput(v)))
            assertThat(header).isEqualTo(decoded)
            assertThat(header.asShotTemperatureK).isNotNull()
            header
        }
    }.also { contexts ->
        val kelvins = contexts.values.map { it.asShotTemperatureK!! }
        assumeTrue("the samples need different as-shot white balances: $kelvins", kelvins.toSet().size >= 2)
    }

    private fun run(presetId: String, versions: List<Long>) {
        val work = PresetBatchWorker.enqueue(context, presetId, STRENGTH, versions)
        val manager = WorkManager.getInstance(context)
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (manager.getWorkInfoById(work).get()?.state?.isFinished != true) {
            check(System.currentTimeMillis() < deadline) { "batch did not finish" }
            Thread.sleep(POLL_MS)
        }
        assertThat(manager.getWorkInfoById(work).get()!!.state).isEqualTo(WorkInfo.State.SUCCEEDED)
    }

    private fun assertEachFromItsOwnAsShot(versions: List<Long>, contexts: Map<Long, ApplyContext>) = runBlocking {
        val results = versions.map { v ->
            val ctx = contexts.getValue(v)
            val expected = warm.apply(Recipe(), STRENGTH, ctx).colour.whiteBalance
            val wb = access.edits().recipe(v).colour.whiteBalance
            assertThat(wb.temperatureK!!).isWithin(1f).of(expected.temperatureK!!)
            assertThat(wb.tint!!).isWithin(0.01f).of(expected.tint!!)
            // Half way in mired between the photo's own as-shot kelvin and the preset's.
            val mired = 1e6f / ((1e6f / ctx.asShotTemperatureK!! + 1e6f / TARGET_K) / 2f)
            assertThat(wb.temperatureK!!).isWithin(1f).of(mired)
            wb.temperatureK!!
        }
        // Not the first photo's value for all of them (nor the preset's own, which a 50 % switch would give).
        assertThat(results.toSet().size).isAtLeast(2)
        assertThat(results).doesNotContain(TARGET_K)
    }

    private companion object {
        val SAMPLES = listOf("sony-zv-e10.arw", "nikon-d5600.nef", "google-pixel-4a.dng", "sony-a7c-iso2000.arw")
        const val TARGET_K = 5000f
        const val TARGET_TINT = 10f
        const val START_K = 9000f
        const val STRENGTH = 0.5f
        const val TIMEOUT_MS = 180_000L
        const val POLL_MS = 250L
    }
}
