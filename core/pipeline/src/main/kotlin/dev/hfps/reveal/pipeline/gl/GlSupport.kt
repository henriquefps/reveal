@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES20
import android.opengl.GLES30

/** Thrown when the device cannot run the GPU pipeline; the message says what is missing (shown in diagnostics). */
class GlesRequirementException(message: String) : IllegalStateException(message)

/** Thrown when an OpenGL call reports an error or a shader fails to build. */
class GlException(message: String) : RuntimeException(message)

/** What the current GL context offers, as reported by the driver. */
data class GlInfo(
    val version: String,
    val major: Int,
    val minor: Int,
    val renderer: String,
    val hasFloatRenderTargets: Boolean,
)

internal object GlSupport {
    private const val EXT_COLOR_BUFFER_FLOAT = "GL_EXT_color_buffer_float"
    private const val MIN_MAJOR = 3
    private const val ES_3_2_MINOR = 2

    /** Reads the driver information of the current context. */
    fun query(): GlInfo {
        val version = GLES20.glGetString(GLES20.GL_VERSION).orEmpty()
        val ints = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAJOR_VERSION, ints, 0)
        val major = ints[0]
        GLES30.glGetIntegerv(GLES30.GL_MINOR_VERSION, ints, 0)
        val minor = ints[0]
        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
        val floatTargets = (major > MIN_MAJOR || (major == MIN_MAJOR && minor >= ES_3_2_MINOR)) ||
            extensions.split(' ').contains(EXT_COLOR_BUFFER_FLOAT)
        return GlInfo(version, major, minor, GLES20.glGetString(GLES20.GL_RENDERER).orEmpty(), floatTargets)
    }

    /** Fails clearly when OpenGL ES 3.0+ with float render targets is not available. */
    fun require(info: GlInfo) {
        if (info.major < MIN_MAJOR) {
            throw GlesRequirementException("OpenGL ES 3.0 or newer is required; this device reports '${info.version}'")
        }
        if (!info.hasFloatRenderTargets) {
            throw GlesRequirementException(
                "Float render targets (OpenGL ES 3.2 or EXT_color_buffer_float) are required; " +
                    "this device reports '${info.version}' on '${info.renderer}' without them",
            )
        }
    }

    fun checkError(what: String) {
        val error = GLES20.glGetError()
        if (error != GLES20.GL_NO_ERROR) throw GlException("OpenGL error 0x${error.toString(16)} after $what")
    }

    fun compile(vertex: String, fragment: String): Int {
        val v = compileShader(GLES30.GL_VERTEX_SHADER, vertex)
        val f = compileShader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, v)
        GLES30.glAttachShader(program, f)
        GLES30.glLinkProgram(program)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            throw GlException("Program link failed: $log")
        }
        GLES30.glDeleteShader(v)
        GLES30.glDeleteShader(f)
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            throw GlException("Shader compile failed: $log")
        }
        return shader
    }
}
