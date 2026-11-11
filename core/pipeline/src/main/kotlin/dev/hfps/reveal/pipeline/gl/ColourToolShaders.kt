@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.BlackWhiteOp
import dev.hfps.reveal.pipeline.cpu.ColourMixerOp
import dev.hfps.reveal.pipeline.cpu.CurveLut
import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.GradingParams
import dev.hfps.reveal.pipeline.cpu.HueBands
import dev.hfps.reveal.pipeline.cpu.VibranceSaturationOp
import dev.hfps.reveal.pipeline.gl.ColourShaders.glslFloat
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLSL of the tone curve (slot 8) and the colour mixer, black & white and colour grading (slot 9), mirroring
 * `cpu/Curves.kt` and `cpu/ColourTools.kt` line for line (docs/adr/0013). Their own std140 block (`Tools`, binding
 * [BINDING]) holds every per-recipe number and the curve table is a texture; the develop shader calls `toneCurve(c)`
 * after the global tone and `colourStage(c)` for slot 9 (vibrance/saturation and the colour tools). Needs
 * `ColourShaders.FUNCTIONS` (OkLab) first.
 */
internal object ColourToolShaders {
    /** Uniform block binding point of `Tools`. */
    const val BINDING: Int = 5

    private const val LUT = CurveLut.SIZE

    /** Texture unit of the curve table (units 0-2 are the develop stage's inputs). */
    const val CURVE_UNIT: Int = 3

    /**
     * The `Tools` uniform block (24 vec4) and the curve table: a [LUT] x 1 RGBA32F texture read with `texelFetch`
     * (rgb = `CurveLut.linear`: the operator's linear output at `v = (i / (LUT - 1))²`). The table is a texture, not a
     * block array: some drivers (SwiftShader/ANGLE) count block members against `GL_MAX_FRAGMENT_UNIFORM_VECTORS`.
     */
    const val BLOCK: String = """
uniform highp sampler2D uCurve;
layout(std140) uniform Tools {
    vec4 toolFlags;          // x = curve on, y = mixer on, z = black & white on, w = grading on
    vec4 mixerBand[8];       // MixerParams: x = hue shift (rad), y = hue-map tangent, z = saturation, w = luminance
    vec4 greyMix[2];         // BlackWhiteOp: grey mixer, bands 0-3 and 4-7
    vec4 gradeTint[2];       // GradingParams.tint: (a, b) of shadows, midtones | highlights, global
    vec4 gradeLum;           // GradingParams.luminance: shadows, midtones, highlights, global
    vec4 gradeMask;          // GradingParams.mask: q1, q2, tint half width, luminance half width
    vec4 mixerOptions;       // x = 1 when the hue map is active
    vec4 curveLo;            // CurveLut.table at e = 0 (rgb), for the slope-1 extension below 0
    vec4 curveHi;            // CurveLut.table at e = 1 (rgb), above 1
};
"""

    private fun floatArrayGlsl(name: String, values: FloatArray): String =
        "const float $name[${values.size}] = float[${values.size}](" +
            values.joinToString(", ") { glslFloat(it) } + ");"

