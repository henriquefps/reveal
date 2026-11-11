package dev.hfps.reveal.pipeline.gl

/**
 * GLSL of the Detail operator (slot 11, docs/adr/0012) and of the local Effects that share its final pass (slot 12,
 * docs/adr/0014): `cpu/DetailOp.kt` line for line (same passes, same tap order; formulas documented there). Every pass
 * reads its textures with `texelFetch` at integer positions; a texture holds the image region whose top-left pixel is
 * `uOrigin` (0 for whole-image renders), taps are clamped to the image (as on the CPU) and then to the region (only
 * apron pixels, never a tile's core, can read a clamped tap: docs/adr/0012, "Tiles").
 *
 * The input is the develop stage's output in OkLab (`DEVELOP_FRAG` with `uLabOut = 1`): `(L, a, b, 0)`. Luminance
 * levels write `(L_i, acc)` (RG16F), colour levels run on a half-resolution copy whose texel `J` covers image pixels
 * `2J, 2J+1` (its region starts at `uOrigin / 2`).
 */
internal object DetailShaders {
    private const val PRELUDE = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    private const val HEADER = PRELUDE + """
uniform sampler2D uSrc;
uniform ivec2 uOrigin;     // image position of texel (0, 0) of every texture of this region
uniform ivec2 uImage;      // image size: taps clamp to it
uniform ivec2 uSize;       // region size: the textures' valid texels
out vec4 outColor;

ivec2 tap(ivec2 p) {
    return clamp(clamp(uOrigin + p, ivec2(0), uImage - 1) - uOrigin, ivec2(0), uSize - 1);
}

const float H[5] = float[5](0.0625, 0.25, 0.375, 0.25, 0.0625);
""" + Shaders.Q16

    /** One axis of a luminance level, horizontal (`DetailOp.lumLevel`, first loop): writes the smoothed `L` (R16F). */
    const val LUM_H_FRAG: String = HEADER + """
uniform int uStep;
uniform float uEdge;       // 1/(2 sigma_e^2)

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c = texelFetch(uSrc, p, 0).x;
    float sum = 0.0;
    float ws = 0.0;
    for (int k = 0; k < 5; k++) {
        float q = texelFetch(uSrc, tap(p + ivec2((k - 2) * uStep, 0)), 0).x;
        float dl = q - c;
        float w = H[k] * exp(-dl * dl * uEdge);
        sum += w * q;
        ws += w;
    }
    outColor = vec4(q16(sum / ws), 0.0, 0.0, 1.0);
}
"""

    /**
     * The vertical axis of a luminance level and its shrinkage (`DetailOp.lumLevel`, second loop): reads the
     * horizontal result ([uSrc]) and the level's input `(L_i, acc)` ([uPrev]; the OkLab input on the first level, whose
     * accumulator is 0), writes `(L_i+1, acc)` (RG16F).
     */
    const val LUM_V_FRAG: String = HEADER + """
uniform sampler2D uPrev;
uniform int uStep;
uniform float uEdge;
uniform float uTau;
uniform int uFirst;        // 1 = uPrev is the OkLab input (no accumulator yet)

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c = texelFetch(uSrc, p, 0).x;
    float sum = 0.0;
    float ws = 0.0;
    for (int k = 0; k < 5; k++) {
        float q = texelFetch(uSrc, tap(p + ivec2(0, (k - 2) * uStep)), 0).x;
        float dl = q - c;
        float w = H[k] * exp(-dl * dl * uEdge);
        sum += w * q;
        ws += w;
    }
    vec4 prev = texelFetch(uPrev, p, 0);
    float acc = uFirst == 1 ? 0.0 : prev.y;
    // The detail against the stored smooth value: the levels telescope exactly (docs/adr/0012).
    float l1 = q16(sum / ws);
    float d = prev.x - l1;
    float keep = d * d / (d * d + uTau * uTau);
    outColor = vec4(l1, q16(acc + d * keep * keep), 0.0, 1.0);
}
"""

    /**
     * The colour path's 2x2 binning (`DetailOp.Chroma.run`, first loop): half-resolution texel `uHalfOrigin + p` is the
     * mean of image pixels `2J + (0..1, 0..1)`, clamped to the image and to the full-resolution region.
     */
    const val DOWN_FRAG: String = HEADER + """
uniform ivec2 uHalfOrigin;

void main() {
    ivec2 j = uHalfOrigin + ivec2(gl_FragCoord.xy);
    vec3 s = vec3(0.0);
    for (int k = 0; k < 4; k++) {
        ivec2 q = clamp(clamp(2 * j + ivec2(k & 1, k >> 1), ivec2(0), uImage - 1) - uOrigin, ivec2(0), uSize - 1);
        s += texelFetch(uSrc, q, 0).xyz;
    }
    outColor = vec4(q16(s * 0.25), 1.0);
}
"""

