@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.view.Surface

/** An EGL display + OpenGL ES 3 context, current on the thread that created it, with a 1x1 pbuffer as the base. */
internal class EglCore {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var window: EGLSurface = EGL14.EGL_NO_SURFACE

    /** The context (valid after [create]); another [EglCore] can share its textures ([create] with `share`). */
    val eglContext: EGLContext get() = context

    /**
     * Creates the context and makes it current on the calling thread. With [share], the new context shares textures
     * and sync objects with that context (EGL share group): the zoom tile worker renders into the editor's atlas.
     */
    fun create(share: EGLContext = EGL14.EGL_NO_CONTEXT) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, BITS_8,
            EGL14.EGL_GREEN_SIZE, BITS_8,
            EGL14.EGL_BLUE_SIZE, BITS_8,
            EGL14.EGL_ALPHA_SIZE, BITS_8,
            EGL14.EGL_NONE,
        )
        if (!EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) || count[0] == 0) {
            throw GlesRequirementException("No EGL config with OpenGL ES 3 and RGBA8888 for window + pbuffer")
        }
        config = configs[0]
        val contextAttributes = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        context = EGL14.eglCreateContext(display, config, share, contextAttributes, 0)
        if (context == EGL14.EGL_NO_CONTEXT) {
            throw GlesRequirementException("Could not create an OpenGL ES 3 context (EGL error ${EGL14.eglGetError()})")
        }
        pbuffer =
            EGL14.eglCreatePbufferSurface(
                display,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
        makeCurrent(pbuffer)
    }

    /** Makes the window surface for [surface] current (replacing a previous one). */
    fun attachWindow(surface: Surface) {
        detachWindow()
        window = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(window != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed: ${EGL14.eglGetError()}" }
        makeCurrent(window)
    }

    /** Switches back to the pbuffer and destroys the window surface. */
    fun detachWindow() {
        if (window != EGL14.EGL_NO_SURFACE) {
            makeCurrent(pbuffer)
            EGL14.eglDestroySurface(display, window)
            window = EGL14.EGL_NO_SURFACE
        }
    }

    val hasWindow: Boolean get() = window != EGL14.EGL_NO_SURFACE

    /** Size in pixels of the window surface. */
    fun windowSize(): Pair<Int, Int> {
        val w = IntArray(1)
        val h = IntArray(1)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_WIDTH, w, 0)
        EGL14.eglQuerySurface(display, window, EGL14.EGL_HEIGHT, h, 0)
        return w[0] to h[0]
    }

    /** Presents the window surface. */
    fun swap(): Boolean = EGL14.eglSwapBuffers(display, window)

    /** Destroys everything. */
    fun release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (pbuffer != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, pbuffer)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        window = EGL14.EGL_NO_SURFACE
        pbuffer = EGL14.EGL_NO_SURFACE
    }

    private fun makeCurrent(surface: EGLSurface) {
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) {
            "eglMakeCurrent failed: ${EGL14.eglGetError()}"
        }
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val BITS_8 = 8
    }
}
