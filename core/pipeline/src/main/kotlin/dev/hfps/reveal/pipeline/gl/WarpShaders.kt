package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.geometry.Warp
import dev.hfps.reveal.pipeline.optics.LensTables
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLSL of the lens corrections and the Transform (docs/adr/0015), shared by the on-screen output pass, the export's
 * geometry pass and the scene pass: [dev.hfps.reveal.pipeline.optics.LensTables.lookup] (`lensLookup`) and
 * [Warp.sourcePoint] (`warpSource`) operation for operation. The tables are 256 x 1 RGBA32F textures read with
 * `texelFetch` and explicit interpolation (never the hardware filter).
 */
internal object WarpShaders {
    val FUNCTIONS: String = """
const float LENS_PER_RADIUS = ${ColourShaders.glslFloat(LensTables.PER_RADIUS)};
const float LENS_LAST = ${ColourShaders.glslFloat((LensTables.SIZE - 1).toFloat())};
const float WARP_W_MIN = ${ColourShaders.glslFloat(Warp.W_MIN)};

// LensTables.lookup: entry ch of the table at radius r, linear between entries, flat beyond the last.
float lensLookup(sampler2D table, int ch, float r) {
    float t = clamp(r * LENS_PER_RADIUS, 0.0, LENS_LAST);
    int i = min(int(t), ${LensTables.SIZE - 2});
    float f = t - float(i);
    float a = texelFetch(table, ivec2(i, 0), 0)[ch];
    float b = texelFetch(table, ivec2(i + 1, 0), 0)[ch];
    return a + f * (b - a);
}

// Warp.sourcePoint: plane point t to the source points of red, green and blue. norm = (centre x, centre y, half
// diagonal, 1 / half diagonal) of the image; r0..r2 the rows of the plane-to-corrected homography (normalised);
// lens = 1 when the lens table applies. False when t is behind the horizon.
bool warpSource(vec2 t, vec4 norm, vec3 r0, vec3 r1, vec3 r2, bool lens, sampler2D table,
                out vec2 sR, out vec2 sG, out vec2 sB) {
    float vx = (t.x - norm.x) * norm.w;
    float vy = (t.y - norm.y) * norm.w;
    float hz = r2.x * vx + r2.y * vy + r2.z;
    if (hz <= WARP_W_MIN) {
        return false;
    }
    float ux = (r0.x * vx + r0.y * vy + r0.z) / hz;
    float uy = (r1.x * vx + r1.y * vy + r1.z) / hz;
    if (!lens) {
        sG = vec2(norm.x + norm.z * ux, norm.y + norm.z * uy);
        sR = sG;
        sB = sG;
        return true;
    }
    float r = sqrt(ux * ux + uy * uy);
    float kr = lensLookup(table, 0, r);
    float kg = lensLookup(table, 1, r);
    float kb = lensLookup(table, 2, r);
    sR = vec2(norm.x + norm.z * (kr * ux), norm.y + norm.z * (kr * uy));
    sG = vec2(norm.x + norm.z * (kg * ux), norm.y + norm.z * (kg * uy));
    sB = vec2(norm.x + norm.z * (kb * ux), norm.y + norm.z * (kb * uy));
    return true;
}
"""

    /** `centre x, centre y, half diagonal, 1 / half diagonal` of [warp] (zeros without one). */
    fun norm(warp: Warp?): FloatArray =
        if (warp == null) FloatArray(4) else floatArrayOf(warp.centreX, warp.centreY, warp.radius, warp.invRadius)

    /** The homography's rows as three vec4 (identity without a warp). */
    fun rows(warp: Warp?): FloatArray {
        val m = warp?.matrix ?: floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        return floatArrayOf(m[0], m[1], m[2], 0f, m[3], m[4], m[5], 0f, m[6], m[7], m[8], 0f)
    }

    /** A 256 x 1 RGBA32F lookup table texture (GL thread only). Uploads only when the content changes. */
    class LutTexture {
        var texture: Int = 0
            private set
        private var content: FloatArray? = null

        /** Makes the texture hold [table] (`SIZE * 4` floats; `null` uploads ones), creating it on first use. */
        fun upload(table: FloatArray?) {
            val data = table ?: ONES
            if (texture != 0 && data.contentEquals(content)) return
            if (texture == 0) {
                val id = IntArray(1)
                GLES30.glGenTextures(1, id, 0)
                texture = id[0]
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
                GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, LensTables.SIZE, 1)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
            }
            val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
            buffer.asFloatBuffer().put(data)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
            GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
            GLES30.glTexSubImage2D(
                GLES30.GL_TEXTURE_2D, 0, 0, 0, LensTables.SIZE, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, buffer,
            )
            GlSupport.checkError("lens table upload")
            content = data.copyOf()
        }

        fun release() {
            if (texture != 0) GLES30.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
            content = null
        }

        private companion object {
            val ONES = FloatArray(LensTables.SIZE * 4) { 1f }
        }
    }

    /** A vignette table (one gain per entry) as RGBA texels (gain in `r`). */
    fun vignetteTexels(gains: FloatArray): FloatArray =
        FloatArray(gains.size * 4) { if (it % 4 == 0) gains[it / 4] else 1f }
}
