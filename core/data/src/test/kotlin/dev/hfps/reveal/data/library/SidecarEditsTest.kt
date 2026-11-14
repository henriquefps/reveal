package dev.hfps.reveal.data.library

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.FileDocumentTree
import dev.hfps.reveal.data.db.PhotoQuerySql
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.index.IndexReport
import dev.hfps.reveal.data.index.LibraryIndexer
import dev.hfps.reveal.data.model.PhotoQuery
import dev.hfps.reveal.data.model.PickFlag
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.data.synthetic.SyntheticExif
import dev.hfps.reveal.data.synthetic.SyntheticShoot
import dev.hfps.reveal.data.xmp.DevelopState
import dev.hfps.reveal.data.xmp.PhotoFrame
import dev.hfps.reveal.data.xmp.SidecarRecipe
import dev.hfps.reveal.data.xmp.XmpDevelop
import dev.hfps.reveal.data.xmp.XmpDom
import dev.hfps.reveal.data.xmp.XmpFields
import dev.hfps.reveal.data.xmp.XmpReader
import dev.hfps.reveal.data.xmp.XmpWriter
import dev.hfps.reveal.pipeline.recipe.Colour
import dev.hfps.reveal.pipeline.recipe.CropRect
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Geometry
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.HistoryField
import dev.hfps.reveal.pipeline.recipe.HistoryStep
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeDiff
import dev.hfps.reveal.pipeline.recipe.Treatment
import dev.hfps.reveal.pipeline.recipe.WbMode
import java.io.File
import java.time.OffsetDateTime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** ADR 0026: edits through sidecars, with the real indexer, history and writer on a folder of files. */
@RunWith(AndroidJUnit4::class)
class SidecarEditsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var root: File
    private lateinit var edits: EditRepository
    private var clock = 1_778_000_000_000L
    private var sourceId = 0L
    private val writeOn = MutableStateFlow(true)
    private var readOn = true

    @Before
    fun setUp() {
        db =
            Room.inMemoryDatabaseBuilder(
                ApplicationProvider.getApplicationContext<Context>(),
                RevealDatabase::class.java,
            )
                .allowMainThreadQueries()
                .build()
        root = temp.newFolder("shoot")
        edits = EditRepository(db) { clock }
    }

    @After
    fun tearDown() = db.close()

    private fun tree() = FileDocumentTree(root)

    private fun sync() = SidecarSync(db, {
        tree()
    }, "Reveal 1.0.0", writeEdits = { writeOn.value }, device = "Pixel 9") {
        OffsetDateTime.parse("2026-11-13T10:00:00Z")
    }

    private fun writer() = EditSidecars(db, sync(), writeOn, { writeOn.value }, kotlinx.coroutines.test.TestScope())

    private suspend fun index(): IndexReport = LibraryIndexer(db, { readOn }) { clock }.index(sourceId, tree())

    private suspend fun shoot(stem: String = "DSC00001", orientation: Int = 1): Long {
        val exif = SyntheticExif(orientation = orientation)
        File(root, "$stem.ARW").writeBytes(SyntheticShoot.raw(SyntheticShoot.markerOnlyJpeg(10, 10), exif))
        if (sourceId == 0L) {
            sourceId = db.sourceDao().insert(
                SourceEntity(0, "content://t/1", "", "shoot", null, null, SourceState.AVAILABLE, true, 0),
            )
        }
        index()
        return db.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).first { it.name.startsWith(stem) }.id
    }

    private suspend fun version(photoId: Long) = checkNotNull(db.photoDao().firstVersion(photoId))

    private fun fixture(name: String): ByteArray =
        checkNotNull(javaClass.classLoader!!.getResourceAsStream("xmp/$name")) { name }.readBytes()

    /** Writes [bytes] as the shot's sidecar, as another app would (a newer mtime). */
    private fun otherApp(stem: String, bytes: ByteArray) {
        val file = File(root, "$stem.xmp")
        file.writeBytes(bytes)
        clock += 60_000
        file.setLastModified(file.lastModified() + 5_000)
    }

    private suspend fun commit(versionId: Long, recipe: Recipe) {
        clock += 1_000
        val before = edits.recipe(versionId)
        edits.commit(versionId, recipe, RecipeDiff.between(before, recipe))
    }

    /** A sidecar as another device's Reveal writes it: [recipe] made after [ancestors]. */
    private fun device(recipe: Recipe, ancestors: List<Recipe>, edited: Long, existing: ByteArray? = null): ByteArray =
        XmpWriter.update(
            existing,
            XmpFields(),
            "Reveal 1.0.0",
            "2026-11-13T10:00:00Z",
            listOf(
                XmpDevelop.extension(
                    DevelopState(
                        recipe,
                        PhotoFrame(raw = true, width = 6000, height = 4000),
                        ancestors.map(SidecarRecipe::digest),
                        edited,
                        "Tab S9",
                    ),
                ),
            ),
        )

    private suspend fun lastAction(versionId: Long) = edits.history(versionId).steps.last().label.action

    @Test
    fun `Lightroom's edit of a photo never edited in Reveal comes in as one step`() = runTest {
        File(root, "DSC00001.xmp").writeBytes(fixture("lightroom-classic.xmp"))
        val id = shoot()
        val v = version(id)
        val recipe = edits.recipe(v)
        assertThat(
            recipe.light,
        ).isEqualTo(
            Light(exposureEv = 0.7f, contrast = 15f, highlights = -40f, shadows = 30f, whites = 5f, blacks = -10f),
        )
        assertThat(recipe.effects.texture).isEqualTo(10f)
        assertThat(recipe.effects.clarity).isEqualTo(12f)
        assertThat(recipe.colour.vibrance).isEqualTo(20f)
        assertThat(recipe.colour.whiteBalance.mode).isEqualTo(WbMode.CUSTOM)
        assertThat(recipe.colour.whiteBalance.temperatureK).isEqualTo(5600f)
        assertThat(recipe.colour.whiteBalance.tint).isEqualTo(8f)
        assertThat(recipe.colour.mixer.hue.orange).isEqualTo(-5f)
        assertThat(recipe.colour.mixer.saturation.blue).isEqualTo(-15f)
        assertThat(recipe.detail.nrLuminance).isEqualTo(20f)
        assertThat(recipe.effects.vignetteAmount).isEqualTo(-18f)
        assertThat(recipe.optics.lensCorrections).isTrue()
        assertThat(recipe.optics.removeCa).isTrue()
        assertThat(recipe.geometry.crop).isEqualTo(CropRect(0.1f, 0.05f, 0.9f, 0.95f))
        // Lightroom's own defaults are not edits: no sharpening 40, no colour noise 25.
        assertThat(recipe.detail.sharpenAmount).isEqualTo(0f)
        assertThat(recipe.detail.nrColour).isEqualTo(0f)
        val history = edits.history(v)
        assertThat(history.steps).hasSize(1)
        assertThat(history.steps.single().label.action).isEqualTo(HistoryAction.XMP_IMPORT)
        val photo = db.photoDao().get(id)!!
        assertThat(photo.rating).isEqualTo(4)
        assertThat(photo.label).isEqualTo("Red")
        assertThat(photo.hasEdit).isTrue()
    }

    @Test
    fun `a sidecar Lightroom wrote only to rate the photo imports nothing`() = runTest {
        File(root, "DSC00001.xmp").writeBytes(fixture("lightroom-classic-rated-only.xmp"))
        val id = shoot()
        assertThat(edits.recipe(version(id))).isEqualTo(Recipe())
        assertThat(edits.history(version(id)).steps).isEmpty()
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(3)
    }

    @Test
    fun `Lightroom mobile's element form, black and white and a portrait crop`() = runTest {
        File(root, "DSC00001.xmp").writeBytes(fixture("lightroom-mobile.xmp"))
        val id = shoot(orientation = 6)
        val recipe = edits.recipe(version(id))
        assertThat(recipe.light.exposureEv).isEqualTo(-0.35f)
        assertThat(recipe.light.shadows).isEqualTo(45f)
        assertThat(recipe.effects.dehaze).isEqualTo(12f)
        assertThat(recipe.profile.treatment).isEqualTo(Treatment.BLACK_WHITE)
        assertThat(recipe.colour.greyMixer.blue).isEqualTo(-30f)
        assertThat(recipe.colour.whiteBalance.mode).isEqualTo(WbMode.DAYLIGHT)
        assertThat(recipe.effects.grainAmount).isEqualTo(25f)
        assertThat(recipe.light.curve.rgb).hasSize(3)
        // Stored columns 0.25..0.75 of the landscape image = rows 0.25..0.75 of the portrait seen.
        assertThat(recipe.geometry.crop).isEqualTo(CropRect(0f, 0.25f, 1f, 0.75f))
        assertThat(recipe.geometry.rotate).isEqualTo(0)
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(5)
    }

    @Test
    fun `an edit goes to the sidecar, and is not read back as a change`() = runTest {
        val id = shoot()
        val v = version(id)
        val edit =
            Recipe(light = Light(exposureEv = 0.5f), geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)))
        commit(v, edit)
        assertThat(writer().flush()).isEqualTo(1)
        val sidecar = File(root, "DSC00001.xmp")
        val develop = XmpDevelop.read(XmpDom.parse(sidecar.readBytes()))
        assertThat(develop.reveal!!.recipe).isEqualTo(edit)
        assertThat(develop.reveal.device).isEqualTo("Pixel 9")
        assertThat(develop.crs["Exposure2012"]).isEqualTo("+0.50")
        assertThat(develop.crs["HasCrop"]).isEqualTo("True")
        // Nothing new: no write; the next walk reads nothing.
        assertThat(writer().flush()).isEqualTo(0)
        val report = index()
        assertThat(report.sidecarsRead).isEqualTo(0)
        assertThat(edits.history(v).steps).hasSize(1)
        // A rating change keeps the edit in the sidecar.
        db.photoDao().setRating(listOf(id), 2)
        sync().write(listOf(id))
        assertThat(XmpReader.read(sidecar.readBytes())).isEqualTo(XmpFields(rating = 2))
        assertThat(XmpDevelop.read(XmpDom.parse(sidecar.readBytes())).reveal!!.recipe).isEqualTo(edit)
    }

    @Test
    fun `turning the setting off stops the writes, turning it on writes what changed meanwhile`() = runTest {
        val id = shoot()
        writeOn.value = false
        commit(version(id), Recipe(light = Light(contrast = 20f)))
        assertThat(writer().flush()).isEqualTo(0)
        assertThat(File(root, "DSC00001.xmp").exists()).isFalse()
        // Ratings still travel.
        db.photoDao().setRating(listOf(id), 4)
        sync().write(listOf(id))
        assertThat(String(File(root, "DSC00001.xmp").readBytes())).doesNotContain("reveal:")
        writeOn.value = true
        assertThat(writer().flush()).isEqualTo(1)
        assertThat(String(File(root, "DSC00001.xmp").readBytes())).contains("crs:Contrast2012=\"+20\"")
    }

    @Test
    fun `an unedited photo gets no sidecar, and a reset updates the one it has`() = runTest {
        val id = shoot()
        val v = version(id)
        commit(v, Recipe(light = Light(exposureEv = 1f)))
        commit(v, Recipe())
        // Only the reset is pending for a new writer: no sidecar was there, none is created for an unedited recipe.
        db.metaDao().put(dev.hfps.reveal.data.db.MetaEntity(EditSidecars.KEY_WRITTEN, (clock - 500).toString()))
        assertThat(writer().flush()).isEqualTo(0)
        assertThat(File(root, "DSC00001.xmp").exists()).isFalse()
        commit(v, Recipe(light = Light(exposureEv = 1f)))
        writer().flush()
        commit(v, Recipe())
        writer().flush()
        val develop = XmpDevelop.read(XmpDom.parse(File(root, "DSC00001.xmp").readBytes()))
        assertThat(develop.reveal!!.recipe).isEqualTo(Recipe())
        assertThat(develop.crs["Exposure2012"]).isEqualTo("0.00")
    }

    @Test
    fun `a newer edit from another device comes in as a step on top`() = runTest {
        val id = shoot()
        val v = version(id)
        val a = Recipe(light = Light(exposureEv = 0.5f))
        commit(v, a)
        writer().flush()
        val b = a.copy(colour = Colour(vibrance = 30f))
        otherApp(
            "DSC00001",
            device(b, listOf(a), edited = clock + 30_000, existing = File(root, "DSC00001.xmp").readBytes()),
        )
        val report = index()
        assertThat(report.editsImported).isEqualTo(1)
        assertThat(report.editedVersions).containsExactly(v)
        assertThat(edits.recipe(v)).isEqualTo(b)
        assertThat(lastAction(v)).isEqualTo(HistoryAction.XMP_IMPORT)
        assertThat(edits.history(v).steps).hasSize(2)
        assertThat(db.syncDao().versionsOf(id)).hasSize(1)
        // Undo restores this device's edit.
        assertThat(edits.undo(v)).isEqualTo(a)
    }

    @Test
    fun `a sidecar older than the photo's edit is rewritten, not applied`() = runTest {
        val id = shoot()
        val v = version(id)
        val a = Recipe(light = Light(exposureEv = 0.5f))
        commit(v, a)
        writer().flush()
        val old = File(root, "DSC00001.xmp").readBytes()
        val b = a.copy(effects = Effects(clarity = 15f))
        commit(v, b)
        // The sidecar comes back as it was (a restore from a backup, a copy from another folder).
        otherApp("DSC00001", old)
        val report = index()
        assertThat(report.editsImported).isEqualTo(0)
        assertThat(report.sidecarsBehind).containsExactly(id)
        assertThat(edits.recipe(v)).isEqualTo(b)
        sync().write(report.sidecarsBehind, editsOnly = true)
        assertThat(XmpDevelop.read(XmpDom.parse(File(root, "DSC00001.xmp").readBytes())).reveal!!.recipe).isEqualTo(b)
    }

    @Test
    fun `diverged edits, the newer sidecar wins and this device's edit becomes a conflict copy`() = runTest {
        val id = shoot()
        val v = version(id)
        val a = Recipe(light = Light(exposureEv = 0.5f))
        commit(v, a)
        writer().flush()
        val mine = a.copy(light = a.light.copy(contrast = 10f))
        commit(v, mine)
        val theirs = a.copy(colour = Colour(saturation = -20f))
        otherApp("DSC00001", device(theirs, listOf(a), edited = clock + 10_000))
        val report = index()
        assertThat(report.editsImported).isEqualTo(1)
        assertThat(edits.recipe(v)).isEqualTo(theirs)
        val versions = db.syncDao().versionsOf(id)
        assertThat(versions).hasSize(2)
        val copy = versions.single { it.copyOf != null }
        assertThat(copy.copyOf).isEqualTo(versions.single { it.id == v }.uuid)
        assertThat(edits.recipe(copy.id)).isEqualTo(mine)
        assertThat(edits.history(copy.id).steps).hasSize(2)
        assertThat(versions.single { it.id == v }.editDevice).isEqualTo("Tab S9")
    }

    @Test
    fun `diverged edits, an older sidecar becomes the conflict copy`() = runTest {
        val id = shoot()
        val v = version(id)
        val a = Recipe(light = Light(exposureEv = 0.5f))
        commit(v, a)
        val mine = a.copy(light = a.light.copy(contrast = 10f))
        commit(v, mine)
        val theirs = a.copy(colour = Colour(saturation = -20f))
        otherApp("DSC00001", device(theirs, listOf(a), edited = clock - 3_600_000))
        index()
        assertThat(edits.recipe(v)).isEqualTo(mine)
        val copy = db.syncDao().versionsOf(id).single { it.copyOf != null }
        assertThat(edits.recipe(copy.id)).isEqualTo(theirs)
        assertThat(copy.copyDevice).isEqualTo("Tab S9")
        assertThat(edits.history(copy.id).steps.single().label.action).isEqualTo(HistoryAction.XMP_IMPORT)
    }

    @Test
    fun `Lightroom changing a value in Reveal's sidecar comes in on top of Reveal's recipe`() = runTest {
        val id = shoot()
        val v = version(id)
        val a =
            Recipe(
                light = Light(exposureEv = 0.5f, shadows = 20f),
                geometry = Geometry(crop = CropRect(0.1f, 0.1f, 0.9f, 0.9f)),
            )
        commit(v, a)
        writer().flush()
        val text = String(File(root, "DSC00001.xmp").readBytes())
            .replace("crs:Exposure2012=\"+0.50\"", "crs:Exposure2012=\"+1.25\"")
            .replace("crs:GrainAmount=\"0\"", "crs:GrainAmount=\"40\"")
        otherApp("DSC00001", text.toByteArray())
        index()
        val recipe = edits.recipe(v)
        assertThat(
            recipe,
        ).isEqualTo(a.copy(light = a.light.copy(exposureEv = 1.25f), effects = Effects(grainAmount = 40f)))
        assertThat(lastAction(v)).isEqualTo(HistoryAction.XMP_IMPORT)
    }

    @Test
    fun `Hoard rating a Reveal sidecar changes the rating only`() = runTest {
        val id = shoot()
        val v = version(id)
        val a = Recipe(light = Light(exposureEv = 0.5f))
        commit(v, a)
        writer().flush()
        // Hoard's writer is Reveal's merge: rating, pick and date change, the rest stays.
        val hoard = XmpWriter.update(
            File(root, "DSC00001.xmp").readBytes(),
            XmpFields(rating = 5, flag = PickFlag.PICK),
            "Hoard 1.9.0",
            "2026-11-14T08:00:00Z",
        )
        otherApp("DSC00001", hoard)
        val report = index()
        assertThat(report.sidecarsRead).isEqualTo(1)
        assertThat(report.editsImported).isEqualTo(0)
        assertThat(edits.history(v).steps).hasSize(1)
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(5)
        assertThat(db.photoDao().get(id)!!.flag).isEqualTo(PickFlag.PICK)
    }

    @Test
    fun `a newer app's edit and a switched-off read leave the photo alone`() = runTest {
        val id = shoot()
        val v = version(id)
        val newer = String(device(Recipe(light = Light(exposureEv = 2f)), emptyList(), clock + 1))
            .replace("reveal:RecipeVersion=\"1\"", "reveal:RecipeVersion=\"7\"")
        otherApp("DSC00001", newer.toByteArray())
        index()
        assertThat(edits.history(v).steps).isEmpty()
        readOn = false
        otherApp("DSC00001", fixture("lightroom-classic.xmp"))
        val report = index()
        assertThat(report.sidecarsRead).isEqualTo(1)
        assertThat(edits.history(v).steps).isEmpty()
        assertThat(db.photoDao().get(id)!!.rating).isEqualTo(4)
    }

    @Test
    fun `a malformed sidecar is never overwritten by an edit`() = runTest {
        val id = shoot()
        val broken = "<x:xmpmeta><rdf:RDF"
        File(root, "DSC00001.xmp").writeText(broken)
        index()
        commit(version(id), Recipe(light = Light(exposureEv = 0.5f)))
        assertThat(writer().flush()).isEqualTo(0)
        assertThat(File(root, "DSC00001.xmp").readText()).isEqualTo(broken)
    }

    @Test
    fun `Reveal to sidecar to another Reveal gives the identical recipe and the history label`() = runTest {
        val id = shoot()
        val v = version(id)
        val recipe = Recipe(
            light = Light(exposureEv = 0.734f, highlights = -33.3f),
            geometry = Geometry(crop = CropRect(0.12f, 0.08f, 0.91f, 0.87f), angle = 2.4f, rotate = 3, flipH = true),
        )
        commit(v, recipe)
        writer().flush()
        val sidecar = File(root, "DSC00001.xmp").readBytes()
        // A second library (another device without sync) reads the same folder.
        val other = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(),
            RevealDatabase::class.java,
        )
            .allowMainThreadQueries().build()
        try {
            val source = other.sourceDao().insert(
                SourceEntity(0, "content://t/2", "", "shoot", null, null, SourceState.AVAILABLE, true, 0),
            )
            LibraryIndexer(other).index(source, tree())
            val otherId = other.photoDao().rows(PhotoQuerySql.toQuery(PhotoQuery())).single().id
            val otherVersion = checkNotNull(other.photoDao().firstVersion(otherId))
            val otherEdits = EditRepository(other)
            assertThat(otherEdits.recipe(otherVersion)).isEqualTo(recipe)
            val step = otherEdits.history(otherVersion).steps.single()
            assertThat(step.label.action).isEqualTo(HistoryAction.XMP_IMPORT)
            assertThat(step.labels.map(HistoryStep::field)).contains(HistoryField.EXPOSURE)
        } finally {
            other.close()
        }
        assertThat(File(root, "DSC00001.xmp").readBytes()).isEqualTo(sidecar)
    }
}