    /** Operator functions; constants are the CPU's own float values. */
    val FUNCTIONS: String = """
${floatArrayGlsl("BAND_CENTRE", HueBands.CENTRES)}
${floatArrayGlsl("BAND_GAP", HueBands.GAPS_F)}
${floatArrayGlsl("BAND_INV_GAP", HueBands.INV_GAPS)}
const float BAND_TWO_PI = ${glslFloat(HueBands.TWO_PI)};
const float BAND_C_HALF = ${glslFloat(HueBands.C_HALF)};
const float MIXER_LUM_STOPS = ${glslFloat(ColourMixerOp.LUM_STOPS)};
const float GREY_STOPS = ${glslFloat(BlackWhiteOp.GREY_STOPS)};
const float GRADE_LUM_STOPS = ${glslFloat(GradingParams.LUM_STOPS)};
const float TOOLS_C_SOFT = ${glslFloat(VibranceSaturationOp.C_SOFT)};

// CurveOp.encode / decode: the sRGB transfer, odd-extended.
float curveEncode(float v) {
    float a = abs(v);
    float e = a <= 0.0031308 ? 12.92 * a : 1.055 * pow(a, 1.0 / 2.4) - 0.055;
    return v < 0.0 ? -e : e;
}

float curveDecode(float e) {
    float a = abs(e);
    float v = a <= 0.04045 ? a / 12.92 : pow((a + 0.055) / 1.055, 2.4);
    return e < 0.0 ? -v : v;
}

// CurveLut.apply: the linear table in sqrt(v) on 0..1, the encoded slope-1 extension outside.
float curveApply(float v, int c) {
    if (v < 0.0 || v > 1.0) {
        float e = curveEncode(v);
        float y = e < 0.0 ? curveLo[c] + e : curveHi[c] + (e - 1.0);
        return curveDecode(y);
    }
    float t = sqrt(v) * ${glslFloat((LUT - 1).toFloat())};
    int i = min(int(floor(t)), ${LUT - 2});
    float f = t - float(i);
    float y0 = texelFetch(uCurve, ivec2(i, 0), 0)[c];
    float y1 = texelFetch(uCurve, ivec2(i + 1, 0), 0)[c];
    return y0 + f * (y1 - y0);
}

vec3 toneCurve(vec3 c) {
    if (toolFlags.x == 0.0) {
        return c;
    }
    return vec3(curveApply(c.r, 0), curveApply(c.g, 1), curveApply(c.b, 2));
}

// HueBands.unwrap / segment / position / smooth / chromaWeight.
float bandUnwrap(float h) {
    float x = h;
    if (x < BAND_CENTRE[0]) {
        x += BAND_TWO_PI;
    }
    if (x >= BAND_CENTRE[0] + BAND_TWO_PI) {
        x -= BAND_TWO_PI;
    }
    return x;
}

int bandSegment(float x) {
    int k = 7;
    for (int j = 0; j < 7; j++) {
        if (x < BAND_CENTRE[j + 1]) {
            k = j;
            break;
        }
    }
    return k;
}

float bandSmooth(float t) {
    return t * t * (3.0 - 2.0 * t);
}

float bandChromaWeight(float c) {
    float q = (c / BAND_C_HALF) * (c / BAND_C_HALF);
    return q / (1.0 + q);
}

float greyValue(int k) {
    return k < 4 ? greyMix[0][k] : greyMix[1][k - 4];
}

// ColourGradingOp.smoothstep.
float gradeStep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// ColourMixerOp.apply / BlackWhiteOp.apply on one Lab pixel.
vec3 mixerOrGrey(vec3 lab) {
    float a = lab.y;
    float b = lab.z;
    float c = sqrt(a * a + b * b);
    bool grey = toolFlags.z != 0.0;
    if (c <= 1e-7) {
        return grey ? vec3(lab.x, 0.0, 0.0) : lab;
    }
    float h = atan(b, a);
    float x = bandUnwrap(h);
    int k = bandSegment(x);
    int k1 = k == 7 ? 0 : k + 1;
    float t = clamp((x - BAND_CENTRE[k]) * BAND_INV_GAP[k], 0.0, 1.0);
    float s = bandSmooth(t);
    if (grey) {
        float g0 = greyValue(k);
        float g = g0 + s * (greyValue(k1) - g0);
        return vec3(lab.x * exp2(GREY_STOPS * g * bandChromaWeight(c)), 0.0, 0.0);
    }
    vec4 m0 = mixerBand[k];
    vec4 m1 = mixerBand[k1];
    float sat = m0.z + s * (m1.z - m0.z);
    float lum = m0.w + s * (m1.w - m0.w);
    float q = c / TOOLS_C_SOFT;
    float fs = sat <= 0.0 ? 1.0 + sat : 1.0 + sat / (1.0 + q * q);
    float l = lab.x * exp2(MIXER_LUM_STOPS * lum * bandChromaWeight(c));
    if (mixerOptions.x != 0.0) {
        float gap = BAND_GAP[k];
        float t2 = t * t;
        float t3 = t2 * t;
        float y0 = m0.x;
        float y1 = gap + m1.x;
        float rel = (2.0 * t3 - 3.0 * t2 + 1.0) * y0 + (t3 - 2.0 * t2 + t) * (m0.y * gap) +
            (-2.0 * t3 + 3.0 * t2) * y1 + (t3 - t2) * (m1.y * gap);
        float h2 = BAND_CENTRE[k] + rel;
        float c2 = c * fs;
        return vec3(l, c2 * cos(h2), c2 * sin(h2));
    }
    return vec3(l, a * fs, b * fs);
}

// ColourGradingOp.apply on one Lab pixel.
vec3 colourGrade(vec3 lab) {
    float x = clamp(lab.x, 0.0, 1.0);
    float sw = 1.0 - gradeStep(gradeMask.x - gradeMask.z, gradeMask.x + gradeMask.z, x);
    float hw = gradeStep(gradeMask.y - gradeMask.z, gradeMask.y + gradeMask.z, x);
    float mw = 1.0 - sw - hw;
    float swl = 1.0 - gradeStep(gradeMask.x - gradeMask.w, gradeMask.x + gradeMask.w, x);
    float hwl = gradeStep(gradeMask.y - gradeMask.w, gradeMask.y + gradeMask.w, x);
    float mwl = 1.0 - swl - hwl;
    float l = lab.x * exp2(GRADE_LUM_STOPS * (swl * gradeLum.x + mwl * gradeLum.y + hwl * gradeLum.z + gradeLum.w));
    float ta = sw * gradeTint[0].x + mw * gradeTint[0].z + hw * gradeTint[1].x + gradeTint[1].z;
    float tb = sw * gradeTint[0].y + mw * gradeTint[0].w + hw * gradeTint[1].y + gradeTint[1].w;
    return vec3(l, lab.y + l * ta, lab.z + l * tb);
}

// ColourStageOp.vibranceSaturationLab (VibranceSaturationOp.gain on an OkLab pixel).
vec3 vibSatLab(vec3 lab) {
    float ch = sqrt(lab.y * lab.y + lab.z * lab.z);
    if (ch <= 1e-7) {
        return lab;
    }
    float s = colourOp.x;
    float v = colourOp.y;
    float q = ch / VS_C_SOFT;
    float fs = s <= 0.0 ? 1.0 + s : 1.0 + s / (1.0 + q * q);
    float u = 1.0 - min(ch / VS_C_VIB, 1.0);
    float w = u * u;
    float protect = 1.0;
    if (v > 0.0) {
        float lobe = (1.0 + (lab.y / ch) * VS_SKIN_COS + (lab.z / ch) * VS_SKIN_SIN) * 0.5;
        float l2 = lobe * lobe;
        protect = 1.0 - VS_SKIN_PROTECT * (l2 * l2);
    }
    float k = fs * (1.0 + v * w * protect);
    return vec3(lab.x, lab.y * k, lab.z * k);
}

// Slot 9: ColourStageOp when a colour tool is on (one OkLab round trip for vibrance/saturation, the mixer or
// black & white, and grading), else VibranceSaturationOp as before.
vec3 colourStage(vec3 c) {
    if (toolFlags.y == 0.0 && toolFlags.z == 0.0 && toolFlags.w == 0.0) {
        return vibranceSaturation(c);
    }
    vec3 lab = toOkLab(c);
    if (colourOp.w != 0.0) {
        lab = vibSatLab(lab);
    }
    if (toolFlags.y != 0.0 || toolFlags.z != 0.0) {
        lab = mixerOrGrey(lab);
    }
    if (toolFlags.w != 0.0) {
        lab = colourGrade(lab);
    }
    return fromOkLab(lab);
}
"""

