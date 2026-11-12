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

    /**
     * One axis of a luminance level, horizontal (`DetailOp.lumLevel`, first loop): writes the smoothed `L` (R16F).
     * `L_i` is channel [uChannel] of [uSrc] (0: the OkLab input, `(L_i, acc)`; the preview's packed chain otherwise).
     */
    const val LUM_H_FRAG: String = HEADER + """
uniform int uStep;
uniform float uEdge;       // 1/(2 sigma_e^2)
uniform int uChannel;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float c = texelFetch(uSrc, p, 0)[uChannel];
    float sum = 0.0;
    float ws = 0.0;
    for (int k = 0; k < 5; k++) {
        float q = texelFetch(uSrc, tap(p + ivec2((k - 2) * uStep, 0)), 0)[uChannel];
        float dl = q - c;
        float w = H[k] * exp(-dl * dl * uEdge);
        sum += w * q;
        ws += w;
    }
    outColor = vec4(q16(sum / ws), 0.0, 0.0, 1.0);
}
"""

    /** The vertical smoothing of a level (`DetailOp.lumLevel`, second loop) on the horizontal result in [uSrc]. */
    private const val LUM_V_SMOOTH = """
uniform int uStep;
uniform float uEdge;

float smoothV(ivec2 p) {
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
    return q16(sum / ws);
}
"""

    /** The shrinkage of one level's detail, accumulated (`DetailOp.lumLevel`, the end of the second loop). */
    private const val SHRINK = """
float shrinkAdd(float acc, float d, float tau) {
    float keep = d * d / (d * d + tau * tau);
    return q16(acc + d * keep * keep);
}
"""

    /**
     * The vertical axis of a luminance level and its shrinkage (`DetailOp.lumLevel`, second loop): reads the
     * horizontal result ([uSrc]) and the level's input `(L_i, acc)` ([uPrev]; the OkLab input on the first level, whose
     * accumulator is 0), writes `(L_i+1, acc)` (RG16F). Tiles and exports; the preview splits it into [LUM_V_CHAIN_FRAG]
     * and [SHRINK_FRAG].
     */
    const val LUM_V_FRAG: String = HEADER + LUM_V_SMOOTH + SHRINK + """
uniform sampler2D uPrev;
uniform float uTau;
uniform int uFirst;        // 1 = uPrev is the OkLab input (no accumulator yet)

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 prev = texelFetch(uPrev, p, 0);
    float acc = uFirst == 1 ? 0.0 : prev.y;
    // The detail against the stored smooth value: the levels telescope exactly (docs/adr/0012).
    float l1 = smoothV(p);
    outColor = vec4(l1, shrinkAdd(acc, prev.x - l1, uTau), 0.0, 1.0);
}
"""

    /**
     * The vertical axis of a luminance level without the shrinkage: writes `L_i+1` alone, into channel `i % 4` of
     * the preview's packed chain (RGBA16F, the other channels masked off): the smoothing chain depends on the develop
     * output and the noise only, so a drag of the luminance sliders keeps it (docs/adr/0012, "M2/P").
     */
    const val LUM_V_CHAIN_FRAG: String = HEADER + LUM_V_SMOOTH + """
void main() {
    outColor = vec4(smoothV(ivec2(gl_FragCoord.xy)));
}
"""

    /**
     * Every level's shrinkage in one pass over the preview's cached chain (`DetailOp.lumLevel`'s accumulation, level
     * by level in the same order and with the same float16 rounding as [LUM_V_FRAG], so it equals the fused levels
     * value for value): [uSrc] is the OkLab input (`L_0`), [uChain0] holds `L_1 .. L_4` of the levels that run,
     * [uChain1] `L_5`; writes `(L_n, acc)` (RG16F).
     */
    const val SHRINK_FRAG: String = HEADER + SHRINK + """
uniform sampler2D uChain0;
uniform sampler2D uChain1;
uniform int uLevels;
uniform float uTau[5];

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float prev = texelFetch(uSrc, p, 0).x;
    vec4 c0 = texelFetch(uChain0, p, 0);
    float acc = 0.0;
    for (int i = 0; i < 4; i++) {
        if (i >= uLevels) break;
        float l1 = c0[i];
        acc = shrinkAdd(acc, prev - l1, uTau[i]);
        prev = l1;
    }
    if (uLevels > 4) {
        float l1 = texelFetch(uChain1, p, 0).x;
        acc = shrinkAdd(acc, prev - l1, uTau[4]);
        prev = l1;
    }
    outColor = vec4(prev, acc, 0.0, 1.0);
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

    private const val BLUR_UNIFORMS = """
uniform int uRadius;
uniform float uWs[10];
uniform float uWt[10];
uniform float uRange;      // texture's range weight 1/(2 s^2)
"""

    /**
     * One axis of the two blurs of the denoised `L` (`DetailOp.blur`): x = sharpening's Gaussian, y = Effects'
     * texture's cross-bilateral Gaussian. `uMode` 0 reads `L + acc` (luminance levels ran), 1 reads the OkLab input's
     * `L`, 2 the first axis' pair.
     */
    const val BLUR_FRAG: String = HEADER + """
