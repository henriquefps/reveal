package dev.hfps.reveal.pipeline.preset

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.pipeline.recipe.BaseLook
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Effects
import dev.hfps.reveal.pipeline.recipe.Light
import dev.hfps.reveal.pipeline.recipe.Profile
import dev.hfps.reveal.pipeline.recipe.Recipe
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertThrows
import org.junit.Test

/** `.revealpreset` and `.revealpack` round trips and hostile files (docs/adr/0019, "File format", "Packs"). */
class PresetFilesTest {
    private val recipe = Recipe(
        profile = Profile(BaseLook.PORTRAIT, 90f),
        light = Light(contrast = -15f, curve = Curves(rgb = Curves.MEDIUM_CONTRAST)),
        effects = Effects(grainAmount = 20f),
    )
    private val preset = Preset(
        id = "0f8fad5b-d9cb-469f-a165-70867728950e",
        name = "Soft matte",
        group = "Matte",
        author = "Ana Example",
        version = 2,
        createdAt = 1_762_000_000_000L,
        patch = PresetPatch.fromSections(
            recipe,
            listOf(PresetSection.PROFILE, PresetSection.CONTRAST, PresetSection.TONE_CURVE, PresetSection.GRAIN),
        ),
    )

    private fun text(s: String) = s.trimIndent().toByteArray()

    private fun error(block: () -> Unit): PresetError =
        assertThrows(PresetFormatException::class.java) { block() }.error

    @Test
    fun `a preset file round trips`() {
        val bytes = PresetFiles.encode(preset)
        val text = bytes.decodeToString()
        assertThat(text).contains("\"format\": \"reveal-preset\"")
        assertThat(text).contains("\"created\": \"2025-11-01T12:26:40Z\"")
        val decoded = PresetFiles.decode(bytes)
        assertThat(decoded.ignored).isEmpty()
        assertThat(decoded.preset).isEqualTo(preset)
    }

    @Test
    fun `the smart part is carried verbatim`() {
        val smart = SmartPart(JsonObject(mapOf("targetBrightness" to JsonPrimitive(0.42))))
        val decoded = PresetFiles.decode(PresetFiles.encode(preset.copy(smart = smart)))
        assertThat(decoded.preset.smart).isEqualTo(smart)
    }

    @Test
    fun `a minimal third-party file is accepted`() {
        val decoded = PresetFiles.decode(
            text(
                """
                {"format":"reveal-preset","formatVersion":1,"recipeVersion":1,
                 "id":"9b2e7c1a-3f4d-4e5f-8a6b-7c8d9e0f1a2b","name":"Warm",
                 "patch":{"colour":{"vibrance":12}}}
                """,
            ),
        )
        assertThat(decoded.preset.name).isEqualTo("Warm")
        assertThat(decoded.preset.patch.applyFull(Recipe.DEFAULT).colour.vibrance).isEqualTo(12f)
        assertThat(decoded.preset.version).isEqualTo(1)
    }

    @Test
    fun `newer versions, other formats and bad metadata are refused`() {
        val base = String(PresetFiles.encode(preset))
        assertThat(
            error {
                PresetFiles.decode(base.replace("\"formatVersion\": 1", "\"formatVersion\": 2").toByteArray())
            },
        )
            .isEqualTo(PresetError.NEWER_VERSION)
        assertThat(
            error {
                PresetFiles.decode(base.replace("\"recipeVersion\": 1", "\"recipeVersion\": 99").toByteArray())
            },
        )
            .isEqualTo(PresetError.NEWER_VERSION)
        assertThat(error { PresetFiles.decode(base.replace("reveal-preset", "lightroom").toByteArray()) })
            .isEqualTo(PresetError.NOT_A_PRESET)
        assertThat(error { PresetFiles.decode(base.replace(preset.id, "not-a-uuid").toByteArray()) })
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(error { PresetFiles.decode(base.replace("Soft matte", "a\u0007b").toByteArray()) })
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(error { PresetFiles.decode(base.replace("Soft matte", "x".repeat(200)).toByteArray()) })
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(
            error {
                PresetFiles.decode(
                    base.replace("\"created\": \"2025-11-01T12:26:40Z\"", "\"created\": \"yesterday\"").toByteArray(),
                )
            },
        )
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(error { PresetFiles.decode("[1,2]".toByteArray()) }).isEqualTo(PresetError.MALFORMED)
        assertThat(error { PresetFiles.decode("{".toByteArray()) }).isEqualTo(PresetError.MALFORMED)
        assertThat(error { PresetFiles.decode(byteArrayOf(0xC3.toByte(), 0x28)) }).isEqualTo(PresetError.MALFORMED)
    }

    @Test
    fun `huge preset files and smart parts are refused`() {
        val huge = ByteArray(PresetLimits.PRESET_BYTES + 1) { ' '.code.toByte() }
        assertThat(error { PresetFiles.decode(huge) }).isEqualTo(PresetError.TOO_LARGE)
        val bigSmart = SmartPart(JsonObject(mapOf("x" to JsonPrimitive("y".repeat(PresetLimits.SMART_BYTES)))))
        assertThat(error { PresetFiles.decode(PresetFiles.encode(preset.copy(smart = bigSmart))) })
            .isEqualTo(PresetError.TOO_LARGE)
    }

    // ------------------------------------------------------------------ packs

