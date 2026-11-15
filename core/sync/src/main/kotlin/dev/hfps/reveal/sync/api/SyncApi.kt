package dev.hfps.reveal.sync.api

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink

/**
 * An error answer of the server (ADR 0017 "Errors"): the HTTP [status], the stable [code] and, for `429`/`503`, the
 * seconds of `Retry-After`. [details] carries e.g. the upload offset of `offset_mismatch`.
 */
class SyncHttpException(
    val status: Int,
    val code: String,
    message: String,
    val retryAfterSeconds: Long? = null,
    val details: JsonObject? = null,
) : IOException("$status $code: $message") {
    /** The device key is gone (revoked, server reset): the app must pair again. */
    val unauthorized: Boolean get() = status == HTTP_UNAUTHORIZED && code == "unauthorized"

    /** Worth retrying later (rate limit, server busy or misconfigured, a 5xx). */
    val transient: Boolean get() = status == HTTP_TOO_MANY || status >= HTTP_SERVER_ERROR

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_TOO_MANY = 429
        const val HTTP_SERVER_ERROR = 500
    }
}

/** What `DELETE /v1/blobs/{sha256}` answered. */
enum class DropResult {
    /** The server no longer holds the preview. */
    DROPPED,

    /** It held no such blob (dropped before, or never stored). */
    GONE,

    /** A live photo record still names it: push the record without it first, then ask again. */
    REFERENCED,

    /** Not this device's preview, or not a preview: never asked again. */
    REFUSED,

    /** The server predates the route: asked again after it is updated. */
    UNSUPPORTED,
}

/**
 * The client of the `reveal-sync` HTTP API (`/v1`, ADR 0017), one per server. Blocking (OkHttp): call it from an IO
 * dispatcher. [apiKey] is the device's bearer key (null only for [pair] and [health]). Never logs keys, tokens or
 * photo names: requests carry them, log lines do not.
 */
class SyncApi(baseUrl: String, private val apiKey: String?, private val client: OkHttpClient = defaultClient) {
    private val base: HttpUrl = normalise(baseUrl)

    /** The server's address (normalised): the key of per-server memos. */
    val baseUrl: String get() = base.toString()

    fun health(): HealthResponse = get(url("v1/health"), HealthResponse.serializer(), auth = false)

    fun pair(request: PairRequest): PairResponse =
        post(url("v1/pair"), PairRequest.serializer(), request, PairResponse.serializer(), auth = false)

    fun server(): ServerInfo = get(url("v1/server"), ServerInfo.serializer())

    fun devices(): DevicesResponse = get(url("v1/devices"), DevicesResponse.serializer())

    // ------------------------------------------------------------------ this device (Hoard ADR 0017)

    /** `GET /v1/device`: this device, its physical device and the candidates to join. */
    fun device(): DeviceView = get(url("v1/device"), DeviceView.serializer())

    /** `PATCH /v1/device`: reports the app id, model and capabilities (`features` are only ever added). */
    fun reportDevice(report: DeviceReport): DeviceView {
        val text = json.encodeToString(DeviceReport.serializer(), report)
        val request = authed(Request.Builder().url(url("v1/device")).patch(text.toRequestBody(JSON_TYPE)))
            .header("Accept", "application/json")
        return client.newCall(request.build()).execute().use { response -> parse(response, DeviceView.serializer()) }
    }

    /**
     * `POST /v1/device/claim`: moves this device into physical device [physicalDeviceId], or out to one of its own
     * when null. `409 conflict` (`same_app`) when that phone already has a live device of this app id.
     */
    fun claim(physicalDeviceId: String?): DeviceView {
        val body = JsonObject(mapOf("physicalDeviceId" to (physicalDeviceId?.let(::JsonPrimitive) ?: JsonNull)))
        val request = authed(
            Request.Builder().url(url("v1/device/claim")).post(body.toString().toRequestBody(JSON_TYPE)),
        ).header("Accept", "application/json")
        return client.newCall(request.build()).execute().use { response -> parse(response, DeviceView.serializer()) }
    }

