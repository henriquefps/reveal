package dev.hfps.reveal.data.preset

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.db.PhotoEntity
import dev.hfps.reveal.data.db.RevealDatabase
import dev.hfps.reveal.data.db.SourceEntity
import dev.hfps.reveal.data.db.VersionEntity
import dev.hfps.reveal.data.edit.EditRepository
import dev.hfps.reveal.data.model.PhotoFormat
import dev.hfps.reveal.data.model.SourceState
import dev.hfps.reveal.pipeline.preset.PackGroup
import dev.hfps.reveal.pipeline.preset.Preset
import dev.hfps.reveal.pipeline.preset.PresetError
import dev.hfps.reveal.pipeline.preset.PresetFiles
import dev.hfps.reveal.pipeline.preset.PresetFormatException
import dev.hfps.reveal.pipeline.preset.PresetIds
import dev.hfps.reveal.pipeline.preset.PresetPack
import dev.hfps.reveal.pipeline.preset.PresetPacks
import dev.hfps.reveal.pipeline.preset.PresetPatch
import dev.hfps.reveal.pipeline.preset.PresetSection
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.HistoryAction
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Recipe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The preset library (docs/adr/0019 "Library", "Import and export"). */
@RunWith(AndroidJUnit4::class)
class PresetRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var db: RevealDatabase
    private lateinit var repo: PresetRepository
    private var now = 1000L

    private val builtIn = Preset(
        id = "aa0e8400-e29b-41d4-a716-446655440000",
        name = "Built-in",
        patch = PresetPatch.fromSections(Recipe(light = Light(contrast = 10f)), listOf(PresetSection.CONTRAST)),
    )

    private val clarity = PresetPatch.fromSections(Recipe(effects = Effects(clarity = 30f)), listOf(PresetSection.CLARITY))

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = RevealDatabase.configure(Room.inMemoryDatabaseBuilder(context, RevealDatabase::class.java))
            .allowMainThreadQueries().build()
        repo = PresetRepository(
            db,
            folder.newFolder("covers"),
            { listOf(BuiltInGroup("bb0e8400-e29b-41d4-a716-446655440000", "Looks", listOf(builtIn))) },
        ) { now++ }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `built-ins come first, a new preset lands in the default group`() = runTest {
        val uuid = repo.create("Crisp", clarity, groupId = null)
        val library = repo.library().first()
        assertThat(library.groups.map { it.kind }).containsExactly(GroupKind.BUILT_IN, GroupKind.USER).inOrder()
        assertThat(library.groups[1].name).isEmpty() // the default group, shown as "My presets"
        val created = library[uuid]!!
        assertThat(created.origin).isEqualTo(PresetOrigin.USER)
        assertThat(created.preset.patch).isEqualTo(clarity)
        assertThat(repo.preset(builtIn.id)).isEqualTo(builtIn)
        // The new preset is a sync record.
        assertThat(db.syncDao().record("preset", uuid)!!.dirty).isTrue()
    }

    @Test
    fun `rename, reorder, move, duplicate and delete`() = runTest {
        val a = repo.create("A", clarity, null)
        val b = repo.create("B", clarity, null)
        repo.rename(a, "  Alpha\u0007 ")
        repo.reorder(listOf(b, a))
        var library = repo.library().first()
        assertThat(library.groups[1].presets.map { it.name }).containsExactly("B", "Alpha").inOrder()

        val named = repo.createGroup("Travel")
        repo.move(a, named)
        library = repo.library().first()
        assertThat(library.groups.map { it.name }).containsExactly("Looks", "", "Travel").inOrder()
        assertThat(library.groups[2].presets.single().uuid).isEqualTo(a)

        val copy = repo.duplicate(a, "Alpha 2")!!
        assertThat(repo.library().first().groups[2].presets.map { it.name }).containsExactly("Alpha", "Alpha 2")
        repo.delete(a)
        repo.delete(copy)
        // A named group emptied by deletes goes; the default group stays.
        library = repo.library().first()
        assertThat(library.groups.map { it.name }).containsExactly("Looks", "")
        assertThat(db.syncDao().record("preset", a)!!.deleted).isTrue()
    }

    @Test
    fun `groups are renamed, reordered and deleted with their presets`() = runTest {
        val g1 = repo.createGroup("One")
        val g2 = repo.createGroup("Two")
        val p = repo.create("P", clarity, g2)
        repo.renameGroup(g1, "First")
        repo.reorderGroups(listOf(g2, g1))
        assertThat(repo.library().first().userGroups.map { it.name }).containsExactly("Two", "First").inOrder()
        repo.deleteGroup(g2)
        assertThat(repo.library().first()[p]).isNull()
    }

    @Test
    fun `favourites and recents, built-ins included`() = runTest {
        val a = repo.create("A", clarity, null)
        repo.setFavourite(builtIn.id, true)
        repo.markUsed(a)
        repo.markUsed(builtIn.id)
        val library = repo.library().first()
        assertThat(library.favourites.map { it.uuid }).containsExactly(builtIn.id)
        assertThat(library.recents.map { it.uuid }).containsExactly(builtIn.id, a).inOrder()
        repo.setFavourite(builtIn.id, false)
        assertThat(repo.library().first().favourites).isEmpty()
    }

    @Test
    fun `a preset file is imported into its group, updated only by a newer version`() = runTest {
        val preset = Preset(PresetIds.newId(), "Imported", clarity, group = "Friends", version = 2)
        val first = repo.importPreset(PresetFiles.encode(preset)) as ImportResult.PresetAdded
        assertThat(first.updated).isFalse()
        var library = repo.library().first()
        assertThat(library.groups.last().name).isEqualTo("Friends")
        assertThat(library[preset.id]!!.origin).isEqualTo(PresetOrigin.IMPORTED)
        assertThat(repo.importPreset(PresetFiles.encode(preset))).isInstanceOf(ImportResult.AlreadyThere::class.java)
        val newer = preset.copy(name = "Imported v3", version = 3)
        assertThat((repo.importPreset(PresetFiles.encode(newer)) as ImportResult.PresetAdded).updated).isTrue()
        library = repo.library().first()
        assertThat(library[preset.id]!!.name).isEqualTo("Imported v3")
        // A broken file changes nothing.
        val error = assertThrows(PresetFormatException::class.java) { kotlinx.coroutines.runBlocking { repo.importPreset("{}".toByteArray()) } }
        assertThat(error.error).isEqualTo(PresetError.MALFORMED)
    }

    private fun pack(version: Int, names: List<String>): ByteArray {
        val group = PackGroup("1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d", "Golden")
        val presets = names.mapIndexed { i, name ->
            Preset("2b3c4d5e-6f70-4182-9a3b-4c5d6e7f809$i", name, clarity, group = group.name)
        }
        val pack = PresetPack(
            id = "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d", name = "Pack", author = "Ana Example", version = version,
            licenceName = "Personal use", licenceText = "Yours to use.", link = "https://example.com",
            groups = listOf(group), presets = presets, groupOf = presets.associate { it.id to group.id },
            cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1),
        )
        return ByteArrayOutputStream().also { PresetPacks.write(pack, it) }.toByteArray()
    }

    @Test
    fun `a pack is tried, kept, updated by a newer version and removed`() = runTest {
        val added = repo.importPack(ByteArrayInputStream(pack(1, listOf("Warm", "Warmer")))) as ImportResult.PackAdded
        assertThat(added.presets).isEqualTo(2)
        var library = repo.library().first()
        val group = library.groups.last()
        assertThat(group.kind).isEqualTo(GroupKind.PACK)
        assertThat(group.pack!!.trial).isTrue()
        assertThat(group.pack.licenceText).isEqualTo("Yours to use.")
        assertThat(group.pack.cover!!.isFile).isTrue()
        assertThat(group.presets.map { it.origin }.toSet()).containsExactly(PresetOrigin.PACK)
        assertThat(library.trialPacks).hasSize(1)

        repo.keepPack(added.packRowId)
        assertThat(repo.library().first().trialPacks).isEmpty()

        // The same version again changes nothing; a newer one replaces the presets and stays kept.
        assertThat((repo.importPack(ByteArrayInputStream(pack(1, listOf("X")))) as ImportResult.PackAdded).updated).isFalse()
        val update = repo.importPack(ByteArrayInputStream(pack(2, listOf("Warm", "Warmer", "Warmest")))) as ImportResult.PackAdded
        assertThat(update.updated).isTrue()
        library = repo.library().first()
        assertThat(library.groups.last().presets.map { it.name }).containsExactly("Warm", "Warmer", "Warmest")
        assertThat(library.groups.last().pack!!.trial).isFalse()

        val cover = library.groups.last().pack!!.cover!!
        repo.removePack(update.packRowId)
        library = repo.library().first()
        assertThat(library.groups.none { it.kind == GroupKind.PACK }).isTrue()
        assertThat(cover.exists()).isFalse()
    }

    @Test
    fun `export round trips a preset and a user group as a pack`() = runTest {
        val g = repo.createGroup("Mine")
        val p = repo.create("P", clarity, g)
        val file = PresetFiles.decode(repo.export(p)!!)
        assertThat(file.preset.patch).isEqualTo(clarity)
        val pack = PresetPacks.read(ByteArrayInputStream(repo.exportGroup(g, "Ana", "Personal use")!!))
        assertThat(pack.presets.map { it.id }).containsExactly(p)
        assertThat(pack.groups.single().name).isEqualTo("Mine")
    }

    @Test
    fun `batch apply makes one named step per photo`() = runTest {
        val edits = EditRepository(db) { now }
        val source = db.sourceDao().insert(SourceEntity(0, "t", "", "s", null, null, SourceState.AVAILABLE, true, 0))
        val versions = (0 until 3).map { i ->
            val photo = db.photoDao().insertPhoto(
                PhotoEntity(
                    sourceId = source, folder = "", folderDocumentId = "", stemKey = "a$i", name = "A$i.JPG",
                    format = PhotoFormat.JPEG, hasRaw = false, sortTime = 0, addedAt = 0,
                ),
            )
            db.photoDao().insertVersion(VersionEntity(photoId = photo, createdAt = 0))
        }
        val preset = Preset(PresetIds.newId(), "Crisp", clarity)
        val changed = edits.applyEach(versions, HistoryAction.PRESET, preset.name) { preset.apply(it, 0.5f) }
        assertThat(changed).isEqualTo(3)
        for (v in versions) {
            val history = edits.load(v)
            assertThat(history.entries).hasSize(1)
            assertThat(history.entries[0].label.action).isEqualTo(HistoryAction.PRESET)
            assertThat(history.entries[0].label.name).isEqualTo("Crisp")
            assertThat(history.current.effects.clarity).isEqualTo(15f)
        }
    }
}
