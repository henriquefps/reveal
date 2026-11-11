package dev.hfps.reveal.pipeline.gl

import dev.hfps.reveal.pipeline.cpu.GlobalToneOp
import dev.hfps.reveal.pipeline.cpu.HighlightReconstructionOp
import dev.hfps.reveal.pipeline.cpu.LocalToneOp
import java.util.Locale

/**
 * GLSL of the Light group (docs/adr/0007): highlight reconstruction, highlights/shadows (guided filter on a low-res
 * grid) and contrast/whites/blacks. Every function mirrors `cpu/HighlightReconstruction.kt` and `cpu/Tone.kt` line
 * for line, including the order of sums; constants come from the Kotlin objects so the two cannot drift.
 *
 * Neighbourhoods are read with `texelFetch` only, and bilinear upsampling is written out with fp32 weights (hardware
 * bilinear weights are 8-bit on some GPUs, and derivative-selected LODs differ per driver: docs/adr/0002).
 * Low-res textures are RGBA32F (renderable through `EXT_color_buffer_float`, which the renderer already requires).
 */
internal object LightShaders {
    private fun f(v: Float): String = String.format(Locale.ROOT, "%.9e", v)

    private fun f(v: Double): String = f(v.toFloat())

    /** The `Light` uniform block (std140, binding 2) and helpers shared by the scene and develop stages. */
    val COMMON: String = """
layout(std140) uniform Light {
    vec4 grid;     // x = cell (px), y = grid width, z = grid height, w = 1 when highlight reconstruction runs
    vec4 hlClip;   // rgb = balanced clip level per channel, w = the largest
    vec4 tone;     // x = highlight stops, y = shadow stops, z = 1 when local tone runs, w = 1 when global tone runs
    vec4 tone2;    // x = contrast gamma, y = whites shift, z = blacks shift, w = pivot lightness
};

float sstep(float a, float b, float x) {
    float t = clamp((x - a) / (b - a), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

// LowResGrid.bilinear: cell centres at integers, clamped to the edge cells, fp32 weights.
$BILINEAR_ONLY
vec2 toGrid(ivec2 p) {
    return (vec2(p) + 0.5) / grid.x - 0.5;
}

float luma(vec3 c) {
    return 0.2627 * c.r + 0.6780 * c.g + 0.0593 * c.b;
}
"""

    private val CLIP_AMOUNT: String = """
const float T0 = ${f(HighlightReconstructionOp.T0)};
const float T1 = ${f(HighlightReconstructionOp.T1)};

float clipAmount(float b, float c) {
    return min(max((b / c - T0) / (T1 - T0), 0.0), 1.0);
}
"""

    /** HighlightReconstructionOp.apply for one balanced pixel; needs `uChroma` (the filled chroma grid). */
    val RECONSTRUCT: String = CLIP_AMOUNT + """
const float RMAX = ${f(HighlightReconstructionOp.RMAX)};

vec3 reconstructHighlights(vec3 b, ivec2 p) {
    vec3 t = vec3(clipAmount(b.r, hlClip.r), clipAmount(b.g, hlClip.g), clipAmount(b.b, hlClip.b));
    if (t.r == 0.0 && t.g == 0.0 && t.b == 0.0) {
        return b;
    }
    vec3 c = bilinearFetch(uChroma, ivec2(grid.yz), toGrid(p)).rgb;
    vec3 u = 1.0 - t;
    float num = u.r * max(b.r, 0.0) + u.g * max(b.g, 0.0) + u.b * max(b.b, 0.0);
    float den = u.r * c.r + u.g * c.g + u.b * c.b;
    if (den > 1e-6) {
        float k = num / den;
        vec3 r = min(k * c, RMAX * hlClip.rgb);
        b += t * max(r - b, 0.0);
    }
    float full = max(hlClip.r / max(c.r, 1e-6), max(hlClip.g / max(c.g, 1e-6), hlClip.b / max(c.b, 1e-6)));
    float n = max(b.r, max(b.g, b.b));
    n = max(n, min(full * c.r, RMAX * hlClip.r));
    n = max(n, min(full * c.g, RMAX * hlClip.g));
    n = max(n, min(full * c.b, RMAX * hlClip.b));
    float fl = min(t.r, min(t.g, t.b));
    return b + (vec3(n) - b) * fl;
}
"""

    private val LOG_LUMINANCE: String = """
float logLuminance(vec3 c) {
    return log2(max(luma(c), ${f(LocalToneOp.MIN_Y)}));
}
"""

