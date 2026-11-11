@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import dev.hfps.reveal.pipeline.cpu.DevelopParams
import dev.hfps.reveal.pipeline.cpu.OkLab
import dev.hfps.reveal.pipeline.cpu.ToneCurve
import dev.hfps.reveal.pipeline.cpu.VibranceSaturationOp
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * GLSL of the profile (slot 6) and colour (slot 9) operators, mirroring `cpu/ColourOperators.kt`, `cpu/ToneCurve.kt`
 * and `cpu/OkLab.kt` line for line (formulas documented there, docs/adr/0008). They have their own std140 block
 * (`Look`, binding [BINDING]) so the develop block of the other operators is untouched. The fused stage-1 shader
 * includes [BLOCK] and [FUNCTIONS] and calls `profileLook(c)` after exposure (and highlight reconstruction) and
 * `vibranceSaturation(c)` after tone.
 */
internal object ColourShaders {
    /** Uniform block binding point of `Look`. */
    const val BINDING: Int = 3

    /** The `Look` uniform block. */
    const val BLOCK: String = """
layout(std140) uniform Look {
    mat4 lookPre;            // ProfileOp: upper-left 3x3 column-major, gain folded in
    mat4 lookPost;           // ProfileOp: curve space back to Rec.2020
    vec4 lookKnots[16];      // ToneCurve: x = knot value y[i], y = tangent m[i]
    vec4 lookCurve;          // x = profile enabled, y = toe slope y[0]/x0, z = x0, w = last knot x
    vec4 hueChroma[2];       // HueTreatment chroma coefficients c0..c6
    vec4 hueShift[2];        // HueTreatment hue coefficients d0..d6 (radians)
    vec4 colourOp;           // x = saturation, y = vibrance, z = hue treatment on, w = vibrance/saturation on
};
"""

    private fun mat3Glsl(rowMajor: FloatArray): String {
        val cols = (0 until 3).flatMap { c -> (0 until 3).map { r -> rowMajor[r * 3 + c] } }
        return "mat3(" + cols.joinToString(", ") { glslFloat(it) } + ")"
    }

    /** A float literal GLSL ES reads back to the same float (Kotlin's shortest round-trip form). */
    fun glslFloat(v: Float): String {
        val s = v.toString()
        return if (s.contains('.') || s.contains('E')) s else "$s.0"
    }

    /** OkLab (`cpu/OkLab.kt`): `toOkLab` / `fromOkLab` on linear Rec.2020, shared with the Detail passes. */
    val OKLAB: String = """
const mat3 OK_RGB_TO_LMS = ${mat3Glsl(OkLab.RGB_TO_LMS)};
const mat3 OK_LMS_TO_LAB = ${mat3Glsl(OkLab.LMS_TO_LAB)};
const mat3 OK_LAB_TO_LMS = ${mat3Glsl(OkLab.LAB_TO_LMS)};
const mat3 OK_LMS_TO_RGB = ${mat3Glsl(OkLab.LMS_TO_RGB)};

vec3 okCbrt(vec3 v) {
    return sign(v) * pow(abs(v), vec3(1.0 / 3.0));
}

vec3 toOkLab(vec3 c) {
    return OK_LMS_TO_LAB * okCbrt(OK_RGB_TO_LMS * c);
}

vec3 fromOkLab(vec3 lab) {
    vec3 l = OK_LAB_TO_LMS * lab;
    return OK_LMS_TO_RGB * (l * l * l);
}
"""

