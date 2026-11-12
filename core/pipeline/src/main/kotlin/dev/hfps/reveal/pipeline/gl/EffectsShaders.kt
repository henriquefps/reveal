package dev.hfps.reveal.pipeline.gl

import dev.hfps.reveal.pipeline.cpu.EffectsGrid
import dev.hfps.reveal.pipeline.cpu.EffectsParams
import dev.hfps.reveal.pipeline.cpu.PostCropOp
import dev.hfps.reveal.pipeline.cpu.PostParams
import dev.hfps.reveal.pipeline.gl.ColourShaders.glslFloat

/**
 * GLSL of the Effects group (docs/adr/0014), `cpu/EffectsOp.kt` line for line: the low-resolution grid passes of
 * clarity and dehaze ([EffectsPasses]), their per-pixel application (in `DetailShaders.FINAL_FRAG`), and the post-crop
 * vignette and grain (in the on-screen and export output passes). Constants are the CPU's own floats.
 */
internal object EffectsShaders {
    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    /**
     * Clarity and dehaze per pixel (`EffectsGrid.sample`, `EffectsParams.clarity`, `.dehaze`); needs `fromOkLab`.
     * Samplers `uCells` (cell means), `uCoef` (coefficients), `uAir` (1x1, `(dark, L, a, b)` of the atmospheric light).
     */
    val APPLY: String = """
uniform sampler2D uCells;
uniform sampler2D uCoef;
uniform sampler2D uAir;        // 1 x 1, the atmospheric light's (sum L, sum a, sum b, cells)
uniform ivec3 uEffectsGrid;    // x = cell (px), y = grid width, z = grid height
uniform vec3 uEffects;         // x = clarity gain, y = dehaze omega, z = dehaze beta

vec4 effectsCoef(ivec2 ip, float l) {
    ivec2 size = uEffectsGrid.yz;
    vec2 uv = min(max((vec2(ip) + 0.5) / float(uEffectsGrid.x) - 0.5, vec2(0.0)), vec2(size - 1));
    ivec2 p0 = ivec2(floor(uv));
    ivec2 p1 = min(p0 + 1, size - 1);
    vec2 fr = uv - vec2(p0);
    float sw = 0.0;
    vec4 s = vec4(0.0);
    for (int k = 0; k < 4; k++) {
        bool right = k == 1 || k == 3;
        bool down = k >= 2;
        ivec2 q = ivec2(right ? p1.x : p0.x, down ? p1.y : p0.y);
        float bx = right ? fr.x : 1.0 - fr.x;
        float by = down ? fr.y : 1.0 - fr.y;
        float d = l - texelFetch(uCells, q, 0).x;
        float range = max(exp(-(d * d) * ${glslFloat(EffectsGrid.RANGE_SCALE)}), ${glslFloat(EffectsGrid.RANGE_FLOOR)});
        float w = bx * by * range;
        sw += w;
        s += w * texelFetch(uCoef, q, 0);
    }
    return s / sw;
}

float clarityWith(float l, float b, float gain) {
    float w = min(max(4.0 * b * (1.0 - b), 0.0), 1.0);
    float d = l - b;
    return l + gain * w * d * (${glslFloat(EffectsParams.CLARITY_EDGE2)} / (${glslFloat(EffectsParams.CLARITY_EDGE2)} + d * d));
}

float clarity(float l, float b) {
    return clarityWith(l, b, uEffects.x);
}

// EffectsParams.dehaze with the strengths omega (positive dehaze) and beta (negative): masks pass per-pixel totals.
vec3 dehazeWith(vec3 c, float hRaw, float omega, float beta) {
    float h = min(max(hRaw, 0.0), 1.0);
    vec4 airSum = texelFetch(uAir, ivec2(0), 0);
    vec3 air = max(fromOkLab(airSum.xyz / airSum.w), vec3(${glslFloat(EffectsGrid.AIR_MIN)}));
    if (omega > 0.0) {
        float t = max(1.0 - omega * h, ${glslFloat(EffectsParams.DEHAZE_T0)});
        return (c - air) / t + air;
    }
    float f = beta * (${glslFloat(EffectsParams.HAZE_BASE)} + (1.0 - ${glslFloat(EffectsParams.HAZE_BASE)}) * h);
    return c + (air - c) * f;
}

vec3 dehaze(vec3 c, float hRaw) {
    return dehazeWith(c, hRaw, uEffects.y, uEffects.z);
}
"""

    /**
     * Grid pass 1 (`EffectsGrid.build`, a row segment): per cell column and image row, the sums of `L, L^2, a, b` and
     * the minima of linear `r, g, b` of the segment, into two targets (MRT).
     */
    val ROWS_FRAG: String = HEADER + ColourShaders.OKLAB + """
uniform sampler2D uSrc;
uniform ivec4 uGrid;    // x = cell, y = image width, z = image height
uniform ivec2 uOrigin;  // image position of the source texture's texel (0, 0)
layout(location = 0) out vec4 outSums;
layout(location = 1) out vec4 outMins;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    int cell = uGrid.x;
    int x1 = min((g.x + 1) * cell, uGrid.y);
    vec4 s = vec4(0.0);
    vec3 m = vec3(3.4028235e38);
    for (int x = g.x * cell; x < x1; x++) {
        vec3 lab = texelFetch(uSrc, ivec2(x, g.y) - uOrigin, 0).xyz;
        s += vec4(lab.x, lab.x * lab.x, lab.y, lab.z);
        m = min(m, fromOkLab(lab));
    }
    outSums = s;
    outMins = vec4(m, 0.0);
}
"""

