// Colour constants and fixed array layouts: named inline, not as constants.
@file:Suppress("MagicNumber")

package dev.hfps.image.raw

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import dev.hfps.image.LinearImage
import dev.hfps.image.NativeLinearImage
import dev.hfps.image.color.RawColorInfo
import dev.hfps.image.source.CancelToken
import dev.hfps.image.source.CancelledException
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Semaphore

/** LibRaw demosaic algorithms (`user_qual`). */
enum class Demosaic(val code: Int) {
    LINEAR(0),
    VNG(1),
    PPG(2),
    AHD(3),
    DCB(4),
    DHT(11),
    AAHD(12),
    ;

    companion object {
        /** The default for developing; chosen from measurements in ADR 0004. */
        val DEFAULT = AHD
    }
}

/**
 * How a RAW is decoded. [halfSize] is 2x2 binning (no demosaic, about 4x faster, a quarter of the pixels): for
 * proxies. [demosaic] is ignored by LibRaw when [halfSize] is set.
 */
data class RawDecodeOptions(val halfSize: Boolean = false, val demosaic: Demosaic = Demosaic.DEFAULT)

/** A decoded RAW: pixels ([image]), what they mean ([info]) and where the time went ([stats]). */
data class RawDecodeResult<T>(val image: T, val info: RawColorInfo, val stats: RawDecodeStats)

/** Milliseconds per phase (open + identify, unpack, process, output), for the harness and logs. */
data class RawDecodeStats(val totalMs: Long, val stageMs: Map<String, Long>)

/**
 * RAW decoding through LibRaw (vendored, `src/main/cpp`) to **linear camera RGB**: black subtracted, scaled so the
 * white level is 65535, demosaiced, with no white balance, no colour matrix, no gamma and no auto-brightness.
 * Orientation is reported in [RawColorInfo.orientation], never applied.
 *
 * Input is a [ParcelFileDescriptor] (a Storage Access Framework document, a USB drive file, a local file): it is read
 * with positional reads, never copied and never closed here.
 *
 * Only one decode runs at a time: a full-size 24 MP decode peaks around 250-350 MB of native memory.
 */
object RawDecoder {
    private const val TAG = "RevealImage"

    private val loaded: Boolean by lazy {
        try {
            System.loadLibrary("revealimage")
            true
        } catch (e: UnsatisfiedLinkError) {
            // ABIs without the library (armeabi-v7a, x86): RAW files fall back to embedded previews.
            // Plain JVM unit tests have no logger and no native library: both are fine, the result is "unavailable".
            runCatching { Log.w(TAG, "librevealimage unavailable: ${e.message}") }
            false
        }
    }

    private val gate = Semaphore(1, true)

    val isAvailable: Boolean get() = loaded

    val libRawVersion: String? get() = if (loaded) RawNative.nativeVersion() else null

    /** Header metadata and colour data only (no unpack, a few ms). Levels are the file's/table's, not yet final. */
    fun readInfo(descriptor: ParcelFileDescriptor): RawColorInfo {
        check(loaded) { "LibRaw is not available on this device" }
        return gated(CancelToken.NONE) {
            RawInfoMapper.map(RawNative.nativeReadInfo(descriptor.fd), lensCorrectionOf(descriptor))
        }.first
    }

    /** Decodes into a heap [LinearImage] (6 bytes per pixel on the Java heap: right for proxies). */
    fun decode(
        descriptor: ParcelFileDescriptor,
        options: RawDecodeOptions = RawDecodeOptions(),
        cancel: CancelToken = CancelToken.NONE,
    ): RawDecodeResult<LinearImage> = decodeInternal(descriptor, options, cancel) { handle, w, h ->
        val data = ShortArray(w * h * LinearImage.CHANNELS)
        RawNative.nativeCopyToArray(handle, data)
        LinearImage(w, h, data)
    }