    /**
     * One colour level on the half-resolution copy (`DetailOp.Chroma.level`); here `uOrigin`, `uImage` and `uSize`
     * are the half-resolution region, image and size.
     */
    const val CHROMA_FRAG: String = HEADER + """
uniform int uStep;
uniform vec3 uChroma;      // x = range 1/(2 s^2), y = mix, z = luminance guard 1/(2 s^2)

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uSrc, p, 0);
    float sumA = 0.0;
    float sumB = 0.0;
    float wC = 0.0;
    for (int j = 0; j < 5; j++) {
        float hy = H[j];
        for (int i = 0; i < 5; i++) {
            float hw = H[i] * hy;
            vec4 q = texelFetch(uSrc, tap(p + ivec2(i - 2, j - 2) * uStep), 0);
            float dl = q.x - c.x;
            float da = q.y - c.y;
            float db = q.z - c.z;
            float wc = hw * exp(-(da * da + db * db) * uChroma.x - dl * dl * uChroma.z);
            sumA += wc * q.y;
            sumB += wc * q.z;
            wC += wc;
        }
    }
    outColor = vec4(c.x, q16(c.y + (sumA / wC - c.y) * uChroma.y), q16(c.z + (sumB / wC - c.z) * uChroma.y), 1.0);
}
"""

    /**
     * One axis of the two blurs of the denoised `L` (`DetailOp.blur`): x = sharpening's Gaussian, y = Effects'
     * texture's cross-bilateral Gaussian. `uMode` 0 reads `L + acc` (luminance levels ran), 1 reads the OkLab input's
     * `L`, 2 the first axis' pair.
     */
    const val BLUR_FRAG: String = HEADER + """
uniform ivec2 uDir;
uniform int uRadius;
uniform float uWs[10];
uniform float uWt[10];
uniform float uRange;      // texture's range weight 1/(2 s^2)
uniform int uMode;

vec2 value(ivec2 p) {
    vec4 v = texelFetch(uSrc, tap(p), 0);
    return uMode == 0 ? vec2(v.x + v.y) : (uMode == 1 ? vec2(v.x) : v.xy);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 c = uMode == 2 ? texelFetch(uSrc, p, 0).xy : value(p);
    float s0 = uWs[0] * c.x;
    float s1 = uWt[0] * c.y;
    float n1 = uWt[0];
    for (int k = 1; k <= uRadius; k++) {
        vec2 lo = value(p - k * uDir);
        vec2 hi = value(p + k * uDir);
        s0 += uWs[k] * (lo.x + hi.x);
        float wl = uWt[k] * exp(-(lo.y - c.y) * (lo.y - c.y) * uRange);
        float wh = uWt[k] * exp(-(hi.y - c.y) * (hi.y - c.y) * uRange);
        s1 += wl * lo.y + wh * hi.y;
        n1 += wl + wh;
    }
    outColor = vec4(s0, n1 > 0.0 ? s1 / n1 : c.y, 0.0, 1.0);
}
"""

    /** [FINAL_FRAG] specialised for [flags] (the `FLAG_` bits are compile-time constants of the variant). */
    fun finalFrag(flags: Int): String = FINAL_FRAG.replace("FLAGS_VALUE", flags.toString())

    /** Flags of [FINAL_FRAG]'s `uFlags`. */
    const val FLAG_LUM: Int = 1
    const val FLAG_CHROMA: Int = 2
    const val FLAG_SHARPEN: Int = 4
    const val FLAG_SHOW_MASK: Int = 8
    const val FLAG_TEXTURE: Int = 16
    const val FLAG_CLARITY: Int = 32
    const val FLAG_DEHAZE: Int = 64

