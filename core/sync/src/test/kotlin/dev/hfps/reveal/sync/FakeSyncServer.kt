@file:Suppress(
    "LongMethod",
    "CyclomaticComplexMethod",
    "ReturnCount",
    "LongParameterList",
    "LoopWithTooManyJumpStatements",
)

package dev.hfps.reveal.sync

import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest

/**
 * An in-memory `reveal-sync` for JVM tests, following ADR 0017: device keys, the change feed (one revision counter,
 * latest state once, tombstones), the conflict rule (last write wins on modifiedAt then device id, the losing version
 * kept as a copy), fingerprint lookups, chunked resumable uploads, ranges and the NAS feed. Hooks let a test break an
 * upload half-way.
 */
class FakeSyncServer(val serverId: String = "858e1b9d-0000-4000-8000-000000000001") : Dispatcher() {
    private val json = Json { ignoreUnknownKeys = true }

    class Record(
        val kind: String,
        val id: String,
        var rev: Long,
        var deleted: Boolean,
        var modifiedAt: Long,
        var deviceId: String,
        val copyOf: String?,
        var data: JsonElement?,
    )

    class Upload(
        val id: String,
        val device: String,
        val sha: String,
        val size: Long,
        val kind: String,
        val fp: String?,
    ) {
        val bytes = java.io.ByteArrayOutputStream()
    }

    val records = LinkedHashMap<Pair<String, String>, Record>()
    val devices = LinkedHashMap<String, String>() // key -> device id
    val deviceNames = LinkedHashMap<String, String>()
    val blobs = HashMap<String, ByteArray>()
    val blobKinds = HashMap<String, String>() // sha -> kind
    val blobOwners = HashMap<String, String>() // sha -> uploading device

    /** Answer `DELETE /v1/blobs/{sha}` like a server before 0.5.2 (`405`). */
    var dropUnsupported = false
    val originals = HashMap<String, String>() // fingerprint -> sha (backup)
    val nasFiles = ArrayList<JsonObject>()
    val nasBytes = HashMap<String, ByteArray>() // fingerprint -> bytes
    val uploads = HashMap<String, Upload>()
    private var head = 0L
    val tokens = HashSet<String>()

    /** Bytes of a PATCH to keep before answering as an interrupted chunk (once), or -1. */
    var cutNextChunkAt = -1

    /** Fail the next N PATCH requests with 503 before reading them. */
    var failPatches = 0
    val patchCount = AtomicInteger()
    var chunkSize = 1024L

    /** Answer the next N authenticated requests with `429` + `Retry-After: 1`. */
    var rateLimitNext = 0

    private val server = MockWebServer().also { it.dispatcher = this }

    /** `limits.maxPushChanges` of `/v1/server`, enforced like hoard-server (a longer push is `422 invalid_change`). */
    var maxPushChanges = 500

    /** Every `Authorization` header this server received, in order (a key must never reach the wrong server). */
    val authorizations: MutableList<String> = java.util.Collections.synchronizedList(ArrayList())

    /** `features` of `/v1/server`: empty = a server before hoard-server 0.7.0 (no culling, no device identity). */
    val features = ArrayList<String>()

    /** The server's clock (the culling clock guard). */
    var clock: () -> Long = System::currentTimeMillis

    /** Culling records, items and device identity (hoard-server 0.7.0). */
    val culling = FakeCulling(serverId) { clock() }

    /** `GET /v1/items/{id}` answers (Hoard ADR 0014), by item id; unknown ids are 404. */
    val itemRecords = HashMap<String, kotlinx.serialization.json.JsonObject>()

    /** Edited previews (hoard-server 0.9.0). */
    val edits = FakeEdits(culling) { clock() }

    /** Gallery previews (hoard-server 0.6.0, Hoard ADR 0015). */
    val previews = FakePreviews(culling)

    /** Turns on what hoard-server 0.7.0+ lists ([edits]: 0.9.0 with previews on). */
    fun hoard(edits: Boolean = false) {
        features.clear()
        features += listOf("items", "culling", "device-identity")
        if (edits) features += listOf("previews", "edits")
    }

    fun start(): String {
        server.start()
        return server.url("/").toString()
    }

    fun shutdown() = server.close()

    /** A pairing token a test can redeem (as the admin page would show). */
    fun pairingToken(): String = UUID.randomUUID().toString().also { tokens += it }

    /** Adds a device directly; returns its key. */
    fun addDevice(name: String): String {
        val id = UUID.randomUUID().toString()
        val key = "rvk1_${id.replace("-", "").take(16)}_${"k".repeat(43)}"
        devices[key] = id
        deviceNames[id] = name
        return key
    }

