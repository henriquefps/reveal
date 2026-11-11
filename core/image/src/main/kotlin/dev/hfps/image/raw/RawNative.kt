package dev.hfps.image.raw

import androidx.annotation.Keep
import java.io.IOException
import java.nio.ByteBuffer

/** Thrown by the native layer. [code] is a LibRaw error (negative) or a Reveal one (<= -1000). */
@Keep
class LibRawException(val code: Int, message: String) : IOException(message) {
    val isCancelled: Boolean get() = code == LIBRAW_CANCELLED_BY_CALLBACK

    companion object {
        const val LIBRAW_CANCELLED_BY_CALLBACK = -100010
    }
}

/** Called from native code at every LibRaw stage; return false to cancel. */
@Keep
fun interface RawProgressListener {
    fun onProgress(stage: Int, iteration: Int, expected: Int): Boolean
}

/** Flat metadata snapshot from the native layer; indices in [RawNativeLayout]. */
@Keep
class RawNativeInfo(val numbers: DoubleArray, val strings: Array<String?>)

/** Index layout of [RawNativeInfo]; mirrors `enum Field` and `enum Str` in the JNI file. */
internal object RawNativeLayout {
    const val RAW_WIDTH = 0
    const val RAW_HEIGHT = 1
    const val LEFT_MARGIN = 2
    const val TOP_MARGIN = 3
    const val WIDTH = 4
    const val HEIGHT = 5
    const val FLIP = 6
    const val COLORS = 7
    const val FILTERS = 8
    const val CROP_LEFT = 9
    const val CROP_TOP = 10
    const val CROP_WIDTH = 11
    const val CROP_HEIGHT = 12
    const val BLACK = 13
    const val CBLACK0 = 14
    const val CBLACK_PATTERN_W = 18
    const val CBLACK_PATTERN_H = 19
    const val MAXIMUM = 20
    const val DATA_MAXIMUM = 21
    const val LINEAR_MAX0 = 22
    const val CAM_MUL0 = 26
    const val PRE_MUL0 = 30
    const val RGB_CAM = 34
    const val CAM_XYZ = 46
    const val BASELINE_EXPOSURE = 58
    const val DNG_VERSION = 59
    const val RAW_BPS = 60
    const val LEVELS_FINAL = 61
    const val OUT_WIDTH = 62
    const val OUT_HEIGHT = 63
    const val DNG_WHITE = 64
    const val ISO = 65
    const val COUNT = 69

    const val STR_MAKE = 0
    const val STR_MODEL = 1
    const val STR_NORMALIZED_MAKE = 2
    const val STR_NORMALIZED_MODEL = 3
    const val STR_SOFTWARE = 4
    const val STR_LIBRAW = 5
}

/** JNI entry points (librevealimage.so). Descriptors are borrowed: they are read, never closed. */
internal object RawNative {
    @JvmStatic external fun nativeVersion(): String

    @JvmStatic external fun nativeReadInfo(fd: Int): RawNativeInfo

    @JvmStatic
    external fun nativeDecode(fd: Int, halfSize: Boolean, demosaic: Int, listener: RawProgressListener?): Long

    @JvmStatic external fun nativeSessionInfo(handle: Long): RawNativeInfo

    @JvmStatic external fun nativeSessionSize(handle: Long): IntArray

    @JvmStatic external fun nativeCopyToArray(handle: Long, out: ShortArray)

    @JvmStatic external fun nativeCopyToBuffer(handle: Long, buffer: ByteBuffer)

    @JvmStatic external fun nativeRelease(handle: Long)
}
