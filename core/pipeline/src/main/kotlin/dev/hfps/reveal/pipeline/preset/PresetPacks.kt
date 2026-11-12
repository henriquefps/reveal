package dev.hfps.reveal.pipeline.preset

import dev.hfps.reveal.pipeline.preset.PresetFiles.int
import dev.hfps.reveal.pipeline.preset.PresetFiles.invalid
import dev.hfps.reveal.pipeline.preset.PresetFiles.malformed
import dev.hfps.reveal.pipeline.preset.PresetFiles.optionalInt
import dev.hfps.reveal.pipeline.preset.PresetFiles.optionalString
import dev.hfps.reveal.pipeline.preset.PresetFiles.string
import dev.hfps.reveal.pipeline.preset.PresetFiles.tooLarge
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URI
import java.net.URISyntaxException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** One group of a pack (it becomes a group of the preset library, keyed by [id]). */
data class PackGroup(val id: String, val name: String)

/**
 * A preset pack (docs/plan/distribution.md "Preset packs", docs/adr/0019 "Packs"): content, not code. Each preset's
 * [Preset.group] holds its group's name; [groupOf] maps preset id → [PackGroup.id].
 */
data class PresetPack(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    val licenceName: String,
    val licenceText: String,
    val link: String?,
    val groups: List<PackGroup>,
    val presets: List<Preset>,
    val groupOf: Map<String, String>,
    /** The cover image (JPEG or PNG bytes), or null. */
    val cover: ByteArray?,
    /** Patch paths the pack's presets skipped (unknown to this app), for a notice. */
    val ignored: List<String> = emptyList(),
) {
    override fun equals(other: Any?): Boolean = other is PresetPack && other.id == id && other.version == version &&
        other.presets == presets && other.groups == groups && other.cover.contentEquals(cover)

    override fun hashCode(): Int = id.hashCode() * 31 + version
}

/**
 * The `.revealpack` file (docs/adr/0019, "Packs"): a zip with `manifest.json` at its root:
 *
 * ```json
 * {"format": "reveal-pack", "formatVersion": 1, "id": "…uuid…", "name": "Golden hour", "author": "Ana",
 *  "version": 3, "link": "https://example.com/golden", "cover": "cover.jpg",
 *  "licence": {"name": "Personal use", "file": "LICENCE.txt"},
 *  "groups": [{"id": "…uuid…", "name": "Warm"}],
 *  "presets": [{"file": "presets/sunset.revealpreset", "group": "…group uuid…"}]}
 * ```
 *
 * Reading streams the zip once and never writes an entry to disk by its name. Entry names must be relative, use `/`,
 * have no `.`/`..`/empty segment, no control characters and be unique ([PresetError.UNSAFE_ENTRY]); sizes are counted
 * while decompressing (a zip bomb stops at the limit, whatever its headers claim): [PresetLimits].
 */
object PresetPacks {
    const val FORMAT: String = "reveal-pack"
    const val FORMAT_VERSION: Int = 1
    const val EXTENSION: String = "revealpack"
    const val MIME: String = "application/vnd.hfps.reveal.pack+zip"
    const val MANIFEST: String = "manifest.json"