    /** LocalToneOp.apply and GlobalToneOp.apply for one pixel; local needs `uCoef` and `uMoments` (the guide). */
    val TONE: String = LOG_LUMINANCE + """
float highlightWeight(float b) {
    float x = b - ${f(LocalToneOp.H1)};
    return sstep(${f(LocalToneOp.H0)}, ${f(LocalToneOp.H1)}, b) +
        ${f(LocalToneOp.K)} * ((sqrt(x * x + ${f(LocalToneOp.D * LocalToneOp.D)}) + x) * 0.5);
}

float shadowWeight(float b) {
    return sstep(${f(LocalToneOp.S0)}, ${f(LocalToneOp.S1)}, b) *
        (1.0 - sstep(${f(LocalToneOp.S2)}, ${f(LocalToneOp.S3)}, b));
}

// LocalToneOp.sampleCoef: bilinear weights times a range weight on each cell's mean l.
vec2 sampleCoef(ivec2 p, float l) {
    ivec2 size = ivec2(grid.yz);
    vec2 uv = min(max(toGrid(p), vec2(0.0)), vec2(size - 1));
    ivec2 p0 = ivec2(floor(uv));
    ivec2 p1 = min(p0 + 1, size - 1);
    vec2 fr = uv - vec2(p0);
    float sw = 0.0;
    float sa = 0.0;
    float sb = 0.0;
    for (int k = 0; k < 4; k++) {
        bool right = k == 1 || k == 3;
        bool down = k >= 2;
        ivec2 q = ivec2(right ? p1.x : p0.x, down ? p1.y : p0.y);
        float bx = right ? fr.x : 1.0 - fr.x;
        float by = down ? fr.y : 1.0 - fr.y;
        float d = l - texelFetch(uMoments, q, 0).r;
        float range = max(exp(-(d * d) * ${f(LocalToneOp.RANGE_SCALE)}), ${f(LocalToneOp.RANGE_FLOOR)});
        float w = bx * by * range;
        vec2 ab = texelFetch(uCoef, q, 0).rg;
        sw += w;
        sa += w * ab.x;
        sb += w * ab.y;
    }
    return vec2(sa / sw, sb / sw);
}

vec3 localTone(vec3 c, ivec2 p) {
    float l = logLuminance(c);
    vec2 ab = sampleCoef(p, l);
    float base = ab.x * l + ab.y;
    float b1 = base + tone.y * shadowWeight(base);
    float b2 = b1 + tone.x * highlightWeight(b1);
    return c * exp2(b2 - base);
}

float hermite(float t, float y0, float y1, float m0, float m1) {
    float t2 = t * t;
    float t3 = t2 * t;
    return (2.0 * t3 - 3.0 * t2 + 1.0) * y0 + (t3 - 2.0 * t2 + t) * m0 + (-2.0 * t3 + 3.0 * t2) * y1 + (t3 - t2) * m1;
}

float contrastCurve(float p, float g) {
    float p0 = tone2.w;
    float e = 1.0 / g;
    if (p <= 0.0 || p >= 1.0) {
        return p;
    }
    if (p <= p0) {
        return hermite(p / p0, 0.0, p0, e * p0, g * p0);
    }
    return hermite((p - p0) / (1.0 - p0), p0, 1.0, g * (1.0 - p0), e * (1.0 - p0));
}

vec3 globalTone(vec3 c) {
    float yc = max(luma(c), 0.0);
    float p = pow(yc, ${f(1f / 3f)});
    float p1 = contrastCurve(p, tone2.x);
    float p2 = p1 + tone2.y * sstep(tone2.w, 1.0, p1);
    float q = max(p2 + tone2.z * (1.0 - sstep(0.0, tone2.w, p2)), 0.0);
    float y2 = q * q * q;
    float gain = min(y2 / max(yc, ${f(GlobalToneOp.YMIN)}), ${f(GlobalToneOp.GMAX)});
    float add = max(y2 - yc * gain, 0.0);
    return c * gain + add;
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    /**
     * HighlightReconstructionOp.stats, first pass: one fragment per (grid column, image row), summing that row
     * segment of the cell left to right; [CELL_MEAN_FRAG] then sums the segments of each cell.
     */
    val RECON_ROWS_FRAG: String = HEADER + CLIP_AMOUNT + """
uniform sampler2D uSource;
uniform vec4 uMul;      // rgb = white-balance multipliers
uniform vec4 uClip;     // rgb = balanced clip levels
uniform ivec4 uGrid;    // x = cell, y = image width, z = image height
out vec4 outColor;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    int cell = uGrid.x;
    int x1 = min((g.x + 1) * cell, uGrid.y);
    vec4 acc = vec4(0.0);
    for (int x = g.x * cell; x < x1; x++) {
        vec3 b = texelFetch(uSource, ivec2(x, g.y), 0).rgb * uMul.rgb;
        float tr = clipAmount(b.r, uClip.r);
        float tg = clipAmount(b.g, uClip.g);
        float tb = clipAmount(b.b, uClip.b);
        float yr = max(b.r / uClip.r, max(b.g / uClip.g, b.b / uClip.b));
        vec3 bp = max(b, 0.0);
        float sum = bp.r + bp.g + bp.b;
        if (sum <= 1e-6) {
            continue;
        }
        float w = (1.0 - max(tr, max(tg, tb))) * yr * yr;
        acc += vec4(w * (bp.r / sum), w * (bp.g / sum), w * (bp.b / sum), w);
    }
    outColor = acc;
}
"""

    /** LowResGrid.reduceCells, second pass: sums a cell's row segments top to bottom, divides by its pixel count. */
    const val CELL_MEAN_FRAG: String = HEADER + """
uniform sampler2D uRows;
uniform ivec4 uGrid;    // x = cell, y = image width, z = image height
out vec4 outColor;

