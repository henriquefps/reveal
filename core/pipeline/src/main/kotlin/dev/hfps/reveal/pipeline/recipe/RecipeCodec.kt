package dev.hfps.reveal.pipeline.recipe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

/** Thrown when a stored recipe was written by a newer app than this one; it must never be silently downgraded. */
class UnsupportedRecipeVersionException(val found: Int, val supported: Int) :
    IllegalArgumentException("Recipe version $found is newer than the supported $supported")

/** Thrown when stored recipe JSON has no usable `v` field or no migrator for one of its versions. */
class InvalidRecipeException(message: String, cause: Throwable? = null) : IllegalArgumentException(message, cause)

/** One step of the migration chain: rewrites a JSON recipe of version `from` into version `from + 1`. */
fun interface RecipeMigrator {
    /** Returns the migrated object; the codec stamps the new `v` itself. */
    fun migrate(recipe: JsonObject): JsonObject
}

/**
 * JSON encoding of [Recipe] with explicit field names and the migration policy of docs/adr/0003:
 *
 * - Written with every field present (`encodeDefaults`), so a stored recipe is self-describing.
 * - Read: the `v` field selects the migrators `v -> v+1 -> ... -> currentVersion`; unknown fields at the current
 *   version are ignored (additive changes of a newer app within the same `v`), a `v` above [currentVersion] throws
 *   [UnsupportedRecipeVersionException].
 *
 * [currentVersion] and [migrators] are parameters so the migration machinery is testable before the first real
 * migration exists; production code uses [RecipeCodec.Default].
 */
class RecipeCodec(
    private val currentVersion: Int = RECIPE_VERSION,
    private val migrators: Map<Int, RecipeMigrator> = RECIPE_MIGRATORS,
) {
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    /** Serialises [recipe] to JSON. */
    fun encode(recipe: Recipe): String = json.encodeToString(Recipe.serializer(), recipe)

    /** Parses and migrates stored JSON into the current [Recipe]. */
    @Suppress("ThrowsCount")
    fun decode(text: String): Recipe {
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
        } catch (e: kotlinx.serialization.SerializationException) {
            throw InvalidRecipeException("Recipe is not valid JSON: ${e.message}", e)
        } ?: throw InvalidRecipeException("Recipe must be a JSON object")
        val version = try {
            root["v"]?.jsonPrimitive?.int
        } catch (e: IllegalArgumentException) {
            throw InvalidRecipeException("Recipe field v is not an integer: ${e.message}", e)
        } ?: throw InvalidRecipeException("Recipe has no version field v")
        if (version > currentVersion) throw UnsupportedRecipeVersionException(version, currentVersion)
        if (version < 1) throw InvalidRecipeException("Recipe version $version is not valid")
        var current = root
        for (from in version until currentVersion) {
            val migrator = migrators[from] ?: throw InvalidRecipeException("No migrator from recipe v$from")
            current = JsonObject(migrator.migrate(current) + ("v" to JsonPrimitive(from + 1)))
        }
        return json.decodeFromJsonElement(Recipe.serializer(), current)
    }

    companion object {
        /** The codec for the app's current schema. */
        val Default: RecipeCodec = RecipeCodec()
    }
}

/** Migrators by source version (`n` rewrites vN into vN+1). Empty until the first breaking change. */
val RECIPE_MIGRATORS: Map<Int, RecipeMigrator> = emptyMap()
