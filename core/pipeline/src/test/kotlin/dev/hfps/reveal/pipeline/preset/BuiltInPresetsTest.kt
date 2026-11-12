package dev.hfps.reveal.pipeline.preset

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.Recipe
import org.junit.Test

class BuiltInPresetsTest {
    private val all = BuiltInPresets.all

    @Test
    fun `at least thirty presets in every group`() {
        assertThat(all.size).isAtLeast(30)
        for (g in BuiltInPresets.Group.entries) assertThat(all.count { it.group == g }).isAtLeast(2)
        assertThat(all.map { it.key }.toSet()).hasSize(all.size)
    }

    @Test
    fun `ids are valid, unique and stable`() {
        assertThat(all.map { it.preset.id }.toSet()).hasSize(all.size)
        all.forEach { assertThat(PresetIds.isValid(it.preset.id)).isTrue() }
        // A release must never change an id (favourites, recents, sync).
        assertThat(BuiltInPresets.idOf("natural_clean")).isEqualTo(BuiltInPresets.idOf("natural_clean"))
        assertThat(BuiltInPresets.byId(BuiltInPresets.idOf("bw_classic"))?.key).isEqualTo("bw_classic")
        assertThat(BuiltInPresets.idOf("natural_clean")).isEqualTo(NATURAL_CLEAN_ID)
    }

    @Test
    fun `every preset changes the photo and differs from the others`() {
        val results = all.map { it.preset.apply(Recipe(), 1f, smartCorrection = null) }
        for ((p, r) in all.zip(results)) {
            if (p.preset.smart == null) assertThat(r).isNotEqualTo(Recipe())
        }
        assertThat(all.map { it.preset.patch }.toSet()).hasSize(all.size)
    }

    @Test
    fun `styles never set what describes one photo`() {
        val photoSpecific = setOf(
            PresetSection.EXPOSURE,
            PresetSection.WHITE_BALANCE,
            PresetSection.LENS_CORRECTIONS,
            PresetSection.CHROMATIC_ABERRATION,
            PresetSection.DEFRINGE,
            PresetSection.TRANSFORM,
        )
        for (p in all) assertThat(p.preset.patch.sections.intersect(photoSpecific)).isEmpty()
    }

    @Test
    fun `smart presets carry rules that the style does not override`() {
        val smart = all.filter { it.group == BuiltInPresets.Group.SMART }
        assertThat(smart.size).isAtLeast(3)
        for (p in smart) {
            val rules = checkNotNull(SmartRules.fromJson(checkNotNull(p.preset.smart).rules))
            assertThat(rules.isEmpty).isFalse()
            assertThat(p.preset.patch.sections.intersect(rules.sections)).isEmpty()
        }
        assertThat(all.filter { it.group != BuiltInPresets.Group.SMART }.all { it.preset.smart == null }).isTrue()
    }

    @Test
    fun `every preset and group has a translated name`() {
        for (p in all) assertThat(BuiltInPresetNames.name(p.key)).isNotNull()
        for (g in BuiltInPresets.Group.entries) assertThat(BuiltInPresetNames.group(g)).isNotEqualTo(0)
    }

    @Test
    fun `files round trip`() {
        for (p in all) {
            val decoded = PresetFiles.decode(PresetFiles.encode(p.preset)).preset
            assertThat(decoded).isEqualTo(p.preset)
        }
    }

    private companion object {
        const val NATURAL_CLEAN_ID = "6b6a233c-c458-436f-8a9f-52a98d691d88"
    }
}
