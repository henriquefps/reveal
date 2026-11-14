@file:Suppress("ReturnCount", "CyclomaticComplexMethod", "LongMethod", "MagicNumber")

package dev.hfps.reveal.sync

import dev.hfps.reveal.sync.engine.CullingSync
import java.util.UUID
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * hoard-server's culling (Hoard ADR 0016) and device identity (Hoard ADR 0017) for [FakeSyncServer]: per-field records
 * with the conflict rule (clock clip, same value, never written, `baseRev`, last writer wins on time then device id),
 * the items feed with `include=culling`, live items with member files (a NAS shot), `replacedBy`, the bridge from photo
 * records of devices that are not culling-aware, and `GET/PATCH /v1/device` + claims.
 */
class FakeCulling(private val serverId: String, private val now: () -> Long) {
    class Field(var value: JsonElement, var rev: Long, var modifiedAt: Long, var deviceId: String)

    class Rec(val itemId: String, var rev: Long) {
        val fields = LinkedHashMap<String, Field>()
        var replacedBy: String? = null
    }

    val records = LinkedHashMap<String, Rec>()

    /** Live items the server indexes (a NAS shot): id -> member fingerprints, main first. */
    val items = LinkedHashMap<String, List<String>>()
    private val replaced = HashMap<String, String>()
    private var head = 0L

    /** Devices that declared `culling` or pushed to `/v1/culling` (never bridged). */
    val aware = HashSet<String>()

    /** Refuse every change of this field as `invalid_value` (a value the client thought valid). */
    var refuse: String? = null

    /** Every culling push received (tests count them). */
    val pushes = ArrayList<List<JsonObject>>()

    // device identity
    val app = HashMap<String, String?>()
    val manufacturer = HashMap<String, String?>()
    val model = HashMap<String, String?>()
    val physical = HashMap<String, String>()
    val reports = ArrayList<Pair<String, JsonObject>>()

    // ------------------------------------------------------------------ items

    /** A NAS shot of [fingerprints] (main first); its id is derived from the main file. Returns the id. */
    @Synchronized
    fun addItem(fingerprints: List<String>): String {
        val id = CullingSync.derivedItemId(fingerprints.first())
        items[id] = fingerprints
        records[id]?.let { touch(it) }
        return id
    }

    /** The shot's RAW appeared: a new item [newMain] + [others] replaces [oldId]; fields carried over (step 5). */
    @Synchronized
    fun replaceItem(oldId: String, newMain: String, others: List<String>): String {
        val newId = addItem(listOf(newMain) + others)
        items.remove(oldId)
        replaced[oldId] = newId
        val old = records[oldId]
        if (old != null) {
            old.replacedBy = newId
            for ((field, f) in old.fields) write(newId, field, f.value, null, f.modifiedAt, f.deviceId)
            touch(old)
        }
        return newId
    }

    private fun touch(rec: Rec) {
        head++
        rec.rev = head
        records.remove(rec.itemId)
        records[rec.itemId] = rec
    }

    private fun itemOf(fingerprint: String): String =
        items.entries.firstOrNull { fingerprint in it.value }?.key ?: CullingSync.derivedItemId(fingerprint)

    private fun follow(itemId: String): String {
        var id = itemId
        while (replaced[id] != null) id = replaced.getValue(id)
        return id
    }

    // ------------------------------------------------------------------ writes

    /** Result of one write: status, winner, rev, clipped. */
    data class Outcome(val status: String, val winner: String? = null, val rev: Long, val clipped: Boolean = false)

    @Synchronized
    fun write(itemId: String, field: String, value: JsonElement, baseRev: Long?, at: Long, device: String): Outcome {
        var t = at
        var clipped = false
        if (t > now() + 10 * 60 * 1000) {
            t = now()
            clipped = true
        }
        val rec = records.getOrPut(itemId) { Rec(itemId, 0) }
        val cur = rec.fields[field]
        if (cur != null && cur.value == value) {
            if (t > cur.modifiedAt || (t == cur.modifiedAt && device > cur.deviceId)) {
                cur.modifiedAt = t
                cur.deviceId = device
            }
            return Outcome("unchanged", rev = cur.rev, clipped = clipped)
        }
        if (cur == null || baseRev == cur.rev) {
            head++
            rec.fields[field] = Field(value, head, t, device)
            touch(rec)
            rec.rev = head
            return Outcome("applied", rev = head, clipped = clipped)
        }
        val incoming = t > cur.modifiedAt || (t == cur.modifiedAt && device > cur.deviceId)
        if (incoming) {
            head++
            rec.fields[field] = Field(value, head, t, device)
            touch(rec)
            rec.rev = head
        }
        return Outcome(
            "conflict",
            if (incoming) "incoming" else "server",
            rec.fields.getValue(field).rev,
            clipped,
        )
    }

    /** A rating set "through the server" by another app (Hoard, the web gallery): a write of [device]. */
    fun set(fingerprint: String, field: String, value: JsonElement, at: Long, device: String = "web:0123456789abcdef") =
        write(itemOf(fingerprint), field, value, null, at, device)

