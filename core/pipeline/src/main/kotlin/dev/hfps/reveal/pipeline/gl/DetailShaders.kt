package dev.hfps.reveal.pipeline.gl

/**
 * GLSL of the Detail operator (slot 11, docs/adr/0012), `cpu/DetailOp.kt` line for line (same passes, same tap order;
 * formulas documented there). Every pass reads its textures with `texelFetch` at integer positions; a texture holds
 * the image region whose top-left pixel is `uOrigin` (0 for whole-image renders), taps are clamped to the image (as on
 * the CPU) and then to the region (only apron pixels, never a tile's core, can read a clamped tap: docs/adr/0012,
 * "Tiles").
 *
 * The input is the develop stage's output in OkLab with the luminance accumulator in `a` (`DEVELOP_FRAG` with
 * `uLabOut = 1`): `(L, a, b, acc)`.
 */
internal object DetailShaders {
    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uSrc;
uniform ivec2 uOrigin;     // image position of texel (0, 0) of every texture of this region
uniform ivec2 uImage;      // image size: taps clamp to it
uniform ivec2 uSize;       // region size: the textures' valid texels
out vec4 outColor;

ivec2 tap(ivec2 p) {
    return clamp(clamp(uOrigin + p, ivec2(0), uImage - 1) - uOrigin, ivec2(0), uSize - 1);
}
""" + Shaders.Q16

    /** One edge-avoiding à-trous level (`DetailOp.level`). */
    const val LEVEL_FRAG: String = HEADER + """
uniform int uStep;
uniform vec4 uLevel;       // x = luminance tau (0 = off), y = luminance edge 1/(2 s^2), z = chroma range, w = chroma mix
uniform float uGuard;      // chroma's luminance guard 1/(2 s^2)
const float H[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uSrc, p, 0);
    bool lumOn = uLevel.x > 0.0;
    bool chromaOn = uLevel.w > 0.0;
    float sumL = 0.0;
    float wL = 0.0;
    float sumA = 0.0;
    float sumB = 0.0;
    float wC = 0.0;
    for (int j = 0; j < 5; j++) {
        float hy = H[j];
        for (int i = 0; i < 5; i++) {
            float hw = H[i] * hy;
            vec4 q = texelFetch(uSrc, tap(p + ivec2(i - 2, j - 2) * uStep), 0);
            float dL = q.x - c.x;
            if (lumOn) {
                float wl = hw * exp(-dL * dL * uLevel.y);
                sumL += wl * q.x;
                wL += wl;
            }
            if (chromaOn) {
                float da = q.y - c.y;
                float db = q.z - c.z;
                float wc = hw * exp(-(da * da + db * db) * uLevel.z - dL * dL * uGuard);
                sumA += wc * q.y;
                sumB += wc * q.z;
                wC += wc;
            }
        }
    }
    vec4 o = c;
    if (lumOn) {
        // The detail is taken against the stored smooth value, so the levels telescope exactly (docs/adr/0012).
        float l1 = q16(sumL / wL);
        float d = c.x - l1;
        o.x = l1;
        float keep = d * d / (d * d + uLevel.x * uLevel.x);
        o.w = q16(c.w + d * keep * keep);
    }
    if (chromaOn) {
        o.y = q16(c.y + (sumA / wC - c.y) * uLevel.w);
        o.z = q16(c.z + (sumB / wC - c.z) * uLevel.w);
    }
    outColor = o;
}
"""

    /** One axis of the sharpening Gaussian (`DetailOp.blur`); the first pass reads the denoised `L + acc`. */
    const val BLUR_FRAG: String = HEADER + """
uniform ivec2 uDir;
uniform int uRadius;
uniform float uWeights[10];
uniform int uFromLab;

float value(ivec2 p) {
    vec4 v = texelFetch(uSrc, tap(p), 0);
    return uFromLab == 1 ? v.x + v.w : v.x;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float sum = uWeights[0] * value(p);
    for (int k = 1; k <= uRadius; k++) {
        sum += uWeights[k] * (value(p - k * uDir) + value(p + k * uDir));
    }
    outColor = vec4(sum, 0.0, 0.0, 1.0);
}
"""

    /**
     * Sharpening (high-pass shaping, edge mask) and back to linear Rec.2020 (`DetailOp.render`); with `uShowMask` the
     * mask as grey (the masking overlay).
     */
    val FINAL_FRAG: String = HEADER + ColourShaders.OKLAB + """
uniform sampler2D uBlur;
uniform int uSharpen;      // 1 = sharpening on
uniform int uShowMask;     // 1 = draw the mask (the blur is computed for it)
uniform vec4 uSharp;       // x = gain, y = coring^2, z = 1 / halo limit, w = gradient scale
uniform vec2 uMask;        // x = ramp start, y = ramp end (0 = no mask)

float blurAt(ivec2 p) {
    return texelFetch(uBlur, tap(p), 0).x;
}

float sharpenShape(float h) {
    float h2 = h * h;
    float cored = uSharp.y > 0.0 ? h * (h2 / (h2 + uSharp.y)) : h;
    return cored / (1.0 + abs(cored) * uSharp.z);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uSrc, p, 0);
    float l = c.x + c.w;
    float mask = 1.0;
    if (uSharpen == 1 || uShowMask == 1) {
        float g = blurAt(p);
        float h = l - g;
        if (uMask.y > 0.0) {
            float gx = blurAt(p + ivec2(1, 0)) - blurAt(p - ivec2(1, 0));
            float gy = blurAt(p + ivec2(0, 1)) - blurAt(p - ivec2(0, 1));
            float grad = 0.5 * sqrt(gx * gx + gy * gy) * uSharp.w;
            float t = clamp((grad - uMask.x) / (uMask.y - uMask.x), 0.0, 1.0);
            mask = t * t * (3.0 - 2.0 * t);
        }
        if (uSharpen == 1) {
            l += uSharp.x * sharpenShape(h) * mask;
        }
    }
    vec3 rgb = uShowMask == 1 ? fromOkLab(vec3(mask, 0.0, 0.0)) : fromOkLab(vec3(l, c.y, c.z));
    outColor = vec4(rgb, 1.0);
}
"""
}
