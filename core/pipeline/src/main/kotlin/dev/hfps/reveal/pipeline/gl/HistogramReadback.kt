package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * GL-thread helper of [GpuDevelopRenderer] for the live histogram: the output pass drawn into a small RGBA8 target
 * ([LONG_EDGE] px on the long edge, explicit LOD from the mip chain), read back **asynchronously** into a pixel
 * buffer object guarded by a fence, then binned on the GL thread once the GPU is done. A synchronous `glReadPixels`
 * waits for the whole frame's GPU work (measured at about 50 ms on the emulator's translated GPU), which would stall
 * the slider; the PBO path costs only the small draw and, a frame later, the map and binning.
 *
 * **Rate:** at most one request per [intervalMs] while the photo changes (a slider drag), plus a trailing one so the
 * final state is always measured.
 */
internal class HistogramReadback {
    private var texture = 0
    private var framebuffer = 0
    private var pbo = 0
    private var width = 0
    private var height = 0
    private var bytes: ByteArray? = null
    private var fence = 0L
    private var requestNanos = 0L
    private var lastRequestNanos = 0L
    private var cpuNanos = 0L

    /** Minimum time between two requests while the photo keeps changing. */
    var intervalMs: Long = DEFAULT_INTERVAL_MS

    /** GL-thread time of the last histogram (request draw + map + binning), milliseconds. */
    @Volatile
    var lastMs: Float = 0f
        private set

    /** GL-thread time of the last request alone (draw + readback issue), milliseconds. */
    @Volatile
    var lastRequestMs: Float = 0f
        private set

    /** Time from the last request to its result (GPU latency included), milliseconds. */
    @Volatile
    var lastLatencyMs: Float = 0f
        private set

    /** Histograms delivered so far. */
    @Volatile
    var count: Long = 0
        private set

    /** True while a readback is in flight. */
    val pending: Boolean get() = fence != 0L

    /** Milliseconds to wait before the next request is allowed (0 = now). */
    fun waitMs(now: Long = System.nanoTime()): Long {
        val elapsed = (now - lastRequestNanos) / NANOS_PER_MS
        return (intervalMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Draws the current stage through [drawOutput] (scale, width, height; framebuffer and viewport already bound)
     * into the small target and starts its asynchronous readback.
     */
    fun request(imageWidth: Int, imageHeight: Int, drawOutput: (scale: Float, w: Int, h: Int) -> Unit) {
        val start = System.nanoTime()
        ensureTarget(imageWidth, imageHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        drawOutput(width.toFloat() / imageWidth, width, height)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        fence = GLES30.glFenceSync(GLES30.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
        GLES30.glFlush()
        GlSupport.checkError("histogram request")
        requestNanos = start
        lastRequestNanos = start
        cpuNanos = System.nanoTime() - start
        lastRequestMs = cpuNanos / NANOS_PER_MS.toFloat()
    }

    /** The histogram of the pending request once the GPU finished it, else `null` (never blocks). */
    @Suppress("ReturnCount")
    fun poll(): Histogram? {
        if (fence == 0L) return null
        val status = GLES30.glClientWaitSync(fence, 0, 0L)
        if (status != GLES30.GL_ALREADY_SIGNALED && status != GLES30.GL_CONDITION_SATISFIED) return null
        val start = System.nanoTime()
        GLES30.glDeleteSync(fence)
        fence = 0L
        val array = checkNotNull(bytes)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        val mapped = GLES30.glMapBufferRange(GLES30.GL_PIXEL_PACK_BUFFER, 0, array.size, GLES30.GL_MAP_READ_BIT)
            as ByteBuffer?
        mapped?.get(array)
        GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        GlSupport.checkError("histogram map")
        val histogram = Histogram.fromRgba8(array, width * height)
        val end = System.nanoTime()
        lastMs = (cpuNanos + end - start) / NANOS_PER_MS.toFloat()
        lastLatencyMs = (end - requestNanos) / NANOS_PER_MS.toFloat()
        count++
        return histogram
    }

    /** Frees the target (GL thread); it is recreated on the next [request]. */
    fun release() {
        if (fence != 0L) GLES30.glDeleteSync(fence)
        fence = 0L
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        if (pbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(pbo), 0)
        texture = 0
        framebuffer = 0
        pbo = 0
        width = 0
        height = 0
    }

    private fun ensureTarget(imageWidth: Int, imageHeight: Int) {
        val scale = LONG_EDGE.toFloat() / maxOf(imageWidth, imageHeight)
        val w = (imageWidth * scale).toInt().coerceAtLeast(1)
        val h = (imageHeight * scale).toInt().coerceAtLeast(1)
        if (w == width && h == height && texture != 0) return
        release()
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, w, h)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(
            GLES30.GL_FRAMEBUFFER,
            GLES30.GL_COLOR_ATTACHMENT0,
            GLES30.GL_TEXTURE_2D,
            texture,
            0,
        )
        GLES30.glGenBuffers(1, ids, 0)
        pbo = ids[0]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pbo)
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, w * h * RGBA, null, GLES30.GL_STREAM_READ)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        width = w
        height = h
        bytes = ByteArray(w * h * RGBA)
    }

    companion object {
        /** Long edge of the histogram target in pixels. */
        const val LONG_EDGE: Int = 256

        /** Default rate limit while dragging: 10 histograms per second. */
        const val DEFAULT_INTERVAL_MS: Long = 100L

        /** How often a pending readback is polled. */
        const val POLL_MS: Long = 4L

        private const val RGBA = 4
        private const val NANOS_PER_MS = 1_000_000L
    }
}
