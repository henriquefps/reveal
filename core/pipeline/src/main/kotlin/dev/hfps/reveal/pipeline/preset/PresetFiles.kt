package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.recipe.RECIPE_VERSION
import java.time.Instant
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * The `.revealpreset` file (docs/adr/0019, "File format"): one UTF-8 JSON object, at most
 * [PresetLimits.PRESET_BYTES]:
 *
 * ```json
 * {"format": "reveal-preset", "formatVersion": 1, "recipeVersion": 1,
 *  "id": "8f0c…", "name": "Soft matte", "group": "Matte", "author": "Ana", "version": 1,
 *  "created": "2026-11-12T10:00:00Z",
 *  "patch": {"light": {"contrast": -15, "curve": {"rgb": [{"x": 0, "y": 0.06}, {"x": 1, "y": 1}]}}},
 *  "smart": {…}}
 * ```
 *
 * Reading is strict: the members above must have their types, ids are version 4 UUIDs, names are 1-80 characters
 * without control characters, a newer `formatVersion` or `recipeVersion` is refused ([PresetError.NEWER_VERSION]),
 * and the patch is checked field by field ([PresetPatch.fromJson]). Unknown top-level members are ignored (a newer
 * app's additive change), so are unknown patch fields (listed in [Decoded.ignored]).
 */
object PresetFiles {
    const val FORMAT: String = "reveal-preset"
    const val FORMAT_VERSION: Int = 1
    const val EXTENSION: String = "revealpreset"
    const val MIME: String = "application/vnd.hfps.reveal.preset+json"

    internal val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
    }

    /** A decoded preset and the patch paths it skipped. */
    data class Decoded(val preset: Preset, val ignored: List<String>)

    /** Writes [preset] as a `.revealpreset` document. */
    fun encode(preset: Preset): ByteArray = json.encodeToString(JsonElement.serializer(), toJson(preset)).toByteArray()

    /** The JSON object of [preset] (also a pack's preset entry). */
    fun toJson(preset: Preset): JsonObject = JsonObject(
        buildMap {
            put("format", JsonPrimitive(FORMAT))
            put("formatVersion", JsonPrimitive(FORMAT_VERSION))
            put("recipeVersion", JsonPrimitive(RECIPE_VERSION))
            put("id", JsonPrimitive(preset.id))
            put("name", JsonPrimitive(preset.name))
            put("group", JsonPrimitive(preset.group))
            put("author", JsonPrimitive(preset.author))
            put("version", JsonPrimitive(preset.version))
            put("created", JsonPrimitive(Instant.ofEpochMilli(preset.createdAt).toString()))
            put("patch", preset.patch.toJson())
            preset.smart?.let { put("smart", it.rules) }
        },
    )

    /** Reads a `.revealpreset` file; throws [PresetFormatException]. */
    fun decode(bytes: ByteArray): Decoded {
        if (bytes.size > PresetLimits.PRESET_BYTES) tooLarge("preset file over ${PresetLimits.PRESET_BYTES} bytes")
        return fromJson(parseObject(bytes))
    }

    /** Reads a preset object; throws [PresetFormatException]. */
    fun fromJson(root: JsonObject): Decoded {
        val format = root.string("format")
        if (format != FORMAT) throw PresetFormatException(PresetError.NOT_A_PRESET, "format is not $FORMAT")
        checkVersion(root.int("formatVersion"), FORMAT_VERSION, "formatVersion")
        checkVersion(root.int("recipeVersion"), RECIPE_VERSION, "recipeVersion")
        val patchObject = root["patch"] as? JsonObject ?: malformed("patch must be an object")
        val decodedPatch = PresetPatch.fromJson(patchObject)
        val smart = when (val element = root["smart"]) {
            null, JsonNull -> null

            is JsonObject -> {
                val size = element.toString().length
                if (size > PresetLimits.SMART_BYTES) tooLarge("smart part over ${PresetLimits.SMART_BYTES} bytes")
                SmartPart(element)
            }

            else -> malformed("smart must be an object")
        }
        val preset = Preset(
            id = id(root.string("id"), "id"),
            name = name(root.string("name"), "name", required = true),
            group = name(root.optionalString("group"), "group", required = false),
            author = name(root.optionalString("author"), "author", required = false),
            version = root.optionalInt("version")?.also { if (it < 1) invalid("version must be >= 1") } ?: 1,
            createdAt = created(root.optionalString("created")),
            patch = decodedPatch.patch,
            smart = smart,
        )
        return Decoded(preset, decodedPatch.ignored)
    }

    // ------------------------------------------------------------------ shared checks (also used by packs)

    internal fun parseObject(bytes: ByteArray): JsonObject = try {
        Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject
    } catch (e: SerializationException) {
        throw PresetFormatException(PresetError.MALFORMED, "not valid JSON", e)
    } catch (e: CharacterCodingException) {
        throw PresetFormatException(PresetError.MALFORMED, "not UTF-8", e)
    } ?: malformed("expected a JSON object")

    internal fun checkVersion(found: Int, supported: Int, what: String) {
        if (found < 1) invalid("$what must be >= 1")
        if (found > supported) {
            throw PresetFormatException(PresetError.NEWER_VERSION, "$what $found is newer than $supported")
        }
    }

    internal fun id(value: String, what: String): String {
        if (!PresetIds.isValid(value)) invalid("$what must be a lower-case version 4 UUID")
        return value
    }

    /** A display string: trimmed, no control characters, at most [PresetLimits.NAME] characters. */
    internal fun name(value: String?, what: String, required: Boolean): String {
        val text = value?.trim().orEmpty()
        if (required && text.isEmpty()) invalid("$what is required")
        if (text.length > PresetLimits.NAME) invalid("$what is longer than ${PresetLimits.NAME} characters")
        if (text.any { it.isISOControl() }) invalid("$what contains control characters")
        return text
    }

    private fun created(value: String?): Long = if (value == null) {
        0L
    } else {
        try {
            Instant.parse(value).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw PresetFormatException(PresetError.INVALID_VALUE, "created is not an ISO-8601 instant", e)
        }
    }

    internal fun JsonObject.string(key: String): String = optionalString(key) ?: malformed("$key is required")

    internal fun JsonObject.optionalString(key: String): String? = when (val e = this[key]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (e.isString) e.content else invalid("$key must be a string")
        else -> invalid("$key must be a string")
    }

    internal fun JsonObject.int(key: String): Int = optionalInt(key) ?: malformed("$key is required")

    internal fun JsonObject.optionalInt(key: String): Int? = when (val e = this[key]) {
        null, JsonNull -> null

        is JsonPrimitive -> if (e.isString) {
            invalid("$key must be an integer")
        } else {
            e.intOrNull
                ?: invalid("$key must be an integer")
        }

        else -> invalid("$key must be an integer")
    }

    internal fun malformed(message: String): Nothing = throw PresetFormatException(PresetError.MALFORMED, message)

    internal fun invalid(message: String): Nothing = throw PresetFormatException(PresetError.INVALID_VALUE, message)

    internal fun tooLarge(message: String): Nothing = throw PresetFormatException(PresetError.TOO_LARGE, message)
}
