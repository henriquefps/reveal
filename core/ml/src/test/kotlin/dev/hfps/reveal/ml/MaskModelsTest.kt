package dev.hfps.reveal.ml

import com.google.common.truth.Truth.assertThat
import dev.hfps.reveal.ml.inpaint.InpaintModels
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

/** The Kotlin specs and the pinned manifest the build fetches (`core/ml/models.json`) agree. */
class MaskModelsTest {
    private val manifest = Json.parseToJsonElement(File("models.json").readText()).jsonObject["models"]!!.jsonArray
        .associateBy {
            "${it.jsonObject["id"]!!.jsonPrimitive.content}@${it.jsonObject["version"]!!.jsonPrimitive.int}"
        }
        .mapValues { it.value.jsonObject }

    private fun flag(entry: Map<String, kotlinx.serialization.json.JsonElement>, name: String) =
        entry[name]?.jsonPrimitive?.boolean ?: false

    @Test
    fun `every spec is pinned in the manifest with the same asset, version and checksum`() {
        for (spec in MaskModels.pinned) {
            val entry = manifest[spec.key] ?: error("${spec.key} missing from models.json")
            assertThat(entry["asset"]!!.jsonPrimitive.content).isEqualTo(spec.asset)
            assertThat(entry["version"]!!.jsonPrimitive.int).isEqualTo(spec.version)
            assertThat(entry["sha256"]!!.jsonPrimitive.content).isEqualTo(spec.sha256)
            assertThat(entry["sha256"]!!.jsonPrimitive.content).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun `the bundled and lab lists are what the manifest carries`() {
        val bundled = manifest.filterValues { flag(it, "bundled") }.keys
        val lab = manifest.filterValues { flag(it, "lab") }.keys
        val kotlinBundled = MaskModels.bundled.map { it.key } + MaskModels.FACE_LANDMARKER.key +
            InpaintModels.bundled.map { it.key }
        assertThat(kotlinBundled).containsExactlyElementsIn(bundled)
        assertThat(MaskModels.lab.map { it.key }).containsExactlyElementsIn(lab)
        assertThat(bundled.intersect(lab)).isEmpty()
    }

    @Test
    fun `every manifest entry has a licence, a size and an https url or a derivation from a pinned entry`() {
        for ((key, entry) in manifest) {
            val id = key.substringBefore('@')
            assertThat(entry["licence"]!!.jsonPrimitive.content).isNotEmpty()
            assertThat(entry["bytes"]!!.jsonPrimitive.int).isGreaterThan(0)
            assertThat(id).matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")
            val derive = entry["derive"]?.jsonObject
            if (derive == null) {
                assertThat(entry["url"]!!.jsonPrimitive.content).startsWith("https://")
            } else {
                assertThat(entry).doesNotContainKey("url")
                assertThat(manifest).containsKey(derive["from"]!!.jsonPrimitive.content)
                val rule = derive["convert"] ?: derive["extract"]
                assertThat(rule).isNotNull()
                derive["convert"]?.let {
                    assertThat(File("../../scripts/ml/${it.jsonPrimitive.content}").isFile).isTrue()
                }
            }
        }
    }

    @Test
    fun `ids are unique and keys carry the version`() {
        assertThat(MaskModels.pinned.map { it.key }.toSet()).hasSize(MaskModels.pinned.size)
        assertThat(MaskModels.pinned.map { it.asset }.toSet()).hasSize(MaskModels.pinned.size)
        assertThat(MaskModels.SELFIE_MULTICLASS.key).isEqualTo("selfie-multiclass@2")
        assertThat(MaskModels.SELFIE_MULTICLASS_V1.key).isEqualTo("selfie-multiclass@1")
    }

    @Test
    fun `every M4 kind comes from a release-bundled model`() {
        val kinds = MaskModels.bundled.flatMap { it.kinds.keys }.toSet() + MaskKind.BACKGROUND +
            MaskKind.entries.filter { it.isFacePart }
        assertThat(kinds).containsExactlyElementsIn(MaskKind.entries)
        for (kind in MaskKind.entries) {
            val model = dev.hfps.reveal.ml.service.MaskService.modelFor(kind)
            assertThat(model in MaskModels.bundled || model == MaskModels.FACE_LANDMARKER).isTrue()
        }
    }

    @Test
    fun `the sky model reads one sigmoid channel`() {
        val sky = MaskModels.DEEPLAB_ADE20K_SKY
        assertThat(sky.output.channels).isEqualTo(1)
        assertThat(sky.output.activation).isEqualTo(dev.hfps.reveal.ml.tensor.Activation.SIGMOID)
        assertThat(sky.input).isEqualTo(MaskModels.DEEPLAB_ADE20K.input)
    }
}