    /** Revokes a device as the admin page does: its key answers `401`, it is no longer listed (ADR 0017). */
    @Synchronized
    fun revoke(deviceId: String) {
        devices.entries.removeIf { it.value == deviceId }
        deviceNames.remove(deviceId)
    }

    /** How many culling pages were asked for (a hand-off's pull, a sync run's). */
    @Volatile
    var cullingPages = 0

    @Synchronized
    override fun dispatch(request: RecordedRequest): MockResponse {
        val url = request.url
        val path = url.encodedPath
        val method = request.method
        if (path == "/v1/pair" && method == "POST") return pair(request)
        if (path == "/v1/health") {
            return ok(
                buildJsonObject {
                    put("status", "ok")
                    put("api", 1)
                },
            )
        }
        request.headers["Authorization"]?.let { authorizations += it }
        val device = request.headers["Authorization"]?.removePrefix("Bearer ")?.let { devices[it] }
            ?: return error(401, "unauthorized")
        if (rateLimitNext > 0) {
            rateLimitNext--
            return error(429, "rate_limited")
        }
        return when {
            path == "/v1/server" -> ok(
                buildJsonObject {
                    put("serverId", serverId)
                    put("serverName", "Test")
                    put("api", 1)
                    put("deviceId", device)
                    put("storageConfigured", true)
                    put("features", buildJsonArray { features.forEach { add(JsonPrimitive(it)) } })
                    put(
                        "limits",
                        buildJsonObject {
                            put("maxPushChanges", maxPushChanges)
                            put("maxPullLimit", 500)
                            put("chunkSize", chunkSize)
                        },
                    )
                },
            )

            path == "/v1/devices" -> ok(
                buildJsonObject {
                    put(
                        "devices",
                        buildJsonArray {
                            deviceNames.forEach { (id, name) ->
                                add(
                                    buildJsonObject {
                                        put("id", id)
                                        put("name", name)
                                        put("platform", "android")
                                    },
                                )
                            }
                        },
                    )
                },
            )

            path == "/v1/changes" && method == "GET" -> pull(
                url.queryParameter("cursor").orEmpty(),
                url.queryParameter("limit")?.toInt() ?: 200,
            )

            path == "/v1/changes" && method == "POST" -> push(device, request)

            path == "/v1/fingerprints/lookup" -> lookup(request)

            path == "/v1/items/lookup" && "items" in features ->
                ok(edits.lookup(json.parseToJsonElement(request.body!!.utf8()).jsonObject))

            path == "/v1/items/previews" && method == "POST" && "previews" in features -> previews.warm(request)

            path.matches(Regex("/v1/items/[^/]+/preview")) && "previews" in features -> previews.get(request)

            path.matches(Regex("/v1/items/[^/]+/edits/[^/]+")) && "edits" in features -> {
                val parts = url.pathSegments
                val answer = if (method == "DELETE") {
                    edits.delete(parts[2], parts[4])
                } else {
                    edits.put(device, parts[2], parts[4], request)
                }
                when (answer) {
                    is FakeEdits.Answer.Ok -> MockResponse.Builder().code(answer.status)
                        .addHeader("Content-Type", "application/json").body(answer.body.toString()).build()

                    is FakeEdits.Answer.Error -> error(answer.status, answer.code, answer.details)
                }
            }

            method == "GET" && url.pathSegments.size == 3 && path.startsWith("/v1/items/") &&
                path != "/v1/items/changes" && "items" in features ->
                itemRecords[url.pathSegments[2]]?.let(::ok) ?: error(404, "not_found")

            path == "/v1/culling" && method == "POST" && "culling" in features ->
                ok(culling.push(device, json.parseToJsonElement(request.body!!.utf8()).jsonObject))

            path == "/v1/items/changes" && "items" in features -> {
                val include = url.queryParameter("include").orEmpty().split(',')
                if ("culling" !in include || "culling" !in features) {
                    ok(
                        buildJsonObject {
                            put("items", buildJsonArray {})
                            put("cursor", "")
                            put("hasMore", false)
                        },
                    )
                } else {
                    cullingPages++
                    culling.page(url.queryParameter("cursor").orEmpty(), url.queryParameter("limit")?.toInt() ?: 500)
                        ?.let(::ok) ?: error(400, "invalid_cursor")
                }
            }

            path == "/v1/device" && "device-identity" in features && method == "GET" ->
                ok(culling.view(device, deviceNames))

            path == "/v1/device" && "device-identity" in features && method == "PATCH" -> {
                culling.patch(device, json.parseToJsonElement(request.body!!.utf8()).jsonObject)
                ok(culling.view(device, deviceNames))
            }

            path == "/v1/device/claim" && "device-identity" in features -> {
                val target = json.parseToJsonElement(request.body!!.utf8()).jsonObject["physicalDeviceId"]
                    ?.let { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                when (culling.claim(device, target, deviceNames)) {
                    null -> ok(culling.view(device, deviceNames))
                    "not_found" -> error(404, "not_found")
                    else -> error(409, "conflict", buildJsonObject { put("reason", "same_app") })
                }
            }

            path == "/v1/uploads" && method == "POST" -> createUpload(device, request)

            path.startsWith("/v1/uploads/") -> upload(device, path.removePrefix("/v1/uploads/"), request)

            path.startsWith("/v1/originals/") && method == "DELETE" -> deleteOriginal(url.pathSegments.last())

            path.startsWith("/v1/originals/") -> serve(request, original(url.pathSegments.last()))

            path.startsWith("/v1/blobs/") && method == "DELETE" -> drop(device, url.pathSegments.last())

            path.startsWith("/v1/blobs/") -> serve(request, blobs[url.pathSegments.last()])

            path == "/v1/nas/files" -> ok(
                buildJsonObject {
                    put("files", JsonArray(nasFiles))
                    put("cursor", "${serverId.take(8)}:n${nasFiles.size}")
                    put("hasMore", false)
                },
            )

            else -> error(404, "not_found")
        }
    }

    /** ADR 0017 "Dropping a smart preview": the uploader's own preview, once no live photo record names it. */
    private fun drop(device: String, sha: String): MockResponse {
        if (dropUnsupported) return error(405, "method_not_allowed")
        if (!blobs.containsKey(sha)) return error(404, "not_found")
        if (blobKinds[sha] != "preview" || blobOwners[sha] != device) return error(403, "forbidden")
        if (previewNamed(sha)) return error(409, "conflict", buildJsonObject { put("reason", "referenced") })
        blobs.remove(sha)
        blobKinds.remove(sha)
        blobOwners.remove(sha)
        return MockResponse.Builder().code(204).build()
    }

    /** Answer `DELETE /v1/originals/{fp}` like a server before 0.5.3 (`405`). */
    var deleteOriginalUnsupported = false

    /** ADR 0017 "Deleting an original": an uploaded original no live photo record names. */
    private fun deleteOriginal(fp: String): MockResponse {
        if (deleteOriginalUnsupported) return error(405, "method_not_allowed")
        val sha = originals[fp]
        if (sha == null) {
            return if (nasBytes.containsKey(fp)) {
                error(403, "forbidden", buildJsonObject { put("reason", "nas") })
            } else {
                error(404, "not_found")
            }
        }
        val named = records.values.any { r ->
            r.kind == "photo" && !r.deleted && r.data?.toString()?.contains("\"$fp\"") == true
        }
        if (named) return error(409, "conflict", buildJsonObject { put("reason", "referenced") })
        originals.remove(fp)
        if (originals.values.none { it == sha }) {
            blobs.remove(sha)
            blobKinds.remove(sha)
            blobOwners.remove(sha)
        }
        return MockResponse.Builder().code(204).build()
    }

    /** Gives a record edited in place by a test a new `rev`, so the devices pull it (as another device's write). */
    @Synchronized
    fun bump(kind: String, id: String) {
        head++
        records[kind to id]!!.rev = head
        records[kind to id]!!.modifiedAt += 1
    }

    /** True when a live photo record names [sha] as its smart preview. */
    fun previewNamed(sha: String): Boolean = records.values.any { r ->
        r.kind == "photo" && !r.deleted &&
            ((r.data as? JsonObject)?.get("preview") as? JsonObject)?.get("sha256")?.jsonPrimitive?.content == sha
    }

    private fun original(fp: String): ByteArray? = nasBytes[fp] ?: originals[fp]?.let { blobs[it] }

    private fun pair(request: RecordedRequest): MockResponse {
        val body = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val token = body["token"]!!.jsonPrimitive.content
        if (!tokens.remove(token)) return error(401, "invalid_pairing_token")
        val key = addDevice(body["deviceName"]!!.jsonPrimitive.content)
        val id = devices.getValue(key)
        culling.paired(id, body)
        return MockResponse.Builder().code(201).body(
            buildJsonObject {
                put("deviceId", id)
                put("apiKey", key)
                put("serverId", serverId)
                put("serverName", "Test")
                if ("device-identity" in features) {
                    put("physicalDeviceId", id)
                    put("candidates", culling.candidates(id, deviceNames))
                }
            }.toString(),
        ).build()
    }

    private fun pull(cursor: String, limit: Int): MockResponse {
        val since = if (cursor.isEmpty()) {
            0
        } else {
            cursor.substringAfter(':').toLongOrNull()
                ?: return error(400, "invalid_cursor")
        }
        if (!cursor.isEmpty() &&
            (!cursor.startsWith(serverId.take(8)) || since > head)
        ) {
            return error(400, "invalid_cursor")
        }
        val changed = records.values.filter { it.rev > since }.sortedBy { it.rev }
        val page = changed.take(limit)
        val last = page.lastOrNull()?.rev ?: since
        return ok(
            buildJsonObject {
                put("changes", buildJsonArray { page.forEach { add(it.toJson()) } })
                put("cursor", "${serverId.take(8)}:$last")
                put("hasMore", changed.size > limit)
            },
        )
    }

    private fun Record.toJson() = buildJsonObject {
        put("kind", kind)
        put("id", id)
        put("rev", rev)
        put("deleted", deleted)
        put("modifiedAt", modifiedAt)
        put("deviceId", deviceId)
        copyOf?.let { put("copyOf", it) }
        put("data", data ?: JsonNull)
    }

    private fun push(device: String, request: RecordedRequest): MockResponse {
        val changes = json.parseToJsonElement(request.body!!.utf8()).jsonObject["changes"]!!.jsonArray
        // hoard-server: too many changes is one invalid_change for the whole request, without an index.
        if (changes.size > maxPushChanges) return error(422, "invalid_change")
        changes.forEachIndexed { index, c ->
            val o = c.jsonObject
            val deleted = o["deleted"]?.jsonPrimitive?.booleanOrNull ?: false
            if (o["kind"]!!.jsonPrimitive.content == "photo" && !deleted &&
                o["data"]?.jsonObject?.get("fingerprint")?.jsonPrimitive?.content?.startsWith("v1:") != true
            ) {
                return error(422, "invalid_change", buildJsonObject { put("index", index) })
            }
        }
        val results = buildJsonArray {
            for (c in changes) {
                val o = c.jsonObject
                val kind = o["kind"]!!.jsonPrimitive.content
                val id = o["id"]!!.jsonPrimitive.content
                val baseRev = o["baseRev"]!!.jsonPrimitive.long
                val modifiedAt = o["modifiedAt"]!!.jsonPrimitive.long
                val deleted = o["deleted"]?.jsonPrimitive?.boolean ?: false
                val data = o["data"]?.takeIf { it !is JsonNull }
                val current = records[kind to id]
                if (current == null || current.rev == baseRev) {
                    val same =
                        current != null && current.modifiedAt == modifiedAt && current.deviceId == device &&
                            current.data == data
                    if (!same) write(kind, id, deleted, modifiedAt, device, null, data, current)
                    add(
                        buildJsonObject {
                            put("kind", kind)
                            put("id", id)
                            put("status", "applied")
                            put(
                                "rev",
                                records[
                                    kind to
                                        id,
                                ]!!.rev,
                            )
                        },
                    )
                    continue
                }
                if (current.modifiedAt == modifiedAt && current.deviceId == device && current.data == data) {
                    add(
                        buildJsonObject {
                            put("kind", kind)
                            put("id", id)
                            put("status", "applied")
                            put("rev", current.rev)
                        },
                    )
                    continue
                }
                val incomingWins =
                    modifiedAt > current.modifiedAt || (modifiedAt == current.modifiedAt && device > current.deviceId)
                var copyId: String? = null
                if (kind == "version") {
                    val loserDeleted = if (incomingWins) current.deleted else deleted
                    if (!loserDeleted) {
                        copyId = UUID.randomUUID().toString()
                        val loserData = if (incomingWins) current.data else data
                        val loserDevice = if (incomingWins) current.deviceId else device
                        val loserAt = if (incomingWins) current.modifiedAt else modifiedAt
                        write(kind, copyId, false, loserAt, loserDevice, id, loserData, null)
                    }
                }
                if (incomingWins) write(kind, id, deleted, modifiedAt, device, null, data, current)
                add(
                    buildJsonObject {
                        put("kind", kind)
                        put("id", id)
                        put("status", "conflict")
                        put("rev", records[kind to id]!!.rev)
                        put("winner", if (incomingWins) "incoming" else "server")
                        copyId?.let { put("copyId", it) }
                    },
                )
            }
        }
        return ok(buildJsonObject { put("results", results) })
    }

    private fun write(
        kind: String,
        id: String,
        deleted: Boolean,
        at: Long,
        device: String,
        copyOf: String?,
        data: JsonElement?,
        current: Record?,
    ) {
        val before = (current?.data as? JsonObject)?.get("preview")?.let { it as? JsonObject }
            ?.get("sha256")?.jsonPrimitive?.content
        if (kind == "photo" && !deleted && "culling" in features) culling.bridge(device, current?.data, data, at)
        head++
        if (current == null) {
            records[kind to id] = Record(kind, id, head, deleted, at, device, copyOf, if (deleted) null else data)
        } else {
            current.rev = head
            current.deleted = deleted
            current.modifiedAt = at
            current.deviceId = device
            current.data = if (deleted) null else data
            // Re-insert so iteration follows the latest revision.
            records.remove(kind to id)
            records[kind to id] = current
        }
        // A photo tombstone releases the preview its record named, unless another live record names it (ADR 0017).
        val released = before != null && !previewNamed(before) && blobKinds[before] == "preview"
        if (kind == "photo" && deleted && released) {
            blobs.remove(before)
            blobKinds.remove(before)
            blobOwners.remove(before)
        }
    }

    private fun lookup(request: RecordedRequest): MockResponse {
        val fps = json.parseToJsonElement(request.body!!.utf8()).jsonObject["fingerprints"]!!.jsonArray.map {
            it.jsonPrimitive.content
        }
        return ok(
            buildJsonObject {
                put(
                    "found",
                    buildJsonArray {
                        for (fp in fps) {
                            when {
                                nasBytes[fp] != null -> add(
                                    buildJsonObject {
                                        put("fingerprint", fp)
                                        put("size", nasBytes[fp]!!.size)
                                        put("source", "nas")
                                    },
                                )

                                originals[fp] != null -> add(
                                    buildJsonObject {
                                        put("fingerprint", fp)
                                        put("size", blobs[originals[fp]]!!.size)
                                        put("source", "backup")
                                        put("sha256", originals[fp])
                                    },
                                )
                            }
                        }
                    },
                )
                put(
                    "missing",
                    buildJsonArray {
                        fps.filter { nasBytes[it] == null && originals[it] == null }.forEach { add(JsonPrimitive(it)) }
                    },
                )
            },
        )
    }

    private fun createUpload(device: String, request: RecordedRequest): MockResponse {
        val o = json.parseToJsonElement(request.body!!.utf8()).jsonObject
        val sha = o["sha256"]!!.jsonPrimitive.content
        val fp = o["fingerprint"]?.jsonPrimitive?.content
        if ((o["kind"]!!.jsonPrimitive.content == "preview") == (fp != null)) return error(422, "invalid_request")
        if (blobs.containsKey(sha)) {
            if (fp != null) originals[fp] = sha
            return ok(buildJsonObject { put("status", "exists") })
        }
        uploads.values.firstOrNull { it.device == device && it.sha == sha }?.let { u ->
            return ok(
                buildJsonObject {
                    put("status", "resume")
                    put("uploadId", u.id)
                    put("offset", u.bytes.size())
                    put("chunkSize", chunkSize)
                },
            )
        }
        val u =
            Upload(
                UUID.randomUUID().toString(),
                device,
                sha,
                o["size"]!!.jsonPrimitive.long,
                o["kind"]!!.jsonPrimitive.content,
                fp,
            )
        uploads[u.id] = u
        return MockResponse.Builder().code(201).body(
            buildJsonObject {
                put("status", "created")
                put("uploadId", u.id)
                put("offset", 0)
                put("chunkSize", chunkSize)
            }.toString(),
        ).build()
    }

    private fun upload(device: String, id: String, request: RecordedRequest): MockResponse {
        val u = uploads[id]?.takeIf { it.device == device } ?: return error(404, "not_found")
        when (request.method) {
            "HEAD" -> return MockResponse.Builder().code(204).addHeader("Upload-Offset", u.bytes.size().toString())
                .addHeader("Upload-Length", u.size.toString()).build()

            "DELETE" -> {
                uploads.remove(id)
                return MockResponse.Builder().code(204).build()
            }
        }
        patchCount.incrementAndGet()
        if (failPatches > 0) {
            failPatches--
            return error(503, "unavailable")
        }
        val offset = request.headers["Upload-Offset"]!!.toLong()
        if (offset != u.bytes.size().toLong()) {
            return error(409, "offset_mismatch", buildJsonObject { put("offset", u.bytes.size()) })
        }
        val body = request.body!!.toByteArray()
        if (body.size > chunkSize) return error(413, "too_large", buildJsonObject { put("offset", u.bytes.size()) })
        if (cutNextChunkAt >= 0) {
            u.bytes.write(body, 0, minOf(cutNextChunkAt, body.size))
            cutNextChunkAt = -1
            return error(400, "invalid_request", buildJsonObject { put("offset", u.bytes.size()) })
        }
        u.bytes.write(body)
        if (u.bytes.size() < u.size) {
            return MockResponse.Builder().code(204).addHeader("Upload-Offset", u.bytes.size().toString()).build()
        }
        val bytes = u.bytes.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        if (sha != u.sha) {
            u.bytes.reset()
            return error(422, "checksum_mismatch", buildJsonObject { put("offset", 0) })
        }
        blobs[sha] = bytes
        blobKinds[sha] = u.kind
        blobOwners[sha] = u.device
        if (u.fp != null) originals[u.fp] = sha
        uploads.remove(id)
        return MockResponse.Builder().code(
            204,
        ).addHeader("Upload-Offset", u.size.toString()).addHeader("Upload-Complete", "1").build()
    }

    private fun serve(request: RecordedRequest, bytes: ByteArray?): MockResponse {
        if (bytes == null) return error(404, "not_found")
        if (request.method == "HEAD") {
            return MockResponse.Builder().code(200).addHeader("Content-Length", bytes.size.toString()).build()
        }
        val range = request.headers["Range"]?.removePrefix("bytes=")
        if (range != null) {
            val from = range.substringBefore('-').toInt()
            val to = minOf(range.substringAfter('-').toInt(), bytes.size - 1)
            return MockResponse.Builder().code(206).body(okio.Buffer().write(bytes, from, to - from + 1)).build()
        }
        return MockResponse.Builder().code(200).body(okio.Buffer().write(bytes)).build()
    }

    private fun ok(body: JsonObject) =
        MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body.toString()).build()