    // ------------------------------------------------------------------ edited previews (Hoard ADR 0020)

    /** `POST /v1/items/lookup`: the live items having any of [fingerprints] (≤ 1000) as a member, with their edits. */
    fun itemsLookup(fingerprints: List<String>): ItemsLookupResponse = post(
        url("v1/items/lookup"),
        LookupRequest.serializer(),
        LookupRequest(fingerprints),
        ItemsLookupResponse.serializer(),
    )

    /**
     * `PUT /v1/items/{itemId}/edits/{versionId}`: [image] (JPEG) with [meta] in headers; null [image] = a
     * metadata-only change (the server keeps the stored image when the digest is the same).
     */
    fun putEdit(itemId: String, versionId: String, meta: EditMeta, image: ByteArray?): EditPutResponse {
        val body = image?.toRequestBody(JPEG_TYPE) ?: ByteArray(0).toRequestBody(null)
        val request = authed(Request.Builder().url(editUrl(itemId, versionId)).put(body))
            .header("Accept", "application/json")
            .header("X-Hoard-Recipe-Digest", meta.recipeDigest)
            .header("X-Hoard-Edited-At", meta.editedAt.toString())
            .header("X-Hoard-Label", java.net.URLEncoder.encode(meta.label, "UTF-8").replace("+", "%20"))
            .header("X-Hoard-Virtual-Copy", meta.virtualCopy.toString())
            .header("X-Hoard-Primary", meta.primary.toString())
        return client.newCall(request.build()).execute().use { response ->
            parse(response, EditPutResponse.serializer())
        }
    }

    /** `DELETE /v1/items/{itemId}/edits/{versionId}`: idempotent; false when the server never had the item (`404`). */
    fun deleteEdit(itemId: String, versionId: String): Boolean {
        val request = authed(Request.Builder().url(editUrl(itemId, versionId)).delete())
        return client.newCall(request.build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return false
            check(response)
            true
        }
    }

    /** `GET` of a stored edit (tests): its bytes, or null (`404`). */
    fun editImage(itemId: String, versionId: String): ByteArray? {
        val request = authed(Request.Builder().url(editUrl(itemId, versionId)))
        return client.newCall(request.build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            check(response)
            response.body.bytes()
        }
    }

