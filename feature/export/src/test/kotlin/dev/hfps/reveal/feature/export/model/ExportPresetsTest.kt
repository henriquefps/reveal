package dev.hfps.reveal.feature.export.model

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.data.export.StoredExportPreset
import dev.hfps.reveal.pipeline.export.ExportSize
import org.junit.Test

/** Export presets (docs/adr/0025): the built-ins, matching the sheet's options, and use on another device. */
class ExportPresetsTest {
    @Test
    fun `the built-ins are what their names promise, with stable keys`() {
        val byId = BuiltInExportPreset.entries.associateBy { it.id }
        assertThat(byId.keys).containsExactly("instagram", "web", "full")
        assertThat(byId.getValue("instagram").options.size).isEqualTo(ExportSize.FitBox(1080, 1350))
        assertThat(byId.getValue("instagram").options.destination).isEqualTo(ExportDestination.Share)
        assertThat(byId.getValue("web").options.size).isEqualTo(ExportSize.LongEdge(2048))
        assertThat(byId.getValue("full").options.size).isEqualTo(ExportSize.Original)
        assertThat(byId.getValue("full").options.quality).isEqualTo(100)
        assertThat(
            ExportPresets.builtIns.map {
                it.key
            },
        ).containsExactly("builtin:instagram", "builtin:web", "builtin:full")
            .inOrder()
        // Nothing device-specific in a built-in.
        assertThat(BuiltInExportPreset.entries.map { it.options.destination }).doesNotContain(null)
        assertThat(BuiltInExportPreset.entries.none { it.options.destination is ExportDestination.Folder }).isTrue()
    }

    @Test
    fun `user presets decode their options and the sheet's options select the matching one`() {
        val prints = ExportOptions(quality = 100, size = ExportSize.LongEdge(4000))
        val stored = listOf(
            StoredExportPreset("u1", "Prints", prints.encode(), 0),
            StoredExportPreset("u2", "From a newer app", """{"quality":77,"futureOption":true}""", 1),
        )
        val all = ExportPresets.all(stored)
        assertThat(all.map { it.key }.takeLast(2)).containsExactly("u1", "u2").inOrder()
        assertThat(all.last().options.quality).isEqualTo(77)
        assertThat(ExportPresets.matching(prints, all)?.key).isEqualTo("u1")
        assertThat(ExportPresets.matching(BuiltInExportPreset.WEB.options, all)?.key).isEqualTo("builtin:web")
        assertThat(ExportPresets.matching(ExportOptions(quality = 3), all)).isNull()
    }

    @Test
    fun `a preset from another device falls back where this one cannot follow`() {
        val folder = ExportDestination.Folder("content://tree/elsewhere", "Exports")
        val preset = ExportPreset.User(
            "u",
            "HEIF to a folder",
            ExportOptions(format = ExportFormat.HEIF, destination = folder),
        )
        val here = ExportPresets.resolve(preset, heif = false) { false }
        assertThat(here.destination).isEqualTo(ExportDestination.Pictures)
        assertThat(here.format).isEqualTo(ExportFormat.JPEG)
        val granted = ExportPresets.resolve(preset, heif = true) { it == folder.treeUri }
        assertThat(granted).isEqualTo(preset.options)
    }
}
