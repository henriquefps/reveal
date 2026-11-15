@file:Suppress("MagicNumber")

package dev.hfps.reveal.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.RecordedRequest

/** hoard-server 0.6.0's gallery previews (Hoard ADR 0015: warm, `GET .../preview`) for [FakeSyncServer]. */
class FakePreviews(private val culling: FakeCulling) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ok(body: kotlinx.serialization.json.JsonObject) = MockResponse.Builder().code(200)
        .addHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun notFound() = MockResponse.Builder().code(404).addHeader("Content-Type", "application/json")
        .body("{\"error\":{\"code\":\"preview_unavailable\",\"message\":\"no preview\"}}").build()

    /** A gallery preview of an item (Hoard ADR 0015): `ready` (with [v]), `pending` or `unavailable`. */
    class Preview(var status: String, var v: String? = null, var bytes: ByteArray = ByteArray(0))

    /** Thumb previews by item id (hoard-server 0.6.0 `previews`); an item without one is `pending`. */
    val thumbs = HashMap<String, Preview>()

    /** Every warm request's item ids, and every preview GET (`<item>?v=<v>&edit=<edit>`). */
    val warms = ArrayList<List<String>>()
    val previewGets = ArrayList<String>()

    /** Edited previews' thumbnails by version id (`GET .../preview?edit=`). */
    val editThumbs = HashMap<String, ByteArray>()

    fun warm(request: RecordedRequest): MockResponse {
        val ids = json.parseToJsonElement(request.body!!.utf8()).jsonObject["items"]!!.jsonArray
            .map { (it as JsonPrimitive).content }
        warms += ids
        return ok(
            buildJsonObject {
                put(
                    "items",
                    buildJsonArray {
                        for (id in ids.filter { it in culling.items }) {
                            val p = thumbs[id] ?: Preview("pending")
                            add(
                                buildJsonObject {
                                    put("id", id)
                                    put(
                                        "thumb",
                                        buildJsonObject {
                                            put("status", p.status)
                                            if (p.status == "ready") put("v", p.v)
                                            if (p.status == "unavailable") put("reason", "unsupported")
                                        },
                                    )
                                },
                            )
                        }
                    },
                )
                put(
                    "missing",
                    buildJsonArray {
                        ids.filter { it !in culling.items }.forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )
    }

    @Suppress("ReturnCount")
    fun get(request: RecordedRequest): MockResponse {
        val id = request.url.pathSegments[2]
        val v = request.url.queryParameter("v")
        val edit = request.url.queryParameter("edit")
        previewGets += "$id?v=$v&edit=$edit"
        if (edit != null) {
            val bytes = editThumbs[edit] ?: return notFound()
            return MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()
        }
        val p = thumbs[id] ?: return MockResponse.Builder().code(202).body("{\"status\":\"pending\"}").build()
        return when (p.status) {
            "ready" -> MockResponse.Builder().code(200).addHeader("X-Hoard-Preview-Version", p.v.orEmpty())
                .body(okio.Buffer().write(p.bytes)).build()

            "pending" -> MockResponse.Builder().code(202).body("{\"status\":\"pending\"}").build()

            else -> notFound()
        }
    }
}