    private const val MAX_NAME = 255
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())

    /** Reads a pack from [input] (not closed); throws [PresetFormatException]. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun read(input: InputStream): PresetPack {
        val entries = readEntries(input)
        val manifestBytes = entries[MANIFEST] ?: malformed("no $MANIFEST at the root")
        if (manifestBytes.size > PresetLimits.MANIFEST_BYTES) tooLarge("manifest too large")
        val m = PresetFiles.parseObject(manifestBytes)
        if (m.optionalString("format") != FORMAT) throw PresetFormatException(PresetError.NOT_A_PRESET, "not a pack")
        PresetFiles.checkVersion(m.int("formatVersion"), FORMAT_VERSION, "formatVersion")
        val id = PresetFiles.id(m.string("id"), "id")
        val name = PresetFiles.name(m.string("name"), "name", required = true)
        val author = PresetFiles.name(m.optionalString("author"), "author", required = false)
        val version = m.optionalInt("version")?.also { if (it < 1) invalid("version must be >= 1") } ?: 1
        val link = m.optionalString("link")?.let(::link)

        val licence = m["licence"] as? JsonObject ?: malformed("licence is required")
        val licenceName = PresetFiles.name(licence.string("name"), "licence name", required = true)
        val licenceText = licence.optionalString("file")?.let { file ->
            val bytes = entries[file] ?: malformed("licence file $file is missing")
            if (bytes.size > PresetLimits.LICENCE_BYTES) tooLarge("licence over ${PresetLimits.LICENCE_BYTES} bytes")
            try {
                bytes.decodeToString(throwOnInvalidSequence = true)
            } catch (e: CharacterCodingException) {
                throw PresetFormatException(PresetError.MALFORMED, "licence is not UTF-8", e)
            }
        }.orEmpty()

        val cover = m.optionalString("cover")?.let { file ->
            val bytes = entries[file] ?: malformed("cover $file is missing")
            if (bytes.size > PresetLimits.COVER_BYTES) tooLarge("cover over ${PresetLimits.COVER_BYTES} bytes")
            if (!bytes.startsWith(JPEG_MAGIC) && !bytes.startsWith(PNG_MAGIC)) invalid("cover must be JPEG or PNG")
            bytes
        }

        val groupsJson = m["groups"] as? JsonArray ?: malformed("groups must be a list")
        if (groupsJson.isEmpty()) malformed("a pack has at least one group")
        if (groupsJson.size > PresetLimits.PACK_GROUPS) tooLarge("more than ${PresetLimits.PACK_GROUPS} groups")
        val groups = groupsJson.map { element ->
            val g = element as? JsonObject ?: malformed("a group must be an object")
            PackGroup(PresetFiles.id(g.string("id"), "group id"), PresetFiles.name(g.string("name"), "group", true))
        }
        if (groups.map { it.id }.toSet().size != groups.size) invalid("duplicate group id")
        val groupIds = groups.associateBy { it.id }

        val presetsJson = m["presets"] as? JsonArray ?: malformed("presets must be a list")
        if (presetsJson.isEmpty()) malformed("a pack has at least one preset")
        if (presetsJson.size > PresetLimits.PACK_PRESETS) tooLarge("more than ${PresetLimits.PACK_PRESETS} presets")
        val presets = ArrayList<Preset>(presetsJson.size)
        val groupOf = LinkedHashMap<String, String>()
        val ignored = LinkedHashSet<String>()
        for (element in presetsJson) {
            val ref = element as? JsonObject ?: malformed("a preset entry must be an object")
            val file = ref.string("file")
            val group = groupIds[ref.string("group")] ?: invalid("preset $file names an unknown group")
            val bytes = entries[file] ?: malformed("preset file $file is missing")
            val decoded = PresetFiles.decode(bytes)
            if (decoded.preset.id in groupOf) invalid("duplicate preset id ${decoded.preset.id}")
            presets += decoded.preset.copy(group = group.name, author = decoded.preset.author.ifEmpty { author })
            groupOf[decoded.preset.id] = group.id
            ignored += decoded.ignored
        }
        return PresetPack(
            id = id,
            name = name,
            author = author,
            version = version,
            licenceName = licenceName,
            licenceText = licenceText,
            link = link,
            groups = groups,
            presets = presets,
            groupOf = groupOf,
            cover = cover,
            ignored = ignored.toList(),
        )
    }

    /** Writes [pack] as a `.revealpack` zip to [output] (not closed). */
    fun write(pack: PresetPack, output: OutputStream) {
        val zip = ZipOutputStream(output)
        fun put(name: String, bytes: ByteArray) {
            zip.putNextEntry(ZipEntry(name))
            zip.write(bytes)
            zip.closeEntry()
        }
        val presetRefs = pack.presets.mapIndexed { i, preset ->
            val file = "presets/${i + 1}.${PresetFiles.EXTENSION}"
            put(file, PresetFiles.encode(preset))
            JsonObject(
                mapOf("file" to JsonPrimitive(file), "group" to JsonPrimitive(pack.groupOf.getValue(preset.id))),
            )
        }
        val coverName = pack.cover?.let { if (it.startsWith(PNG_MAGIC)) "cover.png" else "cover.jpg" }
        pack.cover?.let { put(checkNotNull(coverName), it) }
        val licenceFile = if (pack.licenceText.isNotEmpty()) "LICENCE.txt" else null
        licenceFile?.let { put(it, pack.licenceText.toByteArray()) }
        val manifest = JsonObject(
            buildMap {
                put("format", JsonPrimitive(FORMAT))
                put("formatVersion", JsonPrimitive(FORMAT_VERSION))
                put("id", JsonPrimitive(pack.id))
                put("name", JsonPrimitive(pack.name))
                put("author", JsonPrimitive(pack.author))
                put("version", JsonPrimitive(pack.version))
                pack.link?.let { put("link", JsonPrimitive(it)) }
                coverName?.let { put("cover", JsonPrimitive(it)) }
                put(
                    "licence",
                    JsonObject(
                        buildMap {
                            put("name", JsonPrimitive(pack.licenceName))
                            licenceFile?.let { put("file", JsonPrimitive(it)) }
                        },
                    ),
                )
                put(
                    "groups",
                    JsonArray(
                        pack.groups.map {
                            JsonObject(mapOf("id" to JsonPrimitive(it.id), "name" to JsonPrimitive(it.name)))
                        },
                    ),
                )
                put("presets", JsonArray(presetRefs))
            },
        )
        put(MANIFEST, PresetFiles.json.encodeToString(JsonElement.serializer(), manifest).toByteArray())
        zip.finish()
    }

    /** Every entry of the zip by name, sizes counted while inflating. */
    @Suppress("ThrowsCount") // one code per failure class
    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val counted = LimitedInputStream(input, PresetLimits.PACK_BYTES)
        val entries = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(counted).use { zip -> readAll(zip, entries) }
        } catch (e: PresetFormatException) {
            throw e
        } catch (e: LimitedInputStream.LimitExceeded) {
            throw PresetFormatException(PresetError.TOO_LARGE, "pack over ${PresetLimits.PACK_BYTES} bytes", e)
        } catch (e: IOException) {
            throw PresetFormatException(PresetError.MALFORMED, "not a valid zip", e)
        } catch (e: IllegalArgumentException) {
            // ZipInputStream throws this for malformed entry names (bad UTF-8).
            throw PresetFormatException(PresetError.MALFORMED, "not a valid zip", e)
        }
        if (entries.isEmpty()) malformed("empty or not a zip")
        return entries
    }

    private fun readAll(zip: ZipInputStream, entries: MutableMap<String, ByteArray>) {
        var total = 0L
        var entry = zip.nextEntry
        while (entry != null) {
            val name = entry.name
            checkEntryName(name)
            if (!entry.isDirectory) {
                if (name in entries) throw PresetFormatException(PresetError.UNSAFE_ENTRY, "duplicate entry $name")
                if (entries.size >= PresetLimits.PACK_ENTRIES) tooLarge("too many entries")
                val bytes = readBounded(zip, PresetLimits.COVER_BYTES.toLong(), name)
                total += bytes.size
                if (total > PresetLimits.PACK_UNCOMPRESSED_BYTES) tooLarge("pack too large once decompressed")
                entries[name] = bytes
            }
            entry = zip.nextEntry
        }
    }

    /** Entry names that could escape a directory or confuse a reader are refused, even though none is written out. */
    internal fun checkEntryName(name: String) {
        val segments = name.trimEnd('/').split('/')
        val unsafe = name.isEmpty() || name.length > MAX_NAME || name.startsWith('/') || '\\' in name ||
            name.any { it.isISOControl() } || ':' in name ||
            segments.any { it.isEmpty() || it == "." || it == ".." }
        if (unsafe) throw PresetFormatException(PresetError.UNSAFE_ENTRY, "unsafe entry name")
    }

    private fun readBounded(input: InputStream, max: Long, name: String): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER)
        var read = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            read += n
            if (read > max) tooLarge("entry $name over $max bytes")
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    private fun link(value: String): String {
        if (value.length > PresetLimits.LINK) invalid("link too long")
        val uri = try {
            URI(value)
        } catch (e: URISyntaxException) {
            throw PresetFormatException(PresetError.INVALID_VALUE, "link is not a URL", e)
        }
        if (uri.scheme != "https" || uri.host.isNullOrEmpty()) invalid("link must be an https URL")
        return value
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private const val BUFFER = 16 * 1024
}

/** Counts bytes read from [input] and throws [LimitExceeded] past [limit]. */
internal class LimitedInputStream(private val input: InputStream, private val limit: Long) : InputStream() {
    class LimitExceeded : IOException("limit exceeded")

    private var count = 0L

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) bump(1)
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) bump(n.toLong())
        return n
    }

    private fun bump(n: Long) {
        count += n
        if (count > limit) throw LimitExceeded()
    }

    // The caller owns [input].
    override fun close() = Unit
}