    fun field(itemId: String, field: String): Field? = records[itemId]?.fields?.get(field)

    /** The record a [fingerprint] lands on (a member of an item, else its derived id). */
    fun recordOf(fingerprint: String): Rec? = records[follow(itemOf(fingerprint))]

    @Synchronized
    fun push(device: String, body: JsonObject): JsonObject {
        aware += device
        val changes = body["changes"]!!.jsonArray.map { it.jsonObject }
        pushes += changes
        val results = buildJsonArray {
            changes.forEachIndexed { index, c ->
                val field = c["field"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val fingerprint = c["fingerprint"]?.jsonPrimitive?.contentOrNull
                val itemId = c["itemId"]?.jsonPrimitive?.contentOrNull
                val target = when {
                    fingerprint != null -> follow(itemOf(fingerprint))
                    itemId != null && (records.containsKey(itemId) || items.containsKey(itemId)) -> follow(itemId)
                    else -> null
                }
                val value = canonical(field, c["value"])
                val code = when {
                    target == null -> if (itemId != null) "unknown_item" else "invalid_target"

                    field !in setOf("rating", "flag", "label") -> "invalid_field"

                    field == refuse -> "invalid_value"

                    value == null && field == "label" &&
                        (c["value"]?.jsonPrimitive?.contentOrNull?.trim()?.length ?: 0) > 64 -> "label_too_long"

                    value == null -> "invalid_value"

                    (c["baseRev"]?.jsonPrimitive?.longOrNull ?: -1) < 0 -> "invalid_base_rev"

                    (c["modifiedAt"]?.jsonPrimitive?.longOrNull ?: 0) <= 0 -> "invalid_modified_at"

                    else -> null
                }
                if (code != null) {
                    add(
                        buildJsonObject {
                            put("index", index)
                            fingerprint?.let { put("fingerprint", it) }
                            itemId?.let { put("itemId", it) }
                            put("field", field)
                            put("status", "invalid")
                            put("code", code)
                            put("message", code)
                        },
                    )
                    return@forEachIndexed
                }
                val outcome = write(
                    target!!,
                    field,
                    value!!,
                    c["baseRev"]!!.jsonPrimitive.longOrNull,
                    c["modifiedAt"]!!.jsonPrimitive.longOrNull!!,
                    device,
                )
                add(
                    buildJsonObject {
                        put("index", index)
                        put("itemId", target)
                        put("field", field)
                        put("status", outcome.status)
                        outcome.winner?.let { put("winner", it) }
                        put("rev", outcome.rev)
                        if (outcome.clipped) put("clipped", true)
                    },
                )
            }
        }
        return buildJsonObject { put("results", results) }
    }

    private fun canonical(field: String, value: JsonElement?): JsonElement? {
        val p = value as? JsonPrimitive ?: return null
        return when (field) {
            "rating" -> p.intOrNull?.takeIf { it in 0..5 && !p.isString }?.let(::JsonPrimitive)

            "flag" -> p.contentOrNull?.takeIf {
                p.isString && it in setOf("pick", "reject", "none")
            }?.let(::JsonPrimitive)

            "label" -> p.contentOrNull?.takeIf { p.isString }?.trim()
                ?.takeIf { s -> s.none { it.code < 0x20 || it.code == 0x7f } && s.length <= 64 }?.let(::JsonPrimitive)

            else -> null
        }
    }

    // ------------------------------------------------------------------ feed

    @Synchronized
    fun page(cursor: String, limit: Int): JsonObject? {
        val since = if (cursor.isEmpty()) 0 else cursor.substringAfter(":i").toLongOrNull() ?: return null
        if (cursor.isNotEmpty() && !cursor.startsWith(serverId.take(8))) return null
        val changed = records.values.filter { it.rev > since }.sortedBy { it.rev }
        val page = changed.take(limit)
        val last = page.lastOrNull()?.rev ?: since
        return buildJsonObject {
            put("items", buildJsonArray {})
            put(
                "culling",
                buildJsonArray {
                    for (rec in page) {
                        add(
                            buildJsonObject {
                                put("itemId", rec.itemId)
                                put("rev", rec.rev)
                                put(
                                    "fingerprints",
                                    buildJsonArray {
                                        if (rec.replacedBy ==
                                            null
                                        ) {
                                            items[rec.itemId]?.forEach { add(JsonPrimitive(it)) }
                                        }
                                    },
                                )
                                rec.replacedBy?.let { put("replacedBy", follow(it)) }
                                for ((name, f) in rec.fields) {
                                    put(
                                        name,
                                        buildJsonObject {
                                            put("value", f.value)
                                            put("rev", f.rev)
                                            put("modifiedAt", f.modifiedAt)
                                            put("deviceId", f.deviceId)
                                        },
                                    )
                                }
                            },
                        )
                    }
                },
            )
            put("cursor", "${serverId.take(8)}:i$last")
            put("hasMore", changed.size > limit)
        }
    }

    // ------------------------------------------------------------------ the bridge (Hoard ADR 0016 "Reveal's records")

    /** A photo record [device] wrote: its culling fields that changed become writes, unless the device is aware. */
    @Synchronized
    fun bridge(device: String, before: JsonElement?, after: JsonElement?, at: Long) {
        if (device in aware) return
        val data = after as? JsonObject ?: return
        val fingerprint = data["fingerprint"]?.jsonPrimitive?.contentOrNull ?: return
        val old = before as? JsonObject
        val rating = data["rating"]?.jsonPrimitive?.intOrNull ?: 0
        val flag = data["flag"]?.jsonPrimitive?.contentOrNull ?: "NONE"
        val label = (data["label"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val oldRating = old?.get("rating")?.jsonPrimitive?.intOrNull ?: 0
        val oldFlag = old?.get("flag")?.jsonPrimitive?.contentOrNull ?: "NONE"
        val oldLabel = (old?.get("label") as? JsonPrimitive)?.contentOrNull.orEmpty()
        val target = follow(itemOf(fingerprint))
        val rejectZero = rating == 0 && flag == "REJECT" && oldFlag != "REJECT" && oldRating != 0
        if (rating != oldRating && !rejectZero) write(target, "rating", JsonPrimitive(rating), null, at, device)
        if (flag != oldFlag) write(target, "flag", JsonPrimitive(flag.lowercase()), null, at, device)
        if (label != oldLabel) write(target, "label", JsonPrimitive(label), null, at, device)
    }

    // ------------------------------------------------------------------ devices

    fun paired(device: String, body: JsonObject) {
        app[device] = body["app"]?.jsonPrimitive?.contentOrNull
        manufacturer[device] = body["manufacturer"]?.jsonPrimitive?.contentOrNull
        model[device] = body["model"]?.jsonPrimitive?.contentOrNull
        physical[device] = device
    }

    private fun entry(id: String, names: Map<String, String>) = buildJsonObject {
        put("id", id)
        put("name", names[id].orEmpty())
        put("app", app[id]?.let(::JsonPrimitive) ?: JsonNull)
        put("platform", "android")
        put("manufacturer", manufacturer[id]?.let(::JsonPrimitive) ?: JsonNull)
        put("model", model[id]?.let(::JsonPrimitive) ?: JsonNull)
    }

    fun candidates(device: String, names: Map<String, String>) = buildJsonArray {
        val mine = physical[device] ?: device
        val groups = names.keys.groupBy { physical[it] ?: it }.filterKeys { it != mine }
        val ranked = groups.entries.filter { (_, ids) -> ids.none { app[it] != null && app[it] == app[device] } }
            .sortedByDescending { (_, ids) -> ids.any { sameModel(it, device) } }
        for ((group, ids) in ranked) {
            add(
                buildJsonObject {
                    put("physicalDeviceId", group)
                    put("name", names[ids.first()].orEmpty())
                    put("match", if (ids.any { sameModel(it, device) }) "model" else "other")
                    put("devices", buildJsonArray { ids.forEach { add(entry(it, names)) } })
                },
            )
        }
    }

    private fun sameModel(a: String, b: String) = model[a] != null &&
        model[a].equals(model[b], ignoreCase = true) && manufacturer[a].equals(manufacturer[b], ignoreCase = true)

    fun view(device: String, names: Map<String, String>): JsonObject {
        val group = physical[device] ?: device
        return buildJsonObject {
            put("deviceId", device)
            put("name", names[device].orEmpty())
            put("platform", "android")
            put("app", app[device]?.let(::JsonPrimitive) ?: JsonNull)
            put("manufacturer", manufacturer[device]?.let(::JsonPrimitive) ?: JsonNull)
            put("model", model[device]?.let(::JsonPrimitive) ?: JsonNull)
            put("features", buildJsonArray { if (device in aware) add(JsonPrimitive("culling")) })
            put(
                "physicalDevice",
                buildJsonObject {
                    put("id", group)
                    val members = names.keys.filter { (physical[it] ?: it) == group }
                    put("name", names[members.firstOrNull() ?: device].orEmpty())
                    put("devices", buildJsonArray { members.forEach { add(entry(it, names)) } })
                },
            )
            put("candidates", candidates(device, names))
        }
    }

    fun patch(device: String, body: JsonObject) {
        reports += device to body
        body["app"]?.jsonPrimitive?.contentOrNull?.let { app[device] = it }
        body["manufacturer"]?.jsonPrimitive?.contentOrNull?.let { manufacturer[device] = it }
        body["model"]?.jsonPrimitive?.contentOrNull?.let { model[device] = it }
        if (body["features"]?.jsonArray?.any { it.jsonPrimitive.contentOrNull == "culling" } == true) aware += device
    }

    /** null = ok; else the error code (`not_found`, `same_app`). */
    fun claim(device: String, target: String?, names: Map<String, String>): String? {
        if (target == null) {
            physical[device] = if (names.keys.any { it != device && physical[it] == device }) {
                UUID.randomUUID().toString()
            } else {
                device
            }
            return null
        }
        val members = names.keys.filter { (physical[it] ?: it) == target }
        if (members.isEmpty()) return "not_found"
        if (members.any { it != device && app[it] != null && app[it] == app[device] }) return "same_app"
        physical[device] = target
        return null
    }
}
