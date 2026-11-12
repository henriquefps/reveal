package dev.hfps.reveal.ml

import com.google.common.truth.Truth.assertThat
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
        .associateBy { it.jsonObject["id"]!!.jsonPrimitive.content }
        .mapValues { it.value.jsonObject }

    @Test
    fun `every spec is pinned in the manifest with the same asset, version and checksum`() {
        for (spec in MaskModels.all) {
            val entry = manifest[spec.id.value] ?: error("${spec.id} missing from models.json")
            assertThat(entry["asset"]!!.jsonPrimitive.content).isEqualTo(spec.asset)
            assertThat(entry["version"]!!.jsonPrimitive.int).isEqualTo(spec.version)
            assertThat(entry["sha256"]!!.jsonPrimitive.content).isEqualTo(spec.sha256)
            assertThat(entry["sha256"]!!.jsonPrimitive.content).matches("[0-9a-f]{64}")
        }
    }

    @Test
    fun `the bundled list is what the manifest bundles`() {
        val bundled = manifest.filterValues { it["bundled"]!!.jsonPrimitive.boolean }.keys
        assertThat(MaskModels.bundled.map { it.id.value }).containsExactlyElementsIn(bundled)
    }

    @Test
    fun `every manifest entry has a licence, an https url and a size`() {
        for ((id, entry) in manifest) {
            assertThat(entry["licence"]!!.jsonPrimitive.content).isNotEmpty()
            assertThat(entry["url"]!!.jsonPrimitive.content).startsWith("https://")
            assertThat(entry["bytes"]!!.jsonPrimitive.int).isGreaterThan(0)
            assertThat(id).matches("[a-z][a-z0-9]*(-[a-z0-9]+)*")
        }
    }

    @Test
    fun `ids are unique and keys carry the version`() {
        assertThat(MaskModels.all.map { it.id }.toSet()).hasSize(MaskModels.all.size)
        assertThat(MaskModels.SELFIE_MULTICLASS.key).isEqualTo("selfie-multiclass@1")
    }

    @Test
    fun `every M4 kind has a bundled model`() {
        val kinds = MaskModels.bundled.flatMap { it.kinds.keys }.toSet() + MaskKind.BACKGROUND
        assertThat(kinds).containsExactlyElementsIn(MaskKind.entries)
    }
}