    /** Grid pass 2: a cell's row segments top to bottom, means and minima (MRT). */
    const val CELLS_FRAG: String = HEADER + """
uniform sampler2D uRows;
uniform sampler2D uRowMins;
uniform ivec4 uGrid;
layout(location = 0) out vec4 outMeans;
layout(location = 1) out vec4 outMins;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    int cell = uGrid.x;
    int x1 = min((g.x + 1) * cell, uGrid.y);
    int y1 = min((g.y + 1) * cell, uGrid.z);
    vec4 s = vec4(0.0);
    vec3 m = vec3(3.4028235e38);
    for (int y = g.y * cell; y < y1; y++) {
        s += texelFetch(uRows, ivec2(g.x, y), 0);
        m = min(m, texelFetch(uRowMins, ivec2(g.x, y), 0).rgb);
    }
    outMeans = s / float((x1 - g.x * cell) * (y1 - g.y * cell));
    outMins = vec4(m, min(m.r, min(m.g, m.b)));
}
"""

    /** Grid pass 3: the 3x3 erosion of the minima (dark channel patch). */
    val ERODE_FRAG: String = HEADER + """
uniform sampler2D uSrc;
uniform ivec2 uSize;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 m = vec4(3.4028235e38);
    for (int dy = -${EffectsGrid.ERODE}; dy <= ${EffectsGrid.ERODE}; dy++) {
        for (int dx = -${EffectsGrid.ERODE}; dx <= ${EffectsGrid.ERODE}; dx++) {
            m = min(m, texelFetch(uSrc, clamp(p + ivec2(dx, dy), ivec2(0), uSize - 1), 0));
        }
    }
    outColor = m;
}
"""

    /**
     * Grid pass 4: the atmospheric light (`EffectsGrid.build`, step 3) in 16 x 16 blocks. Mode 0: block maxima of the
     * eroded dark channel (`.w`); mode 1: block maxima of the previous level (`.x`); mode 2: block sums of
     * `(L, a, b, 1)` of the cells whose dark channel is within `AIR_DELTA` of the maximum (`uMax`, 1 x 1); mode 3:
     * block sums of the previous level.
     */
    val AIR_FRAG: String = HEADER + """
const int BLOCK = ${EffectsGrid.AIR_BLOCK};
uniform sampler2D uSrc;      // the eroded minima (modes 0, 2) or the previous level
uniform sampler2D uMeans;    // cell means (mode 2)
uniform sampler2D uMax;      // 1 x 1, x = the largest dark channel (mode 2)
uniform ivec2 uSize;         // the input level's size
uniform int uMode;
out vec4 outColor;

void main() {
    ivec2 b = ivec2(gl_FragCoord.xy);
    ivec2 end = min((b + 1) * BLOCK, uSize);
    float m = -3.4028235e38;
    vec4 s = vec4(0.0);
    float threshold = uMode == 2 ? texelFetch(uMax, ivec2(0), 0).x - ${glslFloat(EffectsGrid.AIR_DELTA)} : 0.0;
    for (int y = b.y * BLOCK; y < end.y; y++) {
        for (int x = b.x * BLOCK; x < end.x; x++) {
            vec4 v = texelFetch(uSrc, ivec2(x, y), 0);
            if (uMode == 0) {
                m = max(m, v.w);
            } else if (uMode == 1) {
                m = max(m, v.x);
            } else if (uMode == 2) {
                if (v.w >= threshold) {
                    vec4 c = texelFetch(uMeans, ivec2(x, y), 0);
                    s += vec4(c.x, c.z, c.w, 1.0);
                }
            } else {
                s += v;
            }
        }
    }
    outColor = uMode < 2 ? vec4(m, 0.0, 0.0, 1.0) : s;
}
"""

    /** Grid pass 5: the guided filter's inputs per cell `(I, I^2, h, I h)`. */
    val MOMENTS_FRAG: String = HEADER + ColourShaders.OKLAB + """
uniform sampler2D uMeans;
uniform sampler2D uEroded;
uniform sampler2D uAir;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 airSum = texelFetch(uAir, ivec2(0), 0);
    vec3 air = max(fromOkLab(airSum.xyz / airSum.w), vec3(${glslFloat(EffectsGrid.AIR_MIN)}));
    vec4 m = texelFetch(uMeans, p, 0);
    vec4 e = texelFetch(uEroded, p, 0);
    float hz = min(e.r / air.r, min(e.g / air.g, e.b / air.b));
    float h = min(max(hz, 0.0), 1.0);
    outColor = vec4(m.x, m.y, h, m.x * h);
}
"""

