package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import java.nio.ByteBuffer

/**
 * GL-thread helper of [GpuDevelopRenderer] for the live histogram: the output pass drawn into a small RGBA8 target
 * ([LONG_EDGE] px on the long edge, explicit LOD from the mip chain) and read back, then binned on the GL thread.
 *
 * **Rate:** at most one readback per [intervalMs] while the photo changes (a slider drag), plus a trailing one so
 * the final state is always measured. The readback is synchronous (`glReadPixels` waits for the GPU), so its cost
 * is measured in [lastMs] and reported in the PR / logcat; at 10 Hz it stays well inside the frame budget.
 */
internal class HistogramReadback {
    private var texture = 0
    private var framebuffer = 0
    private var width = 0
    private var height = 0
    private var pixels: ByteBuffer? = null
    private var bytes: ByteArray? = null
    private var lastNanos = 0L

    /** Minimum time between two readbacks while the photo keeps changing. */
    var intervalMs: Long = DEFAULT_INTERVAL_MS

    /** Wall time of the last readback (draw + read + binning), milliseconds. */
    @Volatile
    var lastMs: Float = 0f
        private set

    /** Readbacks done so far. */
    @Volatile
    var count: Long = 0
        private set

    /** Milliseconds to wait before the next readback is allowed (0 = now). */
    fun waitMs(now: Long = System.nanoTime()): Long {
        val elapsed = (now - lastNanos) / NANOS_PER_MS
        return (intervalMs - elapsed).coerceAtLeast(0L)
    }

    /**
     * Draws the current stage through [drawOutput] (target framebuffer, width, height already bound as viewport)
     * into the small target, reads it back and bins it.
     */
    fun measure(imageWidth: Int, imageHeight: Int, drawOutput: (scale: Float, w: Int, h: Int) -> Unit): Histogram {
        val start = System.nanoTime()
        ensureTarget(imageWidth, imageHeight)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glViewport(0, 0, width, height)
        drawOutput(width.toFloat() / imageWidth, width, height)
        val buffer = checkNotNull(pixels)
        buffer.clear()
        GLES30.glReadPixels(0, 0, width, height, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
        GlSupport.checkError("histogram readback")
        val array = checkNotNull(bytes)
        buffer.get(array)
        val histogram = Histogram.fromRgba8(array, width * height)
        lastNanos = System.nanoTime()
        lastMs = (lastNanos - start) / NANOS_PER_MS.toFloat()
        count++
        return histogram
    }

    /** Frees the target (GL thread); it is recreated on the next [measure]. */
    fun release() {
        if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
        if (framebuffer != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
        texture = 0
        framebuffer = 0
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
        width = w
        height = h
        pixels = ByteBuffer.allocateDirect(w * h * RGBA)
        bytes = ByteArray(w * h * RGBA)
    }

    companion object {
        /** Long edge of the histogram target in pixels. */
        const val LONG_EDGE: Int = 256

        /** Default rate limit while dragging: 10 histograms per second. */
        const val DEFAULT_INTERVAL_MS: Long = 100L

        private const val RGBA = 4
        private const val NANOS_PER_MS = 1_000_000L
    }
}
