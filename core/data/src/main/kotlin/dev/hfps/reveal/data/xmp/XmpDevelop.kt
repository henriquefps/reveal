package dev.hfps.reveal.data.xmp

import dev.hfps.reveal.pipeline.recipe.RECIPE_VERSION
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * The recipe as it travels in a sidecar (ADR 0026 "The `reveal:` block"): the ADR 0003 JSON **without its default
 * fields** (`{"v":1,"light":{"exposure":0.7}}`), so it stays small and its [digest] does not change when a later app
 * adds a field with a default. Above [DEFLATE_ABOVE] characters (long brush strokes) it is stored deflated, Base64.
 */
object SidecarRecipe {
    /** Characters of compact JSON above which the recipe is written deflated (`reveal:RecipeDeflate`). */
    const val DEFLATE_ABOVE = 16 * 1024

    /** Hex characters of a [digest] (64 bits: collisions do not matter at one photo's history). */
    private const val DIGEST_HEX = 16

    /** Largest inflated recipe read back (a hostile sidecar cannot inflate into memory). */
    private const val MAX_INFLATED = 4 * 1024 * 1024

    private val full = Json { encodeDefaults = true }

    private val defaults: JsonObject = full.encodeToJsonElement(Recipe.serializer(), Recipe()) as JsonObject

    /**
     * The compact JSON of [recipe], `v` first: object members equal to the default recipe's at the same path are
     * left out (recursively); arrays (masks, curve points) are kept whole, so every polymorphic `type` stays.
     */
    fun encode(recipe: Recipe): String {
        val tree = full.encodeToJsonElement(Recipe.serializer(), recipe) as JsonObject
        val pruned = prune(tree, defaults)
        return JsonObject(mapOf("v" to JsonPrimitive(recipe.v)) + pruned.filterKeys { it != "v" }).toString()
    }

    private fun prune(value: JsonObject, default: JsonObject): JsonObject = JsonObject(
        value.mapNotNull { (key, member) ->
            val base = default[key]
            when {
                member == base -> null

                member is JsonObject && base is JsonObject -> prune(member, base).takeIf { it.isNotEmpty() }?.let {
                    key to
                        it
                }

                else -> key to member
            }
        }.toMap(),
    )

    /** Decodes compact (or full) recipe JSON through [RecipeCodec] (migrations, newer versions refused). */
    fun decode(json: String): Recipe = RecipeCodec.Default.decode(json)

    /** A short, stable digest of [recipe]: SHA-256 of [encode], first 16 hex characters. */
    fun digest(recipe: Recipe): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(encode(recipe).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(DIGEST_HEX)
    }

    fun deflate(text: String): String {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_COMPRESSION)).use {
            it.write(text.toByteArray(Charsets.UTF_8))
        }
        return Base64.getEncoder().encodeToString(out.toByteArray())
    }

    /** Inflates [base64]; null when it is not valid or inflates beyond [MAX_INFLATED]. */
    fun inflate(base64: String): String? = runCatching {
        val bytes = Base64.getMimeDecoder().decode(base64.trim())
        InflaterInputStream(bytes.inputStream(), Inflater()).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
                check(out.size() <= MAX_INFLATED) { "recipe too large" }
            }
            out.toString(Charsets.UTF_8.name())
        }
    }.getOrNull()

    private const val BUFFER = 8192
}

/** The `reveal:` block of a sidecar ([XmpDevelop.read]). */
data class RevealBlock(
    /** The recipe; null when it is missing, malformed, or written by a newer app ([newer]). */
    val recipe: Recipe?,
    /** Written by a newer schema or recipe version than this app reads: leave the sidecar's develop data alone. */
    val newer: Boolean,
    /** When that recipe was made (Unix ms), if known. */
    val edited: Long?,
    /** The device model that wrote it, if known. */
    val device: String?,
    /** Digests of the recipes it came from, oldest first ([SidecarRecipe.digest]). */
    val ancestors: List<String>,
)

/** A sidecar's develop part: Reveal's block, the Camera Raw values (`crs:` local name -> text), the orientation. */
data class SidecarDevelop(val reveal: RevealBlock?, val crs: Map<String, String>, val orientation: Int?) {
    /** True when there is anything to reconcile with the photo's recipe. */
    val hasEdits: Boolean get() = reveal != null || CameraRaw.fields.any { it.importable && it.name in crs } ||
        crs["HasCrop"] != null

