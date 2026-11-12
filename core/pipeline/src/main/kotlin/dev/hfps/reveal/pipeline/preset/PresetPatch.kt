package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.recipe.CurvePoint
import dev.hfps.reveal.pipeline.recipe.Curves
import dev.hfps.reveal.pipeline.recipe.Recipe
import dev.hfps.reveal.pipeline.recipe.RecipeCodec
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a preset sets: a partial recipe (docs/adr/0019, "Patch"). [values] maps each set [PresetField] path to its JSON
 * value in the recipe's own encoding (ADR 0003): `light.exposure` → `0.3`, `profile.base` → `"vivid"`,
 * `light.curve.rgb` → `[{"x":0,"y":0},…]`, `colour.whiteBalance.temperature` → `null` (as shot). Every field it does
 * not name keeps the photo's value. Immutable; values are validated and clamped on construction by [of] / [fromJson].
 */
class PresetPatch private constructor(val values: Map<String, JsonElement>) {
    /** The set field paths, in recipe order. */
    val paths: Set<String> get() = values.keys

    /** The chooser sections this patch touches. */
    val sections: Set<PresetSection> get() = values.keys.mapTo(LinkedHashSet()) { PresetSection.of(it) }

    val isEmpty: Boolean get() = values.isEmpty()

    /** The patch as a nested partial recipe object, the `patch` member of a `.revealpreset` file. */
    fun toJson(): JsonObject {
        var root = JsonObject(emptyMap())
        for ((path, value) in values) root = JsonTree.set(root, path.split('.'), value)
        return root
    }

    /** [before] with every set field at the preset's value (100 % strength, no scaling maths). */
    fun applyFull(before: Recipe): Recipe = apply(before, 1f)

    /**
     * [before] with this patch applied at [strength] (0..2, 1 = the preset as designed): each set field is scaled from
     * the photo's value towards the preset's by its [FieldKind] ([PresetStrength]); unset fields keep [before]'s.
     */
    @Suppress("ReturnCount")
    fun apply(before: Recipe, strength: Float, context: ApplyContext = ApplyContext()): Recipe {
        if (values.isEmpty()) return before
        val s = strength.coerceIn(0f, MAX_STRENGTH)
        if (s == 0f) return before
        val beforeJson = JsonObjectParser.parse(RecipeCodec.Default.encode(before))
        val merged = PresetStrength.blend(beforeJson, values, s, context)
        return RecipeCodec.Default.decode(merged.toString())
    }

    override fun equals(other: Any?): Boolean = other is PresetPatch && other.values == values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "PresetPatch(${toJson()})"

    companion object {
        /** The strongest strength (200 %). */
        const val MAX_STRENGTH: Float = 2f

        /** Most fields one patch may set (the registry holds fewer; a bigger file is hostile). */
        const val MAX_FIELDS: Int = 1024

        val EMPTY: PresetPatch = PresetPatch(emptyMap())

        /** The patch that sets [paths] (registry paths; others are dropped) to their values in [recipe]. */
        fun fromRecipe(recipe: Recipe, paths: Collection<String>): PresetPatch {
            val json = JsonObjectParser.parse(RecipeCodec.Default.encode(recipe))
            val wanted = paths.toSet()
            val values = LinkedHashMap<String, JsonElement>()
            for (field in PresetFields.all) {
                if (field.path in wanted) values[field.path] = JsonTree.get(json, field.segments) ?: JsonNull
            }
            return PresetPatch(values)
        }

        /** The patch of [sections] of [recipe] ("Create preset"). */
        fun fromSections(recipe: Recipe, sections: Collection<PresetSection>): PresetPatch =
            fromRecipe(recipe, PresetFields.of(sections))

        /**
         * Reads a nested partial recipe (the `patch` of a file). Unknown or not presettable paths, and enum values this
         * app does not know (a newer app's), are skipped and listed in [Decoded.ignored]; a value of the wrong type,
         * a non-finite number or a malformed curve throws [PresetFormatException]. Numbers outside their control's
         * range are clamped.
         */
        fun fromJson(patch: JsonObject): Decoded {
            val leaves = ArrayList<Pair<String, JsonElement>>()
            JsonTree.leaves(patch, "", leaves, PresetFields)
            if (leaves.size > MAX_FIELDS) throw PresetFormatException(PresetError.TOO_LARGE, "too many fields")
            val ignored = ArrayList<String>()
            val byPath = HashMap<String, JsonElement>()
            for ((path, value) in leaves) {
                val field = PresetFields[path]
                if (field == null) {
                    ignored += path
                    continue
                }
                when (val checked = Validation.check(field, value)) {
                    null -> ignored += path
                    else -> byPath[path] = checked
                }
            }
            val ordered = LinkedHashMap<String, JsonElement>()
            for (field in PresetFields.all) byPath[field.path]?.let { ordered[field.path] = it }
            val result = PresetPatch(ordered)
            // The whole patch must make a valid recipe (e.g. a curve's constraints across fields).
            try {
                result.apply(Recipe.DEFAULT, 1f)
            } catch (e: IllegalArgumentException) {
                throw PresetFormatException(PresetError.INVALID_VALUE, "patch does not make a valid recipe", e)
            }
            return Decoded(result, ignored)
        }
    }

    /** A decoded patch and the paths it skipped (unknown, not presettable, or an unknown enum value). */
    data class Decoded(val patch: PresetPatch, val ignored: List<String>)
}