    private fun error(status: Int, code: String, details: JsonObject? = null) = MockResponse.Builder().code(status)
        .addHeader("Content-Type", "application/json")
        .apply { if (status == 429 || status == 503) addHeader("Retry-After", "1") }
        .body(
            buildJsonObject {
                put(
                    "error",
                    buildJsonObject {
                        put("code", code)
                        put("message", code)
                        details?.let { put("details", it) }
                    },
                )
            }.toString(),
        )
        .build()

    /** Adds an indexed NAS file. */
    fun addNasFile(folderId: String, folderName: String, path: String, bytes: ByteArray, fingerprint: String) {
        nasFiles += buildJsonObject {
            put("fingerprint", fingerprint)
            put("folderId", folderId)
            put("folderName", folderName)
            put("path", path)
            put("size", bytes.size)
            put("modifiedAt", 1_700_000_000_000L)
            put("removed", false)
        }
        nasBytes[fingerprint] = bytes
    }

    /** The NAS feed reports [path] of [folderId] as removed. */
    fun removeNasFile(folderId: String, path: String) {
        nasFiles += buildJsonObject {
            put("folderId", folderId)
            put("folderName", "NAS")
            put("path", path)
            put("size", 0)
            put("modifiedAt", 1_700_000_000_000L)
            put("removed", true)
        }
    }

    fun record(kind: String, id: String): Record? = records[kind to id]

    /** Stores [bytes] as an uploaded original (as a device's backup would). */
    @Synchronized
    fun putOriginal(bytes: ByteArray, device: String = "test") {
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        blobs[sha] = bytes
        blobKinds[sha] = "original"
        blobOwners[sha] = device
        originals[dev.hfps.image.source.FileFingerprint.of(bytes)] = sha
    }

    /** True when the server holds an uploaded original of [fingerprint]. */
    fun hasOriginal(fingerprint: String) = originals[fingerprint]?.let { blobs.containsKey(it) } == true

    fun copies(): List<Record> = records.values.filter { it.copyOf != null }

    /** Every version record the server holds (live or not). */
    fun versionRecords(): List<Record> = records.filterKeys { it.first == "version" }.values.toList()

    @Suppress("unused")
    private fun JsonElement.asBool() = (this as? JsonPrimitive)?.booleanOrNull
}