    companion object {
        val NONE = SidecarDevelop(null, emptyMap(), null)
    }
}

/** What Reveal writes as the develop part of a sidecar ([XmpDevelop.extension]). */
data class DevelopState(
    val recipe: Recipe,
    val frame: PhotoFrame,
    /** Digests of the history steps before the current one, oldest first; at most [XmpDevelop.MAX_ANCESTORS]. */
    val ancestors: List<String>,
    /** When the recipe was last changed (Unix ms). */
    val edited: Long,
    /** This device's model (`reveal:Device`). */
    val device: String,
    /**
     * Write only when the sidecar already has a `reveal:` block: an unedited photo (a reset, or never edited) does not
     * start carrying Reveal's values into a sidecar that never had them (they would overwrite another app's `crs:`).
     */
    val onlyIfPresent: Boolean = false,
)

/**
 * Reads and writes the develop part of a sidecar (ADR 0026):
 *
 * - `reveal:` (namespace [XmpNs.REVEAL]): `reveal:Schema` ([SCHEMA]), `reveal:RecipeVersion` (ADR 0003 `v`),
 *   `reveal:Edited` (ISO 8601 UTC), `reveal:Device`, `reveal:Ancestors` (space-separated digests) and the recipe as
 *   the element `reveal:Recipe` (compact JSON) or `reveal:RecipeDeflate` (Base64 of deflated JSON).
 * - `crs:`: the mapped Camera Raw fields of [CameraRaw], the crop, `crs:HasSettings` and `crs:ProcessVersion`.
 * - `tiff:Orientation`: the picture's orientation after Reveal's quarter turns and flips.
 *
 * Everything else in the sidecar is kept: other `crs:` fields (camera profile, Lightroom's local adjustments),
 * darktable's history, other namespaces.
 */
object XmpDevelop {
    /** The `reveal:` layout version; a sidecar with a higher one is never rewritten by this app. */
    const val SCHEMA = 1
    const val MAX_ANCESTORS = 32

    /** The develop part of sidecar [bytes]; throws [XmpException] when they are not well-formed XML. */
    fun read(bytes: ByteArray): SidecarDevelop = read(XmpDom.parse(bytes))

    /** The develop part of [document]. Never throws: anything malformed reads as absent. */
    fun read(document: Document): SidecarDevelop {
        val descriptions = XmpDom.descriptions(document)
        val crs = LinkedHashMap<String, String>()
        var orientation: Int? = null
        for (d in descriptions) {
            for (name in CameraRaw.managedNames) {
                if (name in crs) continue
                val value = if (CameraRaw.isList(name)) {
                    XmpDom.list(d, XmpNs.CRS, name)?.let(CameraRaw::joinList)
                } else {
                    XmpDom.property(d, XmpNs.CRS, name)
                }
                value?.let { crs[name] = it }
            }
            orientation = orientation ?: XmpDom.property(d, XmpNs.TIFF, "Orientation")?.trim()?.toIntOrNull()
                ?.takeIf { it in ORIENTATIONS }
        }
        return SidecarDevelop(readReveal(descriptions), crs, orientation)
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod", "SwallowedException")
    private fun readReveal(descriptions: List<Element>): RevealBlock? {
        if (descriptions.none { XmpDom.hasNamespace(it, XmpNs.REVEAL) }) return null
        fun prop(name: String) = descriptions.firstNotNullOfOrNull { XmpDom.property(it, XmpNs.REVEAL, name) }
        val schema = prop("Schema")?.trim()?.toIntOrNull() ?: SCHEMA
        val version = prop("RecipeVersion")?.trim()?.toIntOrNull()
        val edited = prop("Edited")?.let {
            runCatching { OffsetDateTime.parse(it.trim()).toInstant().toEpochMilli() }.getOrNull()
        }
        val device = prop("Device")?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_DEVICE)
        val ancestors = prop("Ancestors")?.split(' ')?.map { it.trim() }?.filter { it.matches(DIGEST) }.orEmpty()
            .takeLast(MAX_ANCESTORS)
        if (schema > SCHEMA || (version != null && version > RECIPE_VERSION)) {
            return RevealBlock(null, newer = true, edited = edited, device = device, ancestors = ancestors)
        }
        val text = prop("Recipe") ?: prop("RecipeDeflate")?.let(SidecarRecipe::inflate)
        var newer = false
        val recipe = text?.let {
            try {
                SidecarRecipe.decode(it)
            } catch (e: dev.hfps.reveal.pipeline.recipe.UnsupportedRecipeVersionException) {
                newer = e.found > RECIPE_VERSION
                null
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                null
            }
        }
        return RevealBlock(recipe, newer, edited, device, ancestors)
    }