    /** Grid pass 6: one axis of the box mean; with `uCoef = 1` also the clarity and haze coefficients. */
    val BOX_FRAG: String = HEADER + """
const int RADIUS = ${EffectsGrid.RADIUS};
uniform sampler2D uSrc;
uniform ivec2 uSize;
uniform ivec2 uDir;
uniform int uCoef;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 s = vec4(0.0);
    int n = 0;
    for (int k = -RADIUS; k <= RADIUS; k++) {
        ivec2 q = p + uDir * k;
        if (q.x >= 0 && q.y >= 0 && q.x < uSize.x && q.y < uSize.y) {
            s += texelFetch(uSrc, q, 0);
            n++;
        }
    }
    vec4 o = s / float(n);
    if (uCoef == 1) {
        float mi = o.x;
        float variance = max(o.y - mi * mi, 0.0);
        float aC = variance / (variance + ${glslFloat(EffectsGrid.EPS_CLARITY)});
        float cov = o.w - mi * o.z;
        float aH = cov / (variance + ${glslFloat(EffectsGrid.EPS_HAZE)});
        float bH = o.z - aH * mi;
        o = vec4(aC, mi * (1.0 - aC), aH, bH);
    }
    outColor = o;
}
"""

    /**
     * The post-crop finish (`PostCropOp.pixel`): `postCrop(c, o, size, gk, post0, post1, post2)` at output point `o`
     * (output pixels) of an output of `size`, `gk` = lattice cells per output pixel. `post0` = (vignette, midpoint,
     * feather, highlights), `post1` = (round, exponent, norm, flags: 1 vignette, 2 grain), `post2` = (grain,
     * roughness). Needs `hashLowBias32` (Shaders.OUTPUT_FUNCTIONS).
     */
    val POST: String = """
float postLuma(vec3 c) {
    return 0.2627 * c.r + 0.678 * c.g + 0.0593 * c.b;
}

float postCbrt(float y) {
    return y > 0.0 ? pow(y, 1.0 / 3.0) : 0.0;
}

vec3 lightnessGain(vec3 c, float l, float target, bool neutralLift) {
    float t = max(target, 0.0);
    if (l > 1e-4) {
        float g = t / l;
        return c * (g * g * g);
    }
    return neutralLift ? vec3(t * t * t) : c;
}

float lattice(int x, int y, uint seed) {
    uint h = hashLowBias32(uint(x) + hashLowBias32(uint(y) + seed * 0x9E3779B9u));
    return float((h & 0xFFFFu) + (h >> 16)) * ${glslFloat(1f / 65535f)} - 1.0;
}

float valueNoise(vec2 u, uint seed) {
    vec2 f = floor(u);
    ivec2 i = ivec2(f);
    vec2 t = u - f;
    vec2 s = t * t * (3.0 - 2.0 * t);
    float a = lattice(i.x, i.y, seed);
    float b = lattice(i.x + 1, i.y, seed);
    float d = lattice(i.x, i.y + 1, seed);
    float e = lattice(i.x + 1, i.y + 1, seed);
    float top = a + (b - a) * s.x;
    float bottom = d + (e - d) * s.x;
    return top + (bottom - top) * s.y;
}

float postSstep(float a, float b, float x) {
    float t = clamp((x - a) / (b - a), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

vec3 postCrop(vec3 c, vec2 o, vec2 size, float gk, vec4 post0, vec4 post1, vec4 post2) {
    uint flags = uint(post1.w);
    if ((flags & 1u) != 0u) {
        float hx = size.x * 0.5;
        float hy = size.y * 0.5;
        float rc = sqrt((hx * hx + hy * hy) * 0.5);
        float sx = 1.0 / hx + (1.0 / rc - 1.0 / hx) * post1.x;
        float sy = 1.0 / hy + (1.0 / rc - 1.0 / hy) * post1.x;
        float vx = abs((o.x - hx) * sx);
        float vy = abs((o.y - hy) * sy);
        float d = pow(pow(vx, post1.y) + pow(vy, post1.y), 1.0 / post1.y) * post1.z;
        float m = postSstep(post0.y - post0.z * 0.5, post0.y + post0.z * 0.5, d);
        float l = postCbrt(postLuma(c));
        float s = post0.x < 0.0
            ? l * (1.0 + post0.x * m * (1.0 - post0.w * postSstep(0.5, 0.9, l)))
            : l + max(1.0 - l, 0.0) * post0.x * m;
        c = lightnessGain(c, l, s, post0.x > 0.0);
    }
    if ((flags & 2u) != 0u) {
        uint seed = uint(${PostParams.GRAIN_SEED});
        float n0 = valueNoise(o * gk, seed);
        float n1 = valueNoise(o * gk * 2.0 + ${glslFloat(PostCropOp.OCTAVE_OFFSET)}, seed + 1u);
        float n = (n0 + post2.y * n1) / sqrt(1.0 + post2.y * post2.y);
        float l = postCbrt(postLuma(c));
        float weight = min(max(4.0 * l * (1.0 - l), 0.0), 1.0);
        c = lightnessGain(c, l, l + post2.x * ${glslFloat(PostCropOp.NOISE_SCALE)} * n * weight, false);
    }
    return c;
}
"""
}
