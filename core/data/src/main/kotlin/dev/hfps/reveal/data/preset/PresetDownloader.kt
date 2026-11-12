package dev.hfps.reveal.data.preset

import dev.hfps.reveal.pipeline.preset.PresetLimits
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

/** Why a download from a link failed (stable codes; the UI maps them to strings). */
enum class PresetDownloadError {
    /** The link, or a redirect, is not `https`. */
    NOT_HTTPS,

    /** The server answered with an error status ([PresetDownloadException.status]). */
    HTTP_STATUS,

    /** Over [PresetDownloader.maxBytes], by its `Content-Length` or while reading. */
    TOO_LARGE,

    /** No answer, or too slow, within the timeouts. */
    TIMEOUT,

    /** No connection, DNS, TLS or a broken transfer. */
    NETWORK,
}

class PresetDownloadException(val error: PresetDownloadError, val status: Int = 0, cause: Throwable? = null) :
    IOException("preset download failed: $error${if (status != 0) " ($status)" else ""}", cause)

/**
 * Downloads one `.revealpreset` / `.revealpack` the user asked for by link (docs/adr/0019 "Import from a link"):
 * `https` only (also every redirect), at most [maxBytes] (the pack limit, checked against `Content-Length` and
 * counted while reading), connect/read timeouts and an overall deadline, no cookies, no cache, nothing sent but the
 * request line and a generic `User-Agent`. The caller confirms first and cancels by cancelling the coroutine. The
 * bytes go to [download]'s file; the import that follows validates them like any file.
 */
class PresetDownloader(
    private val base: OkHttpClient? = null,
    val maxBytes: Long = PresetLimits.PACK_BYTES,
    private val callTimeoutSeconds: Long = CALL_TIMEOUT_S,
) {
    private val default: OkHttpClient by lazy { OkHttpClient() }

    @Volatile
    private var built: Pair<OkHttpClient, OkHttpClient>? = null

    /** The client for [base] (or the test hook's, or a default one), built once per base. */
    private fun client(): OkHttpClient {
        val from = base ?: testBaseClient ?: default
        built?.let { (b, c) -> if (b === from) return c }
        return build(from).also { built = from to it }
    }

    private fun build(base: OkHttpClient): OkHttpClient = base.newBuilder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
        .followRedirects(true)
        // A redirect from https to http is never followed (and the interceptor refuses any non-https hop).
        .followSslRedirects(false)
        .cache(null)
        .addNetworkInterceptor(HttpsOnly)
        .build()

    /**
     * Downloads [url] into [dest] (replaced), reporting `(received, total)` bytes (`total` null when the server does
     * not say). Throws [PresetDownloadException]; cancelling the calling coroutine cancels the transfer.
     */
    suspend fun download(url: String, dest: File, onProgress: (Long, Long?) -> Unit = { _, _ -> }) {
        val call = client().newCall(request(url))
        val job = currentCoroutineContext().job
        val handle = job.invokeOnCompletion { if (job.isCancelled) call.cancel() }
        try {
            withContext(Dispatchers.IO) {
                val response = try {
                    call.execute()
                } catch (e: IOException) {
                    throw failure(e)
                }
                response.use { save(it, dest, onProgress) }
            }
        } catch (e: PresetDownloadException) {
            dest.delete()
            throw e
        } finally {
            handle.dispose()
        }
    }

    private fun request(url: String): Request {
        val parsed = url.takeIf { it.startsWith("https://", ignoreCase = true) }?.toHttpUrlOrNull()
            ?: throw PresetDownloadException(PresetDownloadError.NOT_HTTPS)
        return Request.Builder().url(parsed).header("User-Agent", USER_AGENT).header("Accept", ACCEPT).build()
    }

    private suspend fun save(response: Response, dest: File, onProgress: (Long, Long?) -> Unit) {
        val body = response.body
        val total = body.contentLength().takeIf { it >= 0 }
        val tooLarge = total?.takeIf { it > maxBytes }?.let { PresetDownloadException(PresetDownloadError.TOO_LARGE) }
        (refusal(response) ?: tooLarge)?.let { throw it }
        onProgress(0, total)
        try {
            body.byteStream().use { input -> dest.outputStream().use { out -> copy(input, out, total, onProgress) } }
        } catch (e: IOException) {
            // A cancelled call fails its read: cancellation wins over the I/O error.
            currentCoroutineContext().ensureActive()
            throw failure(e)
        }
    }

    /** Why [response] is not a file to read: left https, an https -> http redirect handed back, an error status. */
    private fun refusal(response: Response): PresetDownloadException? = when {
        !response.request.url.isHttps -> PresetDownloadException(PresetDownloadError.NOT_HTTPS)

        // followSslRedirects(false) hands an https -> http redirect back instead of following it.
        response.isRedirect && response.header("Location")?.startsWith("http://", ignoreCase = true) == true ->
            PresetDownloadException(PresetDownloadError.NOT_HTTPS)

        !response.isSuccessful -> PresetDownloadException(PresetDownloadError.HTTP_STATUS, response.code)

        else -> null
    }

    /** Copies at most [maxBytes], counted while reading (a chunked body has no length to check first). */
    private suspend fun copy(input: InputStream, out: OutputStream, total: Long?, onProgress: (Long, Long?) -> Unit) {
        val buffer = ByteArray(BUFFER)
        var received = 0L
        var n = input.read(buffer)
        while (n >= 0) {
            currentCoroutineContext().ensureActive()
            received += n
            if (received > maxBytes) throw PresetDownloadException(PresetDownloadError.TOO_LARGE)
            out.write(buffer, 0, n)
            onProgress(received, total)
            n = input.read(buffer)
        }
    }

    private fun failure(e: IOException): PresetDownloadException = when (e) {
        is PresetDownloadException -> e
        is InterruptedIOException -> PresetDownloadException(PresetDownloadError.TIMEOUT, cause = e)
        else -> PresetDownloadException(PresetDownloadError.NETWORK, cause = e)
    }

    /** Refuses any request (the first or a redirect) that would leave https. */
    private object HttpsOnly : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            if (!chain.request().url.isHttps) throw PresetDownloadException(PresetDownloadError.NOT_HTTPS)
            return chain.proceed(chain.request())
        }
    }

    companion object {
        /**
         * Instrumented tests only: the client the app's downloader starts from (one that trusts a local test
         * server's certificate). Read once, when the first download runs; never set in the app.
         */
        @Volatile
        var testBaseClient: OkHttpClient? = null

        const val CONNECT_TIMEOUT_S: Long = 15
        const val READ_TIMEOUT_S: Long = 30
        const val CALL_TIMEOUT_S: Long = 180
        private const val BUFFER = 32 * 1024
        private const val USER_AGENT = "Reveal"
        private const val ACCEPT = "application/vnd.hfps.reveal.pack+zip, application/vnd.hfps.reveal.preset+json, */*"
    }
}