    private val group = PackGroup("1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d", "Matte looks")
    private val pack = PresetPack(
        id = "6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d",
        name = "Example pack",
        author = "Ana Example",
        version = 3,
        licenceName = "Personal use",
        licenceText = "You may use these presets on your own photos.",
        link = "https://example.com/pack",
        groups = listOf(group),
        presets = listOf(
            preset.copy(group = group.name),
            preset.copy(id = "2b3c4d5e-6f70-4182-9a3b-4c5d6e7f8091", name = "Second", group = group.name),
        ),
        groupOf = mapOf(preset.id to group.id, "2b3c4d5e-6f70-4182-9a3b-4c5d6e7f8091" to group.id),
        cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3),
    )

    private fun packBytes(p: PresetPack = pack): ByteArray =
        ByteArrayOutputStream().also { PresetPacks.write(p, it) }.toByteArray()

    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun read(bytes: ByteArray) = PresetPacks.read(ByteArrayInputStream(bytes))

    private val manifest = """
        {"format":"reveal-pack","formatVersion":1,"id":"6a5b4c3d-2e1f-4a0b-8c9d-0e1f2a3b4c5d","name":"P",
         "licence":{"name":"CC0"},"groups":[{"id":"1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d","name":"G"}],
         "presets":[{"file":"a.revealpreset","group":"1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d"}]}
    """.trimIndent()

    @Test
    fun `a pack round trips with its groups, licence, link and cover`() {
        val read = read(packBytes())
        assertThat(read).isEqualTo(pack)
        assertThat(read.licenceText).isEqualTo(pack.licenceText)
        assertThat(read.link).isEqualTo(pack.link)
        assertThat(read.presets.map { it.group }).containsExactly(group.name, group.name)
    }

    @Test
    fun `a minimal pack is accepted`() {
        val read = read(zip("manifest.json" to manifest.toByteArray(), "a.revealpreset" to PresetFiles.encode(preset)))
        assertThat(read.presets.single().group).isEqualTo("G")
        assertThat(read.cover).isNull()
        assertThat(read.licenceName).isEqualTo("CC0")
    }

    @Test
    fun `zip slip names are refused even though nothing is written by name`() {
        listOf("../evil.revealpreset", "/abs.revealpreset", "a/../../b", "a\\b", "C:x", "a//b", "./a").forEach { name ->
            val bytes = zip("manifest.json" to manifest.toByteArray(), name to "x".toByteArray())
            assertThat(error { read(bytes) }).isEqualTo(PresetError.UNSAFE_ENTRY)
        }
    }

    @Test
    fun `a zip bomb stops at the size limit whatever the headers say`() {
        val zeros = ByteArray(PresetLimits.COVER_BYTES + 1)
        val bytes = zip("manifest.json" to manifest.toByteArray(), "bomb.bin" to zeros)
        assertThat(bytes.size).isLessThan(64 * 1024) // compresses to almost nothing
        assertThat(error { read(bytes) }).isEqualTo(PresetError.TOO_LARGE)
        // Many medium entries: the total decompressed size is capped too.
        val many = (0 until 14).map { "f$it.bin" to ByteArray(PresetLimits.COVER_BYTES) }.toTypedArray()
        assertThat(
            error {
                read(zip("manifest.json" to manifest.toByteArray(), *many))
            },
        ).isEqualTo(PresetError.TOO_LARGE)
    }

    @Test
    fun `broken packs are refused with a reason`() {
        assertThat(error { read("not a zip".toByteArray()) }).isEqualTo(PresetError.MALFORMED)
        assertThat(error { read(zip("other.json" to "{}".toByteArray())) }).isEqualTo(PresetError.MALFORMED)
        val preset = "a.revealpreset" to PresetFiles.encode(preset)
        assertThat(error { read(zip("manifest.json" to manifest.replace("reveal-pack", "x").toByteArray(), preset)) })
            .isEqualTo(PresetError.NOT_A_PRESET)
        assertThat(
            error {
                read(
                    zip(
                        "manifest.json" to manifest.replace("\"formatVersion\":1", "\"formatVersion\":7").toByteArray(),
                        preset,
                    ),
                )
            },
        )
            .isEqualTo(PresetError.NEWER_VERSION)
        // A missing preset file, an unknown group, a non-https link, a cover that is not an image.
        assertThat(error { read(zip("manifest.json" to manifest.toByteArray())) }).isEqualTo(PresetError.MALFORMED)
        assertThat(
            error {
                read(
                    zip(
                        "manifest.json" to manifest.replace("\"group\":\"1c9e", "\"group\":\"2c9e").toByteArray(),
                        preset,
                    ),
                )
            },
        )
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(
            error {
                read(
                    zip(
                        "manifest.json" to
                            manifest.replace(
                                "\"licence\"",
                                "\"link\":\"javascript:alert(1)\",\"licence\"",
                            ).toByteArray(),
                        preset,
                    ),
                )
            },
        )
            .isEqualTo(PresetError.INVALID_VALUE)
        assertThat(
            error {
                read(
                    zip(
                        "manifest.json" to
                            manifest.replace("\"licence\"", "\"cover\":\"c.jpg\",\"licence\"").toByteArray(),
                        preset,
                        "c.jpg" to "<svg/>".toByteArray(),
                    ),
                )
            },
        )
            .isEqualTo(PresetError.INVALID_VALUE)
    }

    @Test
    fun `duplicate preset ids in one pack are refused`() {
        val twice = manifest.replace(
            "\"presets\":[{\"file\":\"a.revealpreset\",\"group\":\"1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d\"}]",
            "\"presets\":[{\"file\":\"a.revealpreset\",\"group\":\"1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d\"}," +
                "{\"file\":\"a.revealpreset\",\"group\":\"1c9e5b2a-0d3f-4a7b-9c8d-2e1f0a9b8c7d\"}]",
        )
        assertThat(
            error {
                read(
                    zip(
                        "manifest.json" to twice.toByteArray(),
                        "a.revealpreset" to PresetFiles.encode(preset),
                    ),
                )
            },
        )
            .isEqualTo(PresetError.INVALID_VALUE)
    }
}