    /**
     * `GET /v1/items/{itemId}/preview?size=screen&format=jpeg&edit=[edit]` (Hoard ADR 0020 "Previews of an edit"):
     * the bytes, or null while it is not made yet or there is no such edit (`404`). Checks and tests.
     */
    fun itemPreview(itemId: String, edit: String = "primary"): ByteArray? {
        val url = url("v1/items").newBuilder().addPathSegment(itemId).addPathSegment("preview")
            .addQueryParameter("size", "screen").addQueryParameter("format", "jpeg")
            .addQueryParameter("edit", edit).build()
        return client.newCall(authed(Request.Builder().url(url)).build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND || response.code == HTTP_ACCEPTED) return null
            check(response)
            response.body.bytes()
        }
    }

    // ------------------------------------------------------------------ gallery previews (Hoard ADR 0015, W3/C)

    /**
     * `POST /v1/items/previews` (≤ `previews.maxWarm`, 500): the state of each item's [size] preview of [edit] (null =
     * the item's own), with the `v` key of the ready ones; the others are queued on the server, first item first.
     */
    fun warmPreviews(itemIds: List<String>, size: String = PREVIEW_THUMB, edit: String? = null): WarmResponse = post(
        url("v1/items/previews"),
        WarmRequest.serializer(),
        WarmRequest(itemIds, listOf(size), edit),
        WarmResponse.serializer(),
    )

    /**
     * `GET /v1/items/{itemId}/preview?size=&format=webp&v=&edit=`: the preview's bytes (with `v`, an immutable URL),
     * [PreviewAnswer.Pending] while the server makes it (`202`), [PreviewAnswer.Unavailable] (`404`).
     */
    fun preview(itemId: String, size: String = PREVIEW_THUMB, v: String? = null, edit: String? = null): PreviewAnswer {
        val builder = url("v1/items").newBuilder().addPathSegment(itemId).addPathSegment("preview")
            .addQueryParameter("size", size).addQueryParameter("format", "webp")
        if (v != null) builder.addQueryParameter("v", v)
        if (edit != null) builder.addQueryParameter("edit", edit)
        return client.newCall(authed(Request.Builder().url(builder.build())).build()).execute().use { response ->
            when (response.code) {
                HTTP_ACCEPTED -> PreviewAnswer.Pending

                HTTP_NOT_FOUND -> PreviewAnswer.Unavailable

                else -> {
                    check(response)
                    PreviewAnswer.Ready(response.body.bytes(), response.header("X-Hoard-Preview-Version") ?: v)
                }
            }
        }
    }

    private fun editUrl(itemId: String, versionId: String) = url("v1/items").newBuilder().addPathSegment(itemId)
        .addPathSegment("edits").addPathSegment(versionId).build()

    // ------------------------------------------------------------------ culling (Hoard ADR 0016)

    /** The culling records changed since [cursor], from the items feed (`include=culling`: no items downloaded). */
    fun cullingChanges(cursor: String, limit: Int): CullingPage = get(
        url("v1/items/changes").newBuilder().addQueryParameter("cursor", cursor)
            .addQueryParameter("limit", limit.toString()).addQueryParameter("include", "culling").build(),
        CullingPage.serializer(),
    )

    /** `POST /v1/culling`: up to 500 per-field changes; one result per change, in order (invalid ones included). */
    fun pushCulling(changes: List<CullingChange>): CullingPushResponse = post(
        url("v1/culling"),
        CullingPushRequest.serializer(),
        CullingPushRequest(changes),
        CullingPushResponse.serializer(),
    )

    /** `GET /v1/culling/{itemId}`, or null when no field of it was ever written (tests, checks). */
    fun cullingRecord(itemId: String): CullingRecord? {
        val request = authed(
            Request.Builder().url(url("v1/culling").newBuilder().addPathSegment(itemId).build())
                .header("Accept", "application/json"),
        )
        return client.newCall(request.build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            parse(response, CullingRecord.serializer())
        }
    }

    fun pull(cursor: String, limit: Int): PullResponse = get(
        url("v1/changes").newBuilder().addQueryParameter("cursor", cursor)
            .addQueryParameter("limit", limit.toString()).build(),
        PullResponse.serializer(),
    )

    fun push(changes: List<PushChange>): PushResponse =
        post(url("v1/changes"), PushRequest.serializer(), PushRequest(changes), PushResponse.serializer())

    fun lookup(fingerprints: List<String>): LookupResponse = post(
        url("v1/fingerprints/lookup"),
        LookupRequest.serializer(),
        LookupRequest(fingerprints),
        LookupResponse.serializer(),
    )

    fun nasFiles(cursor: String, limit: Int): NasFilesResponse = get(
        url("v1/nas/files").newBuilder().addQueryParameter("cursor", cursor)
            .addQueryParameter("limit", limit.toString()).build(),
        NasFilesResponse.serializer(),
    )

    // ------------------------------------------------------------------ blobs and originals

    /** Size of an original the server can serve (NAS or backup), or null when it cannot (404). */
    fun originalSize(fingerprint: String): Long? =
        headSize(url("v1/originals").newBuilder().addPathSegment(fingerprint).build())

    fun blobSize(sha256: String): Long? = headSize(url("v1/blobs").newBuilder().addPathSegment(sha256).build())

    /** [length] bytes of an original from [offset] (a single `Range` request). */
    fun readOriginal(fingerprint: String, offset: Long, length: Int): ByteArray =
        readRange(url("v1/originals").newBuilder().addPathSegment(fingerprint).build(), offset, length)

    fun readBlob(sha256: String, offset: Long, length: Int): ByteArray =
        readRange(url("v1/blobs").newBuilder().addPathSegment(sha256).build(), offset, length)

    /** Streams a whole blob into [sink]. */
    fun downloadBlob(sha256: String, sink: (InputStream) -> Unit) {
        val request = authed(Request.Builder().url(url("v1/blobs").newBuilder().addPathSegment(sha256).build()))
        client.newCall(request.build()).execute().use { response ->
            check(response)
            sink(response.body.byteStream())
        }
    }

    // ------------------------------------------------------------------ uploads

    fun createUpload(request: UploadCreateRequest): UploadCreateResponse =
        post(url("v1/uploads"), UploadCreateRequest.serializer(), request, UploadCreateResponse.serializer())

    /** The server's offset of an upload, or null when the upload is unknown (expired, finished, another device's). */
    fun uploadOffset(uploadId: String): Long? {
        val request = authed(Request.Builder().url(uploadUrl(uploadId)).head())
        return client.newCall(request.build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            check(response)
            response.header("Upload-Offset")?.toLongOrNull()
        }
    }

    /**
     * Sends [length] bytes at [offset] from [source] (positioned by the caller's reader). Returns the server's new
     * offset and whether the upload completed. A mismatch (`409 offset_mismatch`) or an interrupted chunk throws
     * [SyncHttpException]; the caller asks [uploadOffset] and continues from there.
     */
    fun patchUpload(uploadId: String, offset: Long, length: Int, write: (BufferedSink) -> Unit): ChunkResult {
        val body = object : RequestBody() {
            override fun contentType() = OFFSET_OCTETS
            override fun contentLength(): Long = length.toLong()
            override fun writeTo(sink: BufferedSink) = write(sink)
        }
        val request = authed(Request.Builder().url(uploadUrl(uploadId)).patch(body))
            .header("Upload-Offset", offset.toString())
        return client.newCall(request.build()).execute().use { response ->
            check(response)
            ChunkResult(
                offset = response.header("Upload-Offset")?.toLongOrNull() ?: (offset + length),
                complete = response.header("Upload-Complete") == "1",
            )
        }
    }

    fun deleteUpload(uploadId: String) {
        val request = authed(Request.Builder().url(uploadUrl(uploadId)).delete())
        client.newCall(request.build()).execute().use { response ->
            if (response.code != HTTP_NOT_FOUND) check(response)
        }
    }

    /**
     * `DELETE /v1/originals/{fingerprint}` (ADR 0017 "Deleting an original", 0.5.3): the user deleted the photo from
     * the server. Same answers as [dropPreview]: [DropResult.REFERENCED] while a live record names it, [DropResult.REFUSED]
     * for a NAS file, [DropResult.UNSUPPORTED] from a server before 0.5.3.
     */
    fun deleteOriginal(fingerprint: String): DropResult {
        val url = url("v1/originals").newBuilder().addPathSegment(fingerprint).build()
        return delete(url)
    }

    /**
     * Drops a smart preview this device uploaded (`DELETE /v1/blobs/{sha256}`, ADR 0017 "Dropping a smart preview").
     * Servers before 0.5.2 do not have the route (`405`): [DropResult.UNSUPPORTED], the drop waits for an update.
     */
    fun dropPreview(sha256: String): DropResult {
        val url = url("v1/blobs").newBuilder().addPathSegment(sha256).build()
        return delete(url)
    }

    private fun delete(url: okhttp3.HttpUrl): DropResult {
        val request = authed(Request.Builder().url(url).delete())
        return client.newCall(request.build()).execute().use { response ->
            when (response.code) {
                HTTP_NO_CONTENT -> DropResult.DROPPED

                HTTP_NOT_FOUND -> DropResult.GONE

                HTTP_FORBIDDEN -> DropResult.REFUSED

                HTTP_CONFLICT -> DropResult.REFERENCED

                HTTP_METHOD_NOT_ALLOWED -> DropResult.UNSUPPORTED

                else -> {
                    check(response)
                    DropResult.DROPPED
                }
            }
        }
    }

    // ------------------------------------------------------------------ plumbing

    private fun uploadUrl(id: String) = url("v1/uploads").newBuilder().addPathSegment(id).build()

    private fun url(path: String): HttpUrl = base.newBuilder().addPathSegments(path).build()

    private fun authed(builder: Request.Builder): Request.Builder {
        val key = checkNotNull(apiKey) { "not paired" }
        return builder.header("Authorization", "Bearer $key")
    }

    private fun <T> get(url: HttpUrl, serializer: KSerializer<T>, auth: Boolean = true): T {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        val request = if (auth) authed(builder) else builder
        return client.newCall(request.build()).execute().use { response -> parse(response, serializer) }
    }

    private fun <B, T> post(
        url: HttpUrl,
        bodySerializer: KSerializer<B>,
        body: B,
        serializer: KSerializer<T>,
        auth: Boolean = true,
    ): T {
        val text = json.encodeToString(bodySerializer, body)
        val builder = Request.Builder().url(url).post(text.toRequestBody(JSON_TYPE))
            .header("Accept", "application/json")
        val request = if (auth) authed(builder) else builder
        return client.newCall(request.build()).execute().use { response -> parse(response, serializer) }
    }

    private fun headSize(url: HttpUrl): Long? {
        val request = authed(Request.Builder().url(url).head())
        return client.newCall(request.build()).execute().use { response ->
            if (response.code == HTTP_NOT_FOUND) return null
            check(response)
            response.header("Content-Length")?.toLongOrNull()
        }
    }

    private fun readRange(url: HttpUrl, offset: Long, length: Int): ByteArray {
        if (length <= 0) return ByteArray(0)
        val request = authed(Request.Builder().url(url))
            .header("Range", "bytes=$offset-${offset + length - 1}")
        return client.newCall(request.build()).execute().use { response ->
            check(response)
            val bytes = response.body.bytes()
            // A server that ignores Range answers 200 with the whole file: cut the window out of it.
            if (response.code == HTTP_OK && bytes.size > length) {
                val from = offset.coerceAtMost(bytes.size.toLong()).toInt()
                bytes.copyOfRange(from, minOf(bytes.size, from + length))
            } else {
                bytes
            }
        }
    }

    private fun <T> parse(response: Response, serializer: KSerializer<T>): T {
        check(response)
        return json.decodeFromString(serializer, response.body.string())
    }

    private fun check(response: Response) {
        if (response.isSuccessful) return
        val text = runCatching { response.body.string() }.getOrDefault("")
        val error = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject }.getOrNull()
        throw SyncHttpException(
            status = response.code,
            code = error?.get("code")?.jsonPrimitive?.content ?: "http_${response.code}",
            message = error?.get("message")?.jsonPrimitive?.content ?: response.message,
            retryAfterSeconds = response.header("Retry-After")?.toLongOrNull(),
            details = error?.get("details") as? JsonObject,
        )
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_ACCEPTED = 202
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_NO_CONTENT = 204
        private const val HTTP_FORBIDDEN = 403
        private const val HTTP_CONFLICT = 409
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
        private val OFFSET_OCTETS = "application/offset+octet-stream".toMediaType()
        private val JPEG_TYPE = "image/jpeg".toMediaType()
        private const val TIMEOUT_S = 30L

        val json = Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

        /** Longest `Retry-After` the client waits out by itself; longer answers go back to the job's backoff. */
        private const val MAX_RETRY_AFTER_S = 30L
        private const val RETRIES = 3
        private const val MILLIS = 1000L

        /**
         * `429 rate_limited` and `503` carry `Retry-After` (ADR 0017 "Errors"): wait it out and send again, a few
         * times. Not for `PATCH`: an upload chunk's body is a stream read once (the uploader resumes from `HEAD`).
         */
        private val retryAfter = okhttp3.Interceptor { chain ->
            var response = chain.proceed(chain.request())
            repeat(RETRIES) {
                val wait = retryWait(response, chain.request().method) ?: return@Interceptor response
                response.close()
                Thread.sleep(wait * MILLIS)
                response = chain.proceed(chain.request())
            }
            response
        }

        /** Seconds to wait before sending again, or null when [response] is final. */
        private fun retryWait(response: Response, method: String): Long? {
            val busy = response.code == HTTP_TOO_MANY || response.code == HTTP_UNAVAILABLE
            if (!busy || method == "PATCH") return null
            return response.header("Retry-After")?.toLongOrNull()?.takeIf { it <= MAX_RETRY_AFTER_S }?.coerceAtLeast(1)
        }

        private const val HTTP_TOO_MANY = 429
        private const val HTTP_UNAVAILABLE = 503

        val defaultClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .addInterceptor(retryAfter)
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }

        /** `https://host/prefix` with a trailing slash, so `v1/...` resolves under a reverse proxy's prefix too. */
        fun normalise(url: String): HttpUrl {
            val parsed = url.trim().toHttpUrl()
            val path = parsed.encodedPath.trimEnd('/') + "/"
            return parsed.newBuilder().encodedPath(path).query(null).fragment(null).build()
        }
    }
}