    private const val BLOCK_FLOATS = 4 + 4 * 8 + 8 + 8 + 4 + 4 + 4 + 4 + 4

    /** std140 contents of `Tools` for [p]. */
    @Suppress("CyclomaticComplexMethod")
    fun toolsBlock(p: DevelopParams): ByteBuffer {
        val b = ByteBuffer.allocateDirect(BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val lut = p.curve?.table
        val tools = p.tools
        val mixer = tools.mixer
        val grey = tools.grey
        val grading = tools.grading
        b.putFloat(flag(lut != null)).putFloat(flag(mixer != null)).putFloat(flag(grey != null))
            .putFloat(flag(grading != null))
        for (k in 0 until HueBands.COUNT) {
            if (mixer == null) {
                b.putFloat(0f).putFloat(1f).putFloat(0f).putFloat(0f)
            } else {
                b.putFloat(mixer.shift[k]).putFloat(mixer.tangent[k]).putFloat(mixer.saturation[k])
                    .putFloat(mixer.luminance[k])
            }
        }
        for (k in 0 until HueBands.COUNT) b.putFloat(grey?.values?.get(k) ?: 0f)
        for (i in 0 until 8) b.putFloat(grading?.tint?.get(i) ?: 0f)
        for (i in 0 until 4) b.putFloat(grading?.luminance?.get(i) ?: 0f)
        val mask = grading?.mask ?: floatArrayOf(0.3f, 0.8f, 0.15f, 0.25f)
        mask.forEach { b.putFloat(it) }
        b.putFloat(flag(mixer?.hueActive == true)).putFloat(0f).putFloat(0f).putFloat(0f)
        val last = (LUT - 1) * 3
        for (c in 0 until 3) b.putFloat(lut?.get(c) ?: 0f)
        b.putFloat(0f)
        for (c in 0 until 3) b.putFloat(lut?.get(last + c) ?: 1f)
        b.putFloat(0f)
        b.flip()
        return b
    }

    /** The colour tools' GL objects of one develop program (GL thread only). */
    class Gl(program: Int) {
        private val ubo: Int
        private val curveTex: Int
        private var curve: CurveLut? = null
        private var curveUploaded = false

        init {
            val index = GLES30.glGetUniformBlockIndex(program, "Tools")
            check(index != GLES30.GL_INVALID_INDEX) { "uniform block Tools missing" }
            GLES30.glUniformBlockBinding(program, index, BINDING)
            GLES30.glUseProgram(program)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCurve"), CURVE_UNIT)
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0)
            ubo = ids[0]
            GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, BINDING, ubo)
            GLES30.glGenTextures(1, ids, 0)
            curveTex = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA32F, LUT, 1)
            for (param in intArrayOf(GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_TEXTURE_MAG_FILTER)) {
                GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, param, GLES30.GL_NEAREST)
            }
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        }

        /** Uploads [p]'s block and, when it changed, its curve table; binds the table to [CURVE_UNIT]. */
        fun upload(p: DevelopParams) {
            val block = toolsBlock(p)
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, ubo)
            GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, block.remaining(), block, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + CURVE_UNIT)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, curveTex)
            val lut = p.curve
            if (!curveUploaded || lut != curve) {
                val table = lut?.linear
                val data = ByteBuffer.allocateDirect(LUT * 4 * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
                for (i in 0 until LUT) {
                    for (c in 0 until 3) data.putFloat(table?.get(3 * i + c) ?: 0f)
                    data.putFloat(0f)
                }
                data.flip()
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, LUT, 1, GLES30.GL_RGBA, GLES30.GL_FLOAT, data)
                curve = lut
                curveUploaded = true
            }
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        }

        fun release() {
            GLES30.glDeleteBuffers(1, intArrayOf(ubo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(curveTex), 0)
        }
    }

    private fun flag(on: Boolean): Float = if (on) 1f else 0f
}