    /**
     * The final pass (`DetailOp.render`'s pixel loop): sharpening (high-pass shaping, edge mask), texture, clarity, the
     * colour from the half-resolution path, back to linear Rec.2020, dehaze; with the mask flag the sharpening mask as
     * grey (the masking overlay).
     */
    private val FINAL_FRAG: String = HEADER + ColourShaders.OKLAB + EffectsShaders.APPLY + """
uniform sampler2D uLum;        // (L, acc) of the last luminance level
uniform sampler2D uBlur;       // (G_sharpen, G_texture)
uniform sampler2D uHalfS;      // colour levels' result, half resolution (L, a, b)
uniform sampler2D uHalfD;      // the binned copy (L, a, b)
const int uFlags = FLAGS_VALUE;
uniform vec4 uSharp;           // x = gain, y = coring^2, z = 1 / halo limit, w = gradient scale
uniform vec2 uMask;            // x = ramp start, y = ramp end (0 = no mask)
uniform vec2 uTexture;         // x = gain, y = edge^2
uniform ivec2 uHalfOrigin;     // half-resolution region origin
uniform ivec2 uHalfImage;      // half-resolution image size
uniform ivec2 uHalfSize;       // half-resolution region size
uniform float uFineRange;

float blurAt(ivec2 p) {
    return texelFetch(uBlur, tap(p), 0).x;
}

float sharpenShape(float h) {
    float h2 = h * h;
    float cored = uSharp.y > 0.0 ? h * (h2 / (h2 + uSharp.y)) : h;
    return cored / (1.0 + abs(cored) * uSharp.z);
}

ivec2 halfTap(ivec2 j) {
    return clamp(clamp(j, ivec2(0), uHalfImage - 1) - uHalfOrigin, ivec2(0), uHalfSize - 1);
}

// DetailOp.Chroma.up: bilinear (a, b) of a half-resolution texture at image pixel q, explicit weights.
vec2 upChroma(sampler2D s, ivec2 q) {
    vec2 uv = (vec2(q) + 0.5) * 0.5 - 0.5;
    vec2 f0 = floor(uv);
    vec2 fr = uv - f0;
    ivec2 i0 = ivec2(f0);
    vec2 a = texelFetch(s, halfTap(i0), 0).yz;
    vec2 b = texelFetch(s, halfTap(i0 + ivec2(1, 0)), 0).yz;
    vec2 d = texelFetch(s, halfTap(i0 + ivec2(0, 1)), 0).yz;
    vec2 e = texelFetch(s, halfTap(i0 + ivec2(1, 1)), 0).yz;
    vec2 top = a + (b - a) * fr.x;
    vec2 bottom = d + (e - d) * fr.x;
    return top + (bottom - top) * fr.y;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 c = texelFetch(uSrc, p, 0);
    float lnr = c.x;
    if ((uFlags & $FLAG_LUM) != 0) {
        vec2 lum = texelFetch(uLum, p, 0).xy;
        lnr = lum.x + lum.y;
    }
    float l = lnr;
    float mask = 1.0;
    bool showMask = (uFlags & $FLAG_SHOW_MASK) != 0;
    if ((uFlags & $FLAG_SHARPEN) != 0 || showMask) {
        float g = texelFetch(uBlur, p, 0).x;
        float h = l - g;
        if (uMask.y > 0.0) {
            float gx = blurAt(p + ivec2(1, 0)) - blurAt(p - ivec2(1, 0));
            float gy = blurAt(p + ivec2(0, 1)) - blurAt(p - ivec2(0, 1));
            float grad = 0.5 * sqrt(gx * gx + gy * gy) * uSharp.w;
            float t = clamp((grad - uMask.x) / (uMask.y - uMask.x), 0.0, 1.0);
            mask = t * t * (3.0 - 2.0 * t);
        }
        if ((uFlags & $FLAG_SHARPEN) != 0) {
            l += uSharp.x * sharpenShape(h) * mask;
        }
    }
    if (showMask) {
        outColor = vec4(fromOkLab(vec3(mask, 0.0, 0.0)), 1.0);
        return;
    }
    if ((uFlags & $FLAG_TEXTURE) != 0) {
        float t = lnr - texelFetch(uBlur, p, 0).y;
        l += uTexture.x * t * (uTexture.y / (uTexture.y + t * t));
    }
    vec4 coef = vec4(0.0);
    bool grid = (uFlags & ($FLAG_CLARITY | $FLAG_DEHAZE)) != 0;
    if (grid) {
        coef = effectsCoef(p + uOrigin, lnr);
        if ((uFlags & $FLAG_CLARITY) != 0) {
            l = clarity(l, coef.x * lnr + coef.y);
        }
    }
    vec2 ab = c.yz;
    if ((uFlags & $FLAG_CHROMA) != 0) {
        ivec2 q = p + uOrigin;
        vec2 s = upChroma(uHalfS, q);
        vec2 r = c.yz - upChroma(uHalfD, q);
        float edge = 1.0 - exp(-(r.x * r.x + r.y * r.y) * uFineRange);
        ab = s + r * (edge * edge);
    }
    vec3 rgb = fromOkLab(vec3(l, ab));
    if ((uFlags & $FLAG_DEHAZE) != 0) {
        rgb = dehaze(rgb, coef.z * lnr + coef.w);
    }
    outColor = vec4(rgb, 1.0);
}
"""
}