    /** Operator functions; constants are the CPU's own float values. */
    val FUNCTIONS: String = OKLAB + """
const float LOOK_FIRST_STOP = ${glslFloat(ToneCurve.FIRST_STOP)};
const float VS_C_SOFT = ${glslFloat(VibranceSaturationOp.C_SOFT)};
const float VS_C_VIB = ${glslFloat(VibranceSaturationOp.C_VIB)};
const float VS_SKIN_COS = ${glslFloat(VibranceSaturationOp.SKIN_COS)};
const float VS_SKIN_SIN = ${glslFloat(VibranceSaturationOp.SKIN_SIN)};
const float VS_SKIN_PROTECT = ${glslFloat(VibranceSaturationOp.SKIN_PROTECT)};

float lookCurveEval(float x) {
    if (x <= lookCurve.z) {
        return max(x, 0.0) * lookCurve.y;
    }
    float t = log2(min(x, lookCurve.w)) - LOOK_FIRST_STOP;
    int i = min(int(floor(t)), 14);
    float f = t - float(i);
    float f2 = f * f;
    float f3 = f2 * f;
    vec2 k0 = lookKnots[i].xy;
    vec2 k1 = lookKnots[i + 1].xy;
    return (2.0 * f3 - 3.0 * f2 + 1.0) * k0.x + (f3 - 2.0 * f2 + f) * k0.y +
        (-2.0 * f3 + 3.0 * f2) * k1.x + (f3 - f2) * k1.y;
}


vec3 hueTreatment(vec3 lab) {
    float c = sqrt(lab.y * lab.y + lab.z * lab.z);
    if (c <= 1e-7) {
        return lab;
    }
    float c1 = lab.y / c;
    float s1 = lab.z / c;
    float c2 = c1 * c1 - s1 * s1;
    float s2 = 2.0 * c1 * s1;
    float c3 = c2 * c1 - s2 * s1;
    float s3 = s2 * c1 + c2 * s1;
    vec4 b0 = vec4(1.0, c1, s1, c2);
    vec4 b1 = vec4(s2, c3, s3, 0.0);
    float gain = max(0.0, 1.0 + dot(hueChroma[0], b0) + dot(hueChroma[1], b1));
    float dh = dot(hueShift[0], b0) + dot(hueShift[1], b1);
    float cd = cos(dh);
    float sd = sin(dh);
    return vec3(lab.x, gain * (lab.y * cd - lab.z * sd), gain * (lab.y * sd + lab.z * cd));
}

vec3 profileLook(vec3 c) {
    if (lookCurve.x == 0.0) {
        return c;
    }
    vec3 v = mat3(lookPre) * c;
    v = vec3(lookCurveEval(v.r), lookCurveEval(v.g), lookCurveEval(v.b));
    v = mat3(lookPost) * v;
    if (colourOp.z != 0.0) {
        v = fromOkLab(hueTreatment(toOkLab(v)));
    }
    return v;
}

vec3 vibranceSaturation(vec3 c) {
    if (colourOp.w == 0.0) {
        return c;
    }
    vec3 lab = toOkLab(c);
    float ch = sqrt(lab.y * lab.y + lab.z * lab.z);
    if (ch <= 1e-7) {
        return c;
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
    return fromOkLab(vec3(lab.x, lab.y * k, lab.z * k));
}
"""

    private const val BLOCK_FLOATS = 16 + 16 + 4 * ToneCurve.KNOTS + 4 + 8 + 8 + 4

    /** std140 contents of `Look` for [p]. */
    fun lookBlock(p: DevelopParams): ByteBuffer {
        val b = ByteBuffer.allocateDirect(BLOCK_FLOATS * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
        val profile = p.profile
        putMat4(b, profile.pre)
        putMat4(b, profile.post)
        for (i in 0 until ToneCurve.KNOTS) {
            b.putFloat(profile.curve.y[i]).putFloat(profile.curve.m[i]).putFloat(0f).putFloat(0f)
        }
        b.putFloat(if (profile.enabled) 1f else 0f).putFloat(profile.curve.toeSlope)
            .putFloat(ToneCurve.X0).putFloat(ToneCurve.X_LAST)
        putCoefficients(b, profile.hueChroma)
        putCoefficients(b, profile.hueShift)
        b.putFloat(p.colour.saturation).putFloat(p.colour.vibrance)
            .putFloat(if (profile.enabled && profile.hueEnabled) 1f else 0f)
            .putFloat(if (p.colour.enabled) 1f else 0f)
        b.flip()
        return b
    }

    private fun putCoefficients(b: ByteBuffer, c: FloatArray) {
        for (i in 0 until 8) b.putFloat(if (i < c.size) c[i] else 0f)
    }

    private fun putMat4(b: ByteBuffer, rowMajor3: FloatArray) {
        for (col in 0 until 3) {
            for (row in 0 until 3) b.putFloat(rowMajor3[row * 3 + col])
            b.putFloat(0f)
        }
        b.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(1f)
    }
}