/** The `offset` an upload stands at after a chunk, and whether it completed. */
data class ChunkResult(val offset: Long, val complete: Boolean)

// ------------------------------------------------------------------ wire types (ADR 0017)

@Serializable
data class HealthResponse(val status: String, val version: String? = null, val api: Int = 0)

/** `POST /v1/pair`; [app], [manufacturer] and [model] are Hoard ADR 0017's (older servers ignore them). */
@Serializable
data class PairRequest(
    val token: String,
    val deviceName: String,
    val platform: String,
    val appVersion: String,
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
)

@Serializable
data class PairResponse(
    val deviceId: String,
    val apiKey: String,
    val serverId: String,
    val serverName: String = "",
    /** hoard-server 0.7.0+: the physical device this pairing started (its id = [deviceId]). */
    val physicalDeviceId: String? = null,
    /** hoard-server 0.7.0+: phones this one may be (another app paired there), model matches first. */
    val candidates: List<DeviceCandidate> = emptyList(),
)

/** One app installation of a physical device (Hoard ADR 0017 "Client calls"). */
@Serializable
data class DeviceEntry(
    val id: String,
    val name: String = "",
    val app: String? = null,
    val platform: String = "",
    val manufacturer: String? = null,
    val model: String? = null,
)

/** A physical device this one may belong to: [match] `model` (same manufacturer and model) or `other`. */
@Serializable
data class DeviceCandidate(
    val physicalDeviceId: String,
    val name: String = "",
    val match: String = "other",
    val devices: List<DeviceEntry> = emptyList(),
) {
    val modelMatch: Boolean get() = match == "model"
}