    private val DIGEST = Regex("[0-9a-f]{16}")
    private val ORIENTATIONS = 1..8
    private const val MAX_DEVICE = 80

    /** True when [document] carries a `reveal:` block this app must not overwrite (a newer app's). */
    fun isNewer(document: Document): Boolean = readReveal(XmpDom.descriptions(document))?.newer == true

    /** The extension that writes [state] (null = leave the develop part as it is). */
    fun extension(state: DevelopState?): XmpExtension = XmpExtension { document, target ->
        if (state == null) return@XmpExtension
        val descriptions = XmpDom.descriptions(document)
        val existing = readReveal(descriptions)
        if (existing?.newer == true) return@XmpExtension
        if (state.onlyIfPresent && existing == null) return@XmpExtension
        write(descriptions, target, state)
    }

    private fun write(descriptions: List<Element>, target: Element, state: DevelopState) {
        for (d in descriptions) {
            XmpDom.removeNamespace(d, XmpNs.REVEAL)
            for (name in CameraRaw.managedNames) XmpDom.removeProperty(d, XmpNs.CRS, name)
            XmpDom.removeProperty(d, XmpNs.TIFF, "Orientation")
        }
        val recipe = state.recipe
        val orientation = CameraRawGeometry.orientation(recipe, state.frame)
        XmpDom.setAttribute(target, XmpNs.TIFF, "Orientation", orientation.toString())
        XmpDom.setAttribute(target, XmpNs.CRS, "ProcessVersion", CameraRaw.PROCESS_VERSION)
        XmpDom.setAttribute(target, XmpNs.CRS, "HasSettings", "True")
        val values = CameraRaw.values(recipe, state.frame)
        for ((name, value) in values) {
            if (!CameraRaw.isList(name)) XmpDom.setAttribute(target, XmpNs.CRS, name, value)
        }
        if (recipe.effects.vignetteAmount != 0f) XmpDom.setAttribute(target, XmpNs.CRS, "PostCropVignetteStyle", "1")
        for ((name, value) in CameraRawGeometry.crop(recipe, state.frame)) {
            XmpDom.setAttribute(target, XmpNs.CRS, name, value)
        }
        XmpDom.setAttribute(target, XmpNs.REVEAL, "Schema", SCHEMA.toString())
        XmpDom.setAttribute(target, XmpNs.REVEAL, "RecipeVersion", recipe.v.toString())
        XmpDom.setAttribute(target, XmpNs.REVEAL, "Edited", iso(state.edited))
        if (state.device.isNotBlank()) {
            XmpDom.setAttribute(
                target,
                XmpNs.REVEAL,
                "Device",
                state.device.take(MAX_DEVICE),
            )
        }
        if (state.ancestors.isNotEmpty()) {
            XmpDom.setAttribute(
                target,
                XmpNs.REVEAL,
                "Ancestors",
                state.ancestors.takeLast(MAX_ANCESTORS).joinToString(" "),
            )
        }
        for ((name, value) in values) {
            if (CameraRaw.isList(name)) XmpDom.appendSeq(target, XmpNs.CRS, name, CameraRaw.splitList(value))
        }
        val text = SidecarRecipe.encode(recipe)
        if (text.length > SidecarRecipe.DEFLATE_ABOVE) {
            XmpDom.appendText(target, XmpNs.REVEAL, "RecipeDeflate", SidecarRecipe.deflate(text))
        } else {
            XmpDom.appendText(target, XmpNs.REVEAL, "Recipe", text)
        }
        XmpDom.closeIndent(target)
    }

    fun iso(epochMillis: Long): String = Instant.ofEpochMilli(epochMillis).truncatedTo(ChronoUnit.SECONDS)
        .atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