void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    int cell = uGrid.x;
    int x1 = min((g.x + 1) * cell, uGrid.y);
    int y1 = min((g.y + 1) * cell, uGrid.z);
    vec4 acc = vec4(0.0);
    for (int y = g.y * cell; y < y1; y++) {
        acc += texelFetch(uRows, ivec2(g.x, y), 0);
    }
    outColor = acc / float((x1 - g.x * cell) * (y1 - g.y * cell));
}
"""

    /** HighlightReconstructionOp.push: mean of the (up to four) children. */
    const val PUSH_FRAG: String = HEADER + """
uniform sampler2D uLevel;
uniform ivec2 uSize;    // child level size
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec4 s = vec4(0.0);
    int n = 0;
    for (int dy = 0; dy <= 1; dy++) {
        for (int dx = 0; dx <= 1; dx++) {
            ivec2 c = 2 * p + ivec2(dx, dy);
            if (c.x < uSize.x && c.y < uSize.y) {
                s += texelFetch(uLevel, c, 0);
                n++;
            }
        }
    }
    outColor = s / float(n);
}
"""

    /** HighlightReconstructionOp.pull: own chroma by confidence, the rest from the upsampled parent (or neutral). */
    val PULL_FRAG: String = HEADER + """
const float NEUTRAL = ${f(HighlightReconstructionOp.NEUTRAL)};
const float CONFIDENCE = ${f(HighlightReconstructionOp.CONFIDENCE)};

uniform sampler2D uLevel;
uniform sampler2D uParent;
uniform ivec2 uParentSize;
uniform int uTop;
out vec4 outColor;

""" + BILINEAR_ONLY + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 up = uTop == 1 ? vec3(NEUTRAL) : bilinearFetch(uParent, uParentSize, (vec2(p) + 0.5) / 2.0 - 0.5).rgb;
    vec4 l = texelFetch(uLevel, p, 0);
    float lambda = min(l.a / CONFIDENCE, 1.0);
    vec3 own = l.a > 0.0 ? l.rgb / l.a : vec3(NEUTRAL);
    outColor = vec4(up + (own - up) * lambda, 1.0);
}
"""

    /** LocalToneOp.moments, first pass: row segments of `l` and `l^2` from the scene-linear texture. */
    val GUIDE_ROWS_FRAG: String = HEADER + """
uniform sampler2D uScene;
uniform ivec4 uGrid;    // x = cell, y = image width, z = image height
out vec4 outColor;

float luma(vec3 c) {
    return 0.2627 * c.r + 0.6780 * c.g + 0.0593 * c.b;
}
""" + LOG_LUMINANCE + """
void main() {
    ivec2 g = ivec2(gl_FragCoord.xy);
    int cell = uGrid.x;
    int x1 = min((g.x + 1) * cell, uGrid.y);
    float s = 0.0;
    float s2 = 0.0;
    for (int x = g.x * cell; x < x1; x++) {
        float l = logLuminance(texelFetch(uScene, ivec2(x, g.y), 0).rgb);
        s += l;
        s2 += l * l;
    }
    outColor = vec4(s, s2, 0.0, 0.0);
}
"""

    /** LocalToneOp.box along one axis; with `uCoef = 1` it also turns (mean, mean of squares) into (a, b). */
    val BOX_FRAG: String = HEADER + """
const int RADIUS = ${LocalToneOp.RADIUS};
const float EPS = ${f(LocalToneOp.EPS)};

uniform sampler2D uSrc;
uniform ivec2 uSize;
uniform ivec2 uDir;
uniform int uCoef;
out vec4 outColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 s = vec2(0.0);
    int n = 0;
    for (int k = -RADIUS; k <= RADIUS; k++) {
        ivec2 q = p + uDir * k;
        if (q.x >= 0 && q.y >= 0 && q.x < uSize.x && q.y < uSize.y) {
            s += texelFetch(uSrc, q, 0).rg;
            n++;
        }
    }
    vec2 m = s / float(n);
    if (uCoef == 1) {
        float variance = max(m.y - m.x * m.x, 0.0);
        float a = variance / (variance + EPS);
        outColor = vec4(a, m.x * (1.0 - a), 0.0, 1.0);
    } else {
        outColor = vec4(m, 0.0, 1.0);
    }
}
"""
}

private const val BILINEAR_ONLY = """
vec4 bilinearFetch(sampler2D s, ivec2 size, vec2 uv) {
    vec2 c = min(max(uv, vec2(0.0)), vec2(size - 1));
    ivec2 p0 = ivec2(floor(c));
    ivec2 p1 = min(p0 + 1, size - 1);
    vec2 fr = c - vec2(p0);
    vec4 a = texelFetch(s, p0, 0);
    vec4 b = texelFetch(s, ivec2(p1.x, p0.y), 0);
    vec4 d = texelFetch(s, ivec2(p0.x, p1.y), 0);
    vec4 e = texelFetch(s, p1, 0);
    vec4 top = a + (b - a) * fr.x;
    vec4 bottom = d + (e - d) * fr.x;
    return top + (bottom - top) * fr.y;
}
"""