@Serializable
data class PhysicalDevice(val id: String, val name: String = "", val devices: List<DeviceEntry> = emptyList())

/** `GET /v1/device` (and the answer of `PATCH` / `claim`). */
@Serializable
data class DeviceView(
    val deviceId: String,
    val name: String = "",
    val platform: String = "",
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val features: List<String> = emptyList(),
    val physicalDevice: PhysicalDevice? = null,
    val candidates: List<DeviceCandidate> = emptyList(),
)

/** `PATCH /v1/device`: null fields are left out (unchanged on the server). */
@Serializable
data class DeviceReport(
    val app: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val features: List<String>? = null,
)

/** One field of a culling record: its value (`4`, `"pick"`, `"Red"`), revision, time and writer. */
@Serializable
data class CullingFieldState(
    val value: JsonElement,
    val rev: Long = 0,
    val modifiedAt: Long = 0,
    val deviceId: String = "",
)

/** A culling record of the items feed (Hoard ADR 0016 "Wire format"). */
@Serializable
data class CullingRecord(
    val itemId: String,
    val rev: Long = 0,
    val fingerprints: List<String> = emptyList(),
    val replacedBy: String? = null,
    val rating: CullingFieldState? = null,
    val flag: CullingFieldState? = null,
    val label: CullingFieldState? = null,
)

