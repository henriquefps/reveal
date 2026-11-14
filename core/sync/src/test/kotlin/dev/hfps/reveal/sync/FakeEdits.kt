@file:Suppress("ReturnCount", "MagicNumber", "CyclomaticComplexMethod")

package dev.hfps.reveal.sync

import java.net.URLDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.RecordedRequest

/** hoard-server 0.9.0's edited previews (Hoard ADR 0020 "Upload", "GET and DELETE", lookup) for [FakeSyncServer]. */
class FakeEdits(private val culling: FakeCulling, private val now: () -> Long) {
    class Edit(
        var itemId: String,
        var digest: String,
        var label: String,
        var primary: Boolean,
        var virtualCopy: Boolean,
        var editedAt: Long,
        var bytes: ByteArray,
        var device: String,
    )

    val edits = LinkedHashMap<String, Edit>()

    /** Every PUT: version id and whether it carried an image. */
    val puts = ArrayList<Pair<String, Boolean>>()
    val deletes = ArrayList<String>()

    /** Answer the next PUT with this error (`status` to `code`, `reason`). */
    var failNext: Triple<Int, String, String?>? = null

    fun of(itemId: String) = edits.entries.filter { it.value.itemId == itemId }

    private fun json(versionId: String, e: Edit) = buildJsonObject {
        put("versionId", versionId)
        put("label", e.label)
        put("recipeDigest", e.digest)
        put("editedAt", e.editedAt)
        put("primary", e.primary)
        put("isVirtualCopy", e.virtualCopy)
        put("deviceId", e.device)
    }

    /** null = ok answer; else (status, code, details). */
    sealed interface Answer {
        data class Ok(val status: Int, val body: JsonObject) : Answer

        data class Error(val status: Int, val code: String, val details: JsonObject?) : Answer
    }

    @Synchronized
    fun put(device: String, itemId: String, versionId: String, request: RecordedRequest): Answer {
        val body = request.body?.toByteArray() ?: ByteArray(0)
        puts += versionId to body.isNotEmpty()
        failNext?.let { (status, code, reason) ->
            failNext = null
            return Answer.Error(status, code, reason?.let { buildJsonObject { put("reason", it) } })
        }
        if (itemId !in culling.items) return Answer.Error(404, "not_found", null)
        val digest = request.headers["X-Hoard-Recipe-Digest"] ?: return Answer.Error(400, "invalid_request", null)
        val at = minOf(request.headers["X-Hoard-Edited-At"]!!.toLong(), now() + 600_000)
        val label = URLDecoder.decode(request.headers["X-Hoard-Label"].orEmpty(), "UTF-8")
        val virtual = request.headers["X-Hoard-Virtual-Copy"] == "true"
        val primary = request.headers["X-Hoard-Primary"] == "true"
        val current = edits[versionId]
        val status: String
        when {
            current == null -> {
                if (body.isEmpty()) return imageRequired()
                edits[versionId] = Edit(itemId, digest, label, primary, virtual, at, body, device)
                status = "created"
            }

            at < current.editedAt -> return Answer.Error(
                409,
                "conflict",
                buildJsonObject {
                    put("reason", "older")
                    put("editedAt", current.editedAt)
                },
            )

            current.digest == digest -> {
                val same = current.label == label && current.primary == primary && current.virtualCopy == virtual &&
                    current.editedAt == at && current.itemId == itemId
                current.label = label
                current.primary = primary
                current.virtualCopy = virtual
                current.editedAt = at
                current.itemId = itemId
                status = if (same) "unchanged" else "updated"
            }

            else -> {
                if (body.isEmpty()) return imageRequired()
                edits[versionId] = Edit(itemId, digest, label, primary, virtual, at, body, device)
                status = "replaced"
            }
        }
        if (primary) of(itemId).filter { it.key != versionId }.forEach { it.value.primary = false }
        return Answer.Ok(
            if (status == "created") 201 else 200,
            buildJsonObject {
                put("status", status)
                put("itemId", itemId)
                put("rev", 1)
                put("edit", json(versionId, edits.getValue(versionId)))
            },
        )
    }

    private fun imageRequired() =
        Answer.Error(422, "invalid_request", buildJsonObject { put("reason", "image_required") })

    @Synchronized
    fun delete(itemId: String, versionId: String): Answer {
        deletes += versionId
        val gone = edits[versionId]?.takeIf { it.itemId == itemId }?.let { edits.remove(versionId) }
        return Answer.Ok(
            200,
            buildJsonObject {
                put("status", if (gone != null) "deleted" else "absent")
                put("itemId", itemId)
                put("rev", 1)
            },
        )
    }

    @Synchronized
    fun lookup(body: JsonObject): JsonObject {
        val fps = body["fingerprints"]!!.jsonArray.map { it.jsonPrimitive.content }
        val matches = fps.mapNotNull { fp ->
            culling.items.entries.firstOrNull { fp in it.value }?.let { fp to it.key }
        }
        return buildJsonObject {
            put(
                "items",
                buildJsonArray {
                    for (id in matches.map { it.second }.distinct()) {
                        add(
                            buildJsonObject {
                                put("id", id)
                                put("edits", buildJsonArray { of(id).forEach { add(json(it.key, it.value)) } })
                            },
                        )
                    }
                },
            )
            put(
                "matches",
                buildJsonArray {
                    matches.forEach { (fp, id) ->
                        add(
                            buildJsonObject {
                                put("fingerprint", fp)
                                put("itemId", id)
                                put("role", "main")
                            },
                        )
                    }
                },
            )
            put(
                "missing",
                buildJsonArray {
                    fps.filter { fp -> matches.none { it.first == fp } }.forEach { add(JsonPrimitive(it)) }
                },
            )
        }
    }
}