    /**
     * Decodes into native memory owned by the result (no Java heap, no copy): right for full-size images headed for
     * the GPU. The caller must close the returned image. One full-size image is about 150-350 MB of native memory
     * while alive: keep few open.
     */
    fun decodeNative(
        descriptor: ParcelFileDescriptor,
        options: RawDecodeOptions = RawDecodeOptions(),
        cancel: CancelToken = CancelToken.NONE,
    ): RawDecodeResult<NativeLinearImage> = decodeInternal(descriptor, options, cancel, keepSession = true) {
            handle,
            w,
            h,
        ->
        NativeLinearImage(w, h, RawNative.nativeSessionBuffer(handle)) { RawNative.nativeRelease(handle) }
    }

    /** [decode] for a local file. */
    fun decode(
        file: File,
        options: RawDecodeOptions = RawDecodeOptions(),
        cancel: CancelToken = CancelToken.NONE,
    ): RawDecodeResult<LinearImage> =
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { decode(it, options, cancel) }

    /** [decode] for a content URI (SAF); the document is read in place through its descriptor. */
    fun decode(
        resolver: ContentResolver,
        uri: Uri,
        options: RawDecodeOptions = RawDecodeOptions(),
        cancel: CancelToken = CancelToken.NONE,
    ): RawDecodeResult<LinearImage> {
        val descriptor = resolver.openFileDescriptor(uri, "r") ?: throw FileNotFoundException("No descriptor for $uri")
        return descriptor.use { decode(it, options, cancel) }
    }

    private fun <T> decodeInternal(
        descriptor: ParcelFileDescriptor,
        options: RawDecodeOptions,
        cancel: CancelToken,
        keepSession: Boolean = false,
        copy: (handle: Long, width: Int, height: Int) -> T,
    ): RawDecodeResult<T> {
        check(loaded) { "LibRaw is not available on this device" }
        val (result, stats) = gated(cancel) { listener ->
            val handle = RawNative.nativeDecode(descriptor.fd, options.halfSize, options.demosaic.code, listener)
            var transferred = false
            try {
                val size = RawNative.nativeSessionSize(handle)
                val info = RawInfoMapper.map(RawNative.nativeSessionInfo(handle), lensCorrectionOf(descriptor))
                listener.onProgress(OUTPUT_STAGE, 0, 1)
                val image = copy(handle, size[0], size[1])
                transferred = keepSession
                image to info
            } finally {
                if (!transferred) RawNative.nativeRelease(handle)
            }
        }
        return RawDecodeResult(result.first, result.second, stats)
    }

    private fun lensCorrectionOf(descriptor: ParcelFileDescriptor) = SonyLensData.read(descriptor)

    private fun <T> gated(cancel: CancelToken, block: (RawProgressListener) -> T): Pair<T, RawDecodeStats> {
        gate.acquire()
        try {
            cancel.throwIfCancelled()
            val start = System.nanoTime()
            val stages = LinkedHashMap<String, Long>()
            var phase = "open"
            var phaseStart = start
            val listener = RawProgressListener { stage, _, _ ->
                val name = phaseOf(stage)
                if (name != phase) {
                    val now = System.nanoTime()
                    stages[phase] = (stages[phase] ?: 0) + (now - phaseStart) / NANOS_PER_MILLI
                    phase = name
                    phaseStart = now
                }
                !cancel.isCancelled
            }
            val result = try {
                block(listener)
            } catch (e: LibRawException) {
                if (e.isCancelled || cancel.isCancelled) throw CancelledException()
                throw e
            }
            val end = System.nanoTime()
            stages[phase] = (stages[phase] ?: 0) + (end - phaseStart) / NANOS_PER_MILLI
            return result to RawDecodeStats((end - start) / NANOS_PER_MILLI, stages)
        } finally {
            gate.release()
        }
    }

    /** Groups LibRaw_progress flags (libraw_const.h) into phases. */
    private fun phaseOf(stage: Int): String = when {
        stage <= 0x4 -> "open"

        // START, OPEN, IDENTIFY, SIZE_ADJUST
        stage == 0x8 -> "unpack"

        // LOAD_RAW
        stage < OUTPUT_STAGE -> "process"

        // raw2image, scale colors, demosaic, convert RGB, ...
        else -> "output" // copy into the caller's storage
    }

    /** LIBRAW_PROGRESS_STAGE27: the Kotlin side reports it itself before copying out, to time that step. */
    private const val OUTPUT_STAGE = 1 shl 27
    private const val NANOS_PER_MILLI = 1_000_000L
}