@Serializable
data class CullingPage(
    val culling: List<CullingRecord> = emptyList(),
    val cursor: String,
    val hasMore: Boolean = false,
)

/** One per-field change; the shot is named by [fingerprint] (Reveal always pushes by its main file). */
@Serializable
data class CullingChange(
    val fingerprint: String? = null,
    val itemId: String? = null,
    val field: String,
    val value: JsonElement,
    val baseRev: Long,
    val modifiedAt: Long,
)

@Serializable
data class CullingPushRequest(val changes: List<CullingChange>)

@Serializable
data class CullingResult(
    val index: Int = -1,
    val itemId: String? = null,
    val fingerprint: String? = null,
    val field: String = "",
    /** `applied`, `unchanged`, `conflict` (with [winner]) or `invalid` (with [code]). */
    val status: String,
    val winner: String? = null,
    val rev: Long? = null,
    val clipped: Boolean = false,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class CullingPushResponse(val results: List<CullingResult> = emptyList())

@Serializable
data class ServerInfo(
    val serverId: String,
    val serverName: String = "",
    val version: String? = null,
    val api: Int = 0,
    val deviceId: String,
    val limits: JsonObject? = null,
    val storageConfigured: Boolean = false,
    /** What the server implements beyond the base protocol (`items`, `previews`, `culling`, `device-identity`). */
    val features: List<String> = emptyList(),
    /** The edited previews' limits (Hoard ADR 0020), present with the `edits` feature. */
    val edits: EditLimits? = null,
) {
    fun has(feature: String): Boolean = feature in features

    fun limit(name: String): Long? = limits?.get(name)?.jsonPrimitive?.longOrNull

    fun intLimit(name: String): Int? = limits?.get(name)?.jsonPrimitive?.intOrNull
}

@Serializable
data class DeviceSummary(
    val id: String,
    val name: String,
    val platform: String = "",
    val physicalDeviceId: String? = null,
    val app: String? = null,
)

@Serializable
data class DevicesResponse(val devices: List<DeviceSummary> = emptyList())

/** One record of the change feed. */
@Serializable
data class FeedRecord(
    val kind: String,
    val id: String,
    val rev: Long,
    val deleted: Boolean = false,
    val modifiedAt: Long = 0,
    val deviceId: String = "",
    val copyOf: String? = null,
    val data: JsonElement? = null,
)

@Serializable
data class PullResponse(val changes: List<FeedRecord> = emptyList(), val cursor: String, val hasMore: Boolean = false)

@Serializable
data class PushChange(
    val kind: String,
    val id: String,
    val baseRev: Long,
    val modifiedAt: Long,
    val deleted: Boolean = false,
    val data: JsonElement? = null,
)

@Serializable
data class PushRequest(val changes: List<PushChange>)

@Serializable
data class PushResult(
    val kind: String,
    val id: String,
    val status: String,
    val rev: Long,
    val winner: String? = null,
    val copyId: String? = null,
)

@Serializable
data class PushResponse(val results: List<PushResult> = emptyList())

@Serializable
data class LookupRequest(val fingerprints: List<String>)

@Serializable
data class FoundOriginal(val fingerprint: String, val size: Long, val source: String, val sha256: String? = null)

@Serializable
data class LookupResponse(val found: List<FoundOriginal> = emptyList(), val missing: List<String> = emptyList())

@Serializable
data class NasFile(
    val fingerprint: String? = null,
    val folderId: String,
    val folderName: String = "",
    val path: String,
    val size: Long = 0,
    val modifiedAt: Long = 0,
    val removed: Boolean = false,
)

@Serializable
data class NasFilesResponse(val files: List<NasFile> = emptyList(), val cursor: String, val hasMore: Boolean = false)

@Serializable
data class UploadCreateRequest(val sha256: String, val size: Long, val kind: String, val fingerprint: String? = null)

@Serializable
data class UploadCreateResponse(
    val status: String,
    val uploadId: String? = null,
    val offset: Long = 0,
    val chunkSize: Long = 0,
    val expiresAt: Long = 0,
)

/** 8 MiB: an edited preview's upload limit when the server does not say (Hoard ADR 0020 "Limits"). */
private const val DEFAULT_EDIT_BYTES = 8L * 1024 * 1024

/** `GET /v1/server` `edits` (Hoard ADR 0020 "Storage and the setup page"). */
@Serializable
data class EditLimits(
    val maxBytes: Long = DEFAULT_EDIT_BYTES,
    val maxEdge: Int = 2560,
    val minEdge: Int = 16,
    val formats: List<String> = listOf("jpeg"),
    val maxPerItem: Int = 32,
    val maxLabel: Int = 64,
    val maxDigest: Int = 128,
)

/** One edited preview the server holds for an item. */
@Serializable
data class ServerEdit(
    val versionId: String,
    val label: String = "",
    val recipeDigest: String = "",
    val editedAt: Long = 0,
    val primary: Boolean = false,
    val isVirtualCopy: Boolean = false,
    val deviceId: String = "",
)

/**
 * An item as the lookup answers it (Hoard ADR 0014): what the edited previews and the library need (its capture time,
 * size and orientation fill a Server photo's header without reading the file, ADR 0018 "W3/C").
 */
@Serializable
data class ServerItem(
    val id: String,
    val edits: List<ServerEdit> = emptyList(),
    val mainFingerprint: String? = null,
    val sortTime: Long? = null,
    val capture: ItemCapture? = null,
    val width: Int? = null,
    val height: Int? = null,
    val orientation: Int? = null,
)

/** An item's capture time: [local] wall clock (`2024-05-01T10:00:00.500`), [offset] (`-03:00`), [utc] ms. */
@Serializable
data class ItemCapture(val local: String? = null, val offset: String? = null, val utc: Long? = null)

@Serializable
data class WarmRequest(val items: List<String>, val previews: List<String>, val edit: String? = null)

/** One preview's state in a warm answer: `ready` (with [v]), `pending` or `unavailable` ([reason]). */
@Serializable
data class WarmPreview(
    val status: String,
    val v: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val webpBytes: Long? = null,
    val reason: String? = null,
)

@Serializable
data class WarmItem(val id: String, val thumb: WarmPreview? = null, val screen: WarmPreview? = null)

@Serializable
data class WarmResponse(val items: List<WarmItem> = emptyList(), val missing: List<String> = emptyList())

/** What `GET .../preview` answered. */
sealed interface PreviewAnswer {
    class Ready(val bytes: ByteArray, val v: String?) : PreviewAnswer

    data object Pending : PreviewAnswer

    data object Unavailable : PreviewAnswer
}

const val PREVIEW_THUMB = "thumb"
const val PREVIEW_SCREEN = "screen"

@Serializable
data class ItemMatch(val fingerprint: String, val itemId: String, val role: String = "")

@Serializable
data class ItemsLookupResponse(
    val items: List<ServerItem> = emptyList(),
    val matches: List<ItemMatch> = emptyList(),
    val missing: List<String> = emptyList(),
)

/** The headers of an edited preview's PUT. */
data class EditMeta(
    val recipeDigest: String,
    val editedAt: Long,
    val label: String,
    val virtualCopy: Boolean,
    val primary: Boolean,
)

@Serializable
data class EditPutResponse(
    /** `created`, `updated`, `replaced` or `unchanged`. */
    val status: String,
    val itemId: String,
    val rev: Long = 0,
    val edit: ServerEdit? = null,
)