/**
 * What a strength between the photo and the preset needs to know about the photo: the camera's as-shot white balance
 * (kelvin and tint) for a RAW, so a preset that sets temperature can blend from "as shot". Unknown (null) makes the
 * absolute white balance switch at 50 % like a mode.
 */
data class ApplyContext(val asShotTemperatureK: Float? = null, val asShotTint: Float? = null)

/** Type and range checks of one patch value against the recipe's schema. */
internal object Validation {
    private const val MAX_CURVE_POINTS = Curves.MAX_POINTS

    /** Allowed serial names of enum leaves, by path (from the recipe's serial descriptors). */
    private val enums: Map<String, Set<String>> = buildMap {
        fun walk(descriptor: SerialDescriptor, prefix: String) {
            for (i in 0 until descriptor.elementsCount) {
                val name = descriptor.getElementName(i)
                val path = if (prefix.isEmpty()) name else "$prefix.$name"
                val child = descriptor.getElementDescriptor(i)
                when (child.kind) {
                    SerialKind.ENUM -> put(path, (0 until child.elementsCount).map(child::getElementName).toSet())
                    is kotlinx.serialization.descriptors.StructureKind.CLASS -> walk(child, path)
                    else -> Unit
                }
            }
        }
        walk(Recipe.serializer().descriptor, "")
    }

    /** The value to store (clamped), null to skip it (an unknown enum value), or throws for a malformed one. */
    fun check(field: PresetField, value: JsonElement): JsonElement? {
        val default = JsonTree.get(PresetFields.defaultJson, field.segments)
        return when {
            field.kind == FieldKind.CURVE -> curve(field.path, value)
            field.kind == FieldKind.KELVIN || field.kind == FieldKind.TINT -> nullableNumber(field, value)
            field.path in enums -> enumValue(field.path, value)
            default is JsonPrimitive && default.booleanOrNull != null -> boolean(field.path, value)
            default is JsonPrimitive && !default.isString -> number(field, value)
            else -> throw PresetFormatException(PresetError.INVALID_VALUE, "${field.path}: unsupported value")
        }
    }

    private fun enumValue(path: String, value: JsonElement): JsonElement? {
        val primitive = value as? JsonPrimitive
        if (primitive == null || !primitive.isString) bad(path, "expected a string")
        return if (primitive.content in enums.getValue(path)) primitive else null
    }

    private fun boolean(path: String, value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive
        if (primitive == null || primitive.isString || primitive.booleanOrNull == null) bad(path, "expected a boolean")
        return primitive
    }

    private fun number(field: PresetField, value: JsonElement): JsonElement {
        val primitive = value as? JsonPrimitive
        val number = primitive?.takeIf { !it.isString }?.doubleOrNull
        if (number == null || !number.isFinite()) bad(field.path, "expected a finite number")
        val clamped = field.range?.clamp(number.toFloat()) ?: number.toFloat()
        return JsonPrimitive(clamped)
    }

    private fun nullableNumber(field: PresetField, value: JsonElement): JsonElement =
        if (value is JsonNull) value else number(field, value)

    private fun curve(path: String, value: JsonElement): JsonElement {
        val array = value as? JsonArray ?: bad(path, "expected a list of points")
        if (array.isNotEmpty() &&
            array.size !in 2..MAX_CURVE_POINTS
        ) {
            bad(path, "a curve has 2..$MAX_CURVE_POINTS points")
        }
        var lastX = -1.0
        for (point in array) {
            val obj = point as? JsonObject ?: bad(path, "expected {x, y} points")
            val x = obj["x"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
            val y = obj["y"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
            if (x == null || y == null) bad(path, "points need numeric x and y")
            if (x !in 0.0..1.0 || y !in 0.0..1.0) bad(path, "points lie in [0, 1]")
            if (x <= lastX) bad(path, "point x must increase")
            lastX = x
        }
        return JsonArray(array.map { JsonObject(mapOf("x" to it.jsonObject["x"]!!, "y" to it.jsonObject["y"]!!)) })
    }

    private fun bad(path: String, message: String): Nothing =
        throw PresetFormatException(PresetError.INVALID_VALUE, "$path: $message")
}

/** Path access on JSON trees (immutable). */
internal object JsonTree {
    fun get(root: JsonObject, segments: List<String>): JsonElement? {
        var node: JsonElement = root
        for (key in segments) node = (node as? JsonObject)?.get(key) ?: return null
        return node
    }

    fun set(root: JsonObject, segments: List<String>, value: JsonElement): JsonObject {
        val key = segments.first()
        if (segments.size == 1) return JsonObject(root + (key to value))
        val child = root[key] as? JsonObject ?: JsonObject(emptyMap())
        return JsonObject(root + (key to set(child, segments.drop(1), value)))
    }

    /**
     * The leaves of a partial recipe: a path is a leaf when the registry knows it, when it is excluded, or when the
     * value is not an object (so a curve's array or a null temperature are leaves).
     */
    fun leaves(node: JsonObject, prefix: String, out: MutableList<Pair<String, JsonElement>>, fields: PresetFields) {
        for ((key, value) in node) {
            val path = if (prefix.isEmpty()) key else "$prefix.$key"
            if (value is JsonObject && fields[path] == null && !fields.isExcluded(path)) {
                leaves(value, path, out, fields)
            } else {
                out += path to value
            }
        }
    }

    fun curvePoints(value: JsonElement?): List<CurvePoint> = (value as? JsonArray).orEmpty().map {
        val obj = it.jsonObject
        CurvePoint(obj.getValue("x").jsonPrimitive.content.toFloat(), obj.getValue("y").jsonPrimitive.content.toFloat())
    }
}