uniform ivec2 uDir;
""" + BLUR_UNIFORMS + """
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
    outColor = vec4(q16(s0), q16(n1 > 0.0 ? s1 / n1 : c.y), 0.0, 1.0);
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

    /** Writes the mean of each 2 x 2 block (the working texture's level 1) at half resolution. */
    const val FLAG_HALF: Int = 128

    /**
     * The final pass (`DetailOp.render`'s pixel loop): sharpening (high-pass shaping, edge mask), texture, clarity, the
     * colour from the half-resolution path, back to linear Rec.2020, dehaze; with the mask flag the sharpening mask as
     * grey (the masking overlay). With [FLAG_HALF] it writes the 2 x 2 means at half resolution (the preview's views at
     * 50 % or less read nothing finer: docs/adr/0012, "M2/P").
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

// upChroma for the four pixels 2J + (0..1, 0..1) of a half-resolution block from one 3 x 3 fetch (the same values:
// pixel 2J uses texels J-1, J with fraction 0.75, pixel 2J+1 texels J, J+1 with fraction 0.25, on each axis).
void upChroma4(sampler2D s, ivec2 j, out vec2 r[4]) {
    vec2 t[9];
    for (int y = 0; y < 3; y++) {
        for (int x = 0; x < 3; x++) {
            t[y * 3 + x] = texelFetch(s, halfTap(j + ivec2(x - 1, y - 1)), 0).yz;
        }
    }
    for (int k = 0; k < 4; k++) {
        int dx = k & 1;
        int dy = k >> 1;
        float fx = dx == 0 ? 0.75 : 0.25;
        float fy = dy == 0 ? 0.75 : 0.25;
        vec2 a = t[dy * 3 + dx];
        vec2 b = t[dy * 3 + dx + 1];
        vec2 d = t[(dy + 1) * 3 + dx];
        vec2 e = t[(dy + 1) * 3 + dx + 1];
        vec2 top = a + (b - a) * fx;
        vec2 bottom = d + (e - d) * fx;
        r[k] = top + (bottom - top) * fy;
    }
}

// The final pixel at p; sUp / dUp are upChroma of the colour levels' result and of the binned copy there.
vec3 finalAt(ivec2 p, vec2 sUp, vec2 dUp) {
    vec4 c = texelFetch(uSrc, p, 0);
    float lnr = c.x;
    if ((uFlags & $FLAG_LUM) != 0) {
        vec2 lum = texelFetch(uLum, p, 0).xy;
        lnr = lum.x + lum.y;
    }
    float l = lnr;
    float mask = 1.0;
    bool showMask = (uFlags & $FLAG_SHOW_MASK) != 0;
    bool blurred = (uFlags & ($FLAG_SHARPEN | $FLAG_SHOW_MASK | $FLAG_TEXTURE)) != 0;
    vec2 g = blurred ? texelFetch(uBlur, p, 0).xy : vec2(0.0);
    if ((uFlags & $FLAG_SHARPEN) != 0 || showMask) {
        float h = l - g.x;
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
        return fromOkLab(vec3(mask, 0.0, 0.0));
    }
    if ((uFlags & $FLAG_TEXTURE) != 0) {
        float t = lnr - g.y;
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
        vec2 s = sUp;
        vec2 r = c.yz - dUp;
        float edge = 1.0 - exp(-(r.x * r.x + r.y * r.y) * uFineRange);
        ab = s + r * (edge * edge);
    }
    vec3 rgb = fromOkLab(vec3(l, ab));
    if ((uFlags & $FLAG_DEHAZE) != 0) {
        rgb = dehaze(rgb, coef.z * lnr + coef.w);
    }
    return rgb;
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    bool chroma = (uFlags & $FLAG_CHROMA) != 0;
    if ((uFlags & $FLAG_HALF) != 0) {
        // Texel J of level 1: the mean of pixels 2J .. 2J + 1, the last row / column repeated at an odd edge (a
        // whole-image region, origin 0: the image pixel 2J is the block's first).
        ivec2 b = 2 * p;
        ivec2 hi = uSize - 1;
        vec2 sUp[4];
        vec2 dUp[4];
        if (chroma) {
            upChroma4(uHalfS, p + uHalfOrigin, sUp);
            upChroma4(uHalfD, p + uHalfOrigin, dUp);
        }
        vec3 sum = vec3(0.0);
        for (int k = 0; k < 4; k++) {
            ivec2 q = b + ivec2(k & 1, k >> 1);
            // At an odd edge the repeated pixel is the previous one, chroma included.
            ivec2 qc = min(q, hi);
            int kc = (qc.x - b.x) + 2 * (qc.y - b.y);
            sum += chroma ? finalAt(qc, sUp[kc], dUp[kc]) : finalAt(qc, vec2(0.0), vec2(0.0));
        }
        outColor = vec4(0.25 * sum, 1.0);
        return;
    }
    vec2 sUp = vec2(0.0);
    vec2 dUp = vec2(0.0);
    if (chroma) {
        ivec2 q = p + uOrigin;
        sUp = upChroma(uHalfS, q);
        dUp = upChroma(uHalfD, q);
    }
    outColor = vec4(finalAt(p, sUp, dUp), 1.0);
}
"""
}
