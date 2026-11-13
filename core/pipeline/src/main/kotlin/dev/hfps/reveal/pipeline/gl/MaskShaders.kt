@file:Suppress("MagicNumber")

package dev.hfps.reveal.pipeline.gl

import dev.hfps.reveal.pipeline.cpu.DetailParams
import dev.hfps.reveal.pipeline.cpu.EffectsParams
import dev.hfps.reveal.pipeline.cpu.GlobalToneOp
import dev.hfps.reveal.pipeline.cpu.LocalToneOp
import dev.hfps.reveal.pipeline.cpu.LocalWhiteBalance
import dev.hfps.reveal.pipeline.gl.ColourShaders.glslFloat
import dev.hfps.reveal.pipeline.mask.MaskMath
import dev.hfps.reveal.pipeline.mask.MaskParams
import dev.hfps.reveal.pipeline.mask.MaskPlan

/**
 * GLSL of the masks (docs/adr/0022): the CPU reference line for line (`mask/MaskRaster.kt`, `cpu/LocalAdjustOps.kt`).
 *
 * - [MASKS]: the `Masks` uniform block (binding [BINDING]: the rendered masks' adjustment vectors and the global values
 *   they add to), the coverage array on [COVERAGE_UNIT] and `localSum` (the per-pixel `Σ coverage · adjust`), declared
 *   by the develop pass and Detail's passes.
 * - [TOTALS]: the totals of a pixel (`LocalTotals`).
 * - [DEVELOP]: local white balance (the gain table on [WB_UNIT]) and the local colour step, for `DEVELOP_FRAG`.
 * - [COVERAGE_FRAG]: one coverage layer (four masks) from the packed components, [BRUSH_FRAG]: a brush layer from the
 *   binned dabs.
 */
internal object MaskShaders {
    /** Uniform block binding point of `Masks`. */
    const val BINDING: Int = 6

    /** Texture unit of the coverage array (develop, Detail and output passes). */
    const val COVERAGE_UNIT: Int = 6

    /** Texture unit of the local white balance's gain table (develop pass). */
    const val WB_UNIT: Int = 7

    /** Texture unit of the masks' adjustment table (develop and Detail passes). */
    const val ADJUST_UNIT: Int = 4

    /** Texels per row of the coverage pass's component table: head, five data texels, the AI layer's info. */
    const val SHAPE_TEXELS: Int = 7

    /** Width of the dab and index data textures (texels per row). */
    const val DATA_WIDTH: Int = 2048

    /** Most components one coverage draw evaluates (four masks). */
    const val MAX_SHAPE_COMPONENTS: Int = MaskPlan.SLOTS * MaskPlan.MAX_COMPONENTS

    /** Shared helpers: `MaskMath.sstep` and the stored coverage. */
    val COMMON: String = """
float maskStep(float e0, float e1, float x) {
    if (e0 >= e1) {
        return x < e1 ? 0.0 : 1.0;
    }
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}

float storeCoverage(float v) {
    float c = clamp(v, 0.0, 1.0);
    return c < ${glslFloat(MaskMath.MIN_STORED)} ? 0.0 : q16(c);
}
"""

    /**
     * The `Masks` block (small: never a big array in a uniform block, SwiftShader counts them against the
     * default-uniform limit), the adjustment table on [ADJUST_UNIT] (RGBA32F, texel (k, m) = vec4 `k` of mask `m`'s
     * vector), the coverage array and `localSum`.
     */
    const val MASKS: String = """
layout(std140) uniform Masks {
    ivec4 maskInfo;                   // x = rendered masks, y = MaskParams.needs
    ivec4 maskGrid;                   // coverage layers (MaskPasses.LayerGrid): cell width, cell height, columns
    vec4 maskGlobal[3];               // MaskParams.global (G_ order); [2].zw = sharpening, texture attenuation
};
uniform highp sampler2D uCoverage;    // four masks per layer, layers as cells of one texture
uniform highp sampler2D uMaskAdj;

// LocalField.sum: s = Σ coverage_m · adjust_m in mask order; false when no mask covers p (s is then 0).
bool localSum(ivec2 p, out vec4 s[5]) {
    for (int k = 0; k < 5; k++) {
        s[k] = vec4(0.0);
    }
    bool any = false;
    for (int m = 0; m < maskInfo.x; m++) {
        int layer = m >> 2;
        ivec2 cell = ivec2((layer % maskGrid.z) * maskGrid.x, (layer / maskGrid.z) * maskGrid.y);
        float c = texelFetch(uCoverage, cell + p, 0)[m & 3];
        if (c == 0.0) {
            continue;
        }
        any = true;
        for (int k = 0; k < 5; k++) {
            s[k] += c * texelFetch(uMaskAdj, ivec2(k, m), 0);
        }
    }
    return any;
}
"""

    /** `LocalTotals`: a total (global + local) to the operator's constant. */
    val TOTALS: String = """
float totalUnit(float t) {
    return clamp(t, -100.0, 100.0) / 100.0;
}

float totalTextureGain(float t, float att) {
    float u = totalUnit(t);
    return u * (u > 0.0 ? ${glslFloat(EffectsParams.TEXTURE_BOOST)} : ${glslFloat(EffectsParams.TEXTURE_SMOOTH)}) * att;
}

float totalSharpenGain(float t, float att) {
    return t > 0.0 ? min(t, 150.0) / 100.0 * ${glslFloat(DetailParams.SHARPEN_GAIN)} * att : 0.0;
}

float totalSoften(float t) {
    return t < 0.0 ? min(-t / 100.0, 1.0) : 0.0;
}

float totalNoiseScale(float t) {
    float n = clamp(t, 0.0, 100.0) / 100.0;
    return n * sqrt(n);
}
"""

    /**
     * Local white balance and the local colour step (the develop pass; needs `toOkLab`, `fromOkLab`, VS_ constants).
     */
    val DEVELOP: String = TOTALS + """
uniform highp sampler2D uLocalWb;   // LocalWhiteBalance.table: (temperature node, tint node) -> rgb cone gains

const mat3 LWB_TO_CONE = ${mat3(LocalWhiteBalance.toCone)};
const mat3 LWB_FROM_CONE = ${mat3(LocalWhiteBalance.fromCone)};

// LocalWhiteBalance.apply: c' = A^-1 diag(g(t, n)) A c, g interpolated with explicit weights.
vec3 localWhiteBalance(vec3 c, float t, float n) {
    float u = (clamp(t, -100.0, 100.0) + 100.0) / ${glslFloat(LocalWhiteBalance.STEP)};
    float v = (clamp(n, -100.0, 100.0) + 100.0) / ${glslFloat(LocalWhiteBalance.STEP)};
    int t0 = min(int(floor(u)), ${LocalWhiteBalance.NODES - 2});
    int n0 = min(int(floor(v)), ${LocalWhiteBalance.NODES - 2});
    float fu = u - float(t0);
    float fv = v - float(n0);
    vec3 g00 = texelFetch(uLocalWb, ivec2(n0, t0), 0).rgb;
    vec3 g01 = texelFetch(uLocalWb, ivec2(n0 + 1, t0), 0).rgb;
    vec3 g10 = texelFetch(uLocalWb, ivec2(n0, t0 + 1), 0).rgb;
    vec3 g11 = texelFetch(uLocalWb, ivec2(n0 + 1, t0 + 1), 0).rgb;
    vec3 a = g00 + (g01 - g00) * fv;
    vec3 b = g10 + (g11 - g10) * fv;
    vec3 g = a + (b - a) * fu;
    return LWB_FROM_CONE * ((LWB_TO_CONE * c) * g);
}

// LocalColourOp.apply: local saturation (VibranceSaturationOp.gain, vibrance 0), hue rotation, colour tint.
vec3 localColour(vec3 c, vec4 s[5]) {
    vec3 lab = toOkLab(c);
    float a = lab.y;
    float b = lab.z;
    if (s[2].w != 0.0) {
        float ch = sqrt(a * a + b * b);
        if (ch > 1e-7) {
            float sat = clamp(s[2].w, -100.0, 100.0) / 100.0;
            float q = ch / VS_C_SOFT;
            float k = sat <= 0.0 ? 1.0 + sat : 1.0 + sat / (1.0 + q * q);
            a *= k;
            b *= k;
        }
    }
    if (s[3].x != 0.0) {
        float theta = radians(clamp(s[3].x, -180.0, 180.0));
        float cs = cos(theta);
        float sn = sin(theta);
        float ra = a * cs - b * sn;
        b = a * sn + b * cs;
        a = ra;
    }
    a += lab.x * s[4].x;
    b += lab.x * s[4].y;
    return fromOkLab(vec3(lab.x, a, b));
}

const float LT_HIGHLIGHT_STOPS = ${glslFloat(LocalToneOp.HIGHLIGHT_STOPS.toFloat())};
const float LT_SHADOW_STOPS = ${glslFloat(LocalToneOp.SHADOW_STOPS.toFloat())};
const float GT_CONTRAST_LOG2 = ${glslFloat(GlobalToneOp.CONTRAST_LOG2.toFloat())};
const float GT_WHITES = ${glslFloat(GlobalToneOp.WHITES_RANGE.toFloat())};
const float GT_BLACKS = ${glslFloat(GlobalToneOp.BLACKS_RANGE.toFloat())};
"""

    /**
     * The masked develop pass's body after the scene fetch (the unmasked path is `DEVELOP_FRAG`'s own, untouched):
     * `LocalDevelop.develop` for one pixel.
     */
    const val DEVELOP_MAIN: String = """
vec3 developMasked(vec3 c, ivec2 p) {
    vec4 s[5];
    bool masked = localSum(p, s);
    float l0 = logLuminance(c);
    float shift = 0.0;
    if (masked && (s[0].x != 0.0 || s[0].y != 0.0 || s[0].z != 0.0)) {
        if (s[0].x != 0.0 || s[0].y != 0.0) {
            c = localWhiteBalance(c, s[0].x, s[0].y);
        }
        if (s[0].z != 0.0) {
            c = c * exp2(clamp(s[0].z, -4.0, 4.0));
        }
        shift = logLuminance(c) - l0;
    }
    bool toneLocal = masked && (s[1].x != 0.0 || s[1].y != 0.0);
    if (tone.z != 0.0 || toneLocal) {
        float hs = toneLocal && s[1].x != 0.0 ? totalUnit(maskGlobal[0].y + s[1].x) * LT_HIGHLIGHT_STOPS : tone.x;
        float ss = toneLocal && s[1].y != 0.0 ? totalUnit(maskGlobal[0].z + s[1].y) * LT_SHADOW_STOPS : tone.y;
        c = localToneAt(c, l0, p + uOrigin, hs, ss, shift);
    }
    c = profileLook(c);
    bool toneGlobal = masked && (s[0].w != 0.0 || s[1].z != 0.0 || s[1].w != 0.0);
    if (tone.w != 0.0 || toneGlobal) {
        float g = toneGlobal && s[0].w != 0.0 ? exp2(totalUnit(maskGlobal[0].x + s[0].w) * GT_CONTRAST_LOG2) : tone2.x;
        float wh = toneGlobal && s[1].z != 0.0 ? totalUnit(maskGlobal[0].w + s[1].z) * GT_WHITES : tone2.y;
        float bl = toneGlobal && s[1].w != 0.0 ? totalUnit(maskGlobal[1].x + s[1].w) * GT_BLACKS : tone2.z;
        c = globalToneWith(c, g, wh, bl);
    }
    c = toneCurve(c);
    c = colourStage(c);
    if (masked && (s[2].w != 0.0 || s[3].x != 0.0 || s[4].x != 0.0 || s[4].y != 0.0)) {
        c = localColour(c, s);
    }
    return c;
}
"""

    /**
     * One coverage layer (`MaskRaster.coverage`): the four masks of the layer from their packed components, each
     * combined from 0 and stored as float16 in its channel. Region pixel `p` is image pixel `uOrigin + p`.
     */
    val COVERAGE_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform ivec4 uShapeMasks[4];                  // per slot of the layer: x = first component, y = count
uniform highp sampler2D uShapes;               // row i: head (type, op, invert, layer), data 0..19, AI (ratio x, y, size)
uniform sampler2D uPre;                        // the pre-local image in OkLab (region)
uniform sampler2D uBrush;                      // brush rasters (region), layers as cells: uBrushGrid
uniform sampler2D uAi;                         // AI masks (R32F, from each cell's texel 0), cells: uAiGrid
uniform ivec3 uBrushGrid;                      // cell width, cell height, columns
uniform ivec3 uAiGrid;
uniform ivec2 uLayerOrigin;                    // the coverage layer's cell being drawn
uniform ivec2 uOrigin;
out vec4 outColor;
""" + Shaders.Q16 + COMMON + """
float shape(int i, int k) {
    return texelFetch(uShapes, ivec2(1 + (k >> 2), i), 0)[k & 3];
}

ivec4 shapeHead(int i) {
    return ivec4(texelFetch(uShapes, ivec2(0, i), 0));
}

ivec2 cellOf(ivec3 grid, int layer) {
    return ivec2((layer % grid.z) * grid.x, (layer / grid.z) * grid.y);
}

// MaskRaster.sampleAi: bilinear with explicit weights, taps clamped to the mask.
float sampleAi(int i, int layer, ivec2 q) {
    vec4 info = texelFetch(uShapes, ivec2(${SHAPE_TEXELS - 1}, i), 0);
    vec2 uv = (vec2(q) + 0.5) * info.xy - 0.5;
    vec2 f = floor(uv);
    vec2 t = uv - f;
    ivec2 i0 = ivec2(f);
    ivec2 hi = ivec2(info.zw) - 1;
    ivec2 cell = cellOf(uAiGrid, layer);
    float a = texelFetch(uAi, cell + clamp(i0, ivec2(0), hi), 0).r;
    float b = texelFetch(uAi, cell + clamp(i0 + ivec2(1, 0), ivec2(0), hi), 0).r;
    float d = texelFetch(uAi, cell + clamp(i0 + ivec2(0, 1), ivec2(0), hi), 0).r;
    float e = texelFetch(uAi, cell + clamp(i0 + ivec2(1, 1), ivec2(0), hi), 0).r;
    float top = a + (b - a) * t.x;
    float bottom = d + (e - d) * t.x;
    return top + (bottom - top) * t.y;
}

// MaskRaster.value; false = skipped.
bool componentValue(int i, ivec2 p, vec3 pre, out float v) {
    ivec4 head = shapeHead(i);
    vec2 q = vec2(p + uOrigin) + 0.5;
    if (head.x == ${MaskPlan.TYPE_LINEAR}) {
        float t = (q.x - shape(i, 0)) * shape(i, 2) + (q.y - shape(i, 1)) * shape(i, 3);
        v = 1.0 - maskStep(shape(i, 4), shape(i, 5), t);
    } else if (head.x == ${MaskPlan.TYPE_RADIAL}) {
        float dx = q.x - shape(i, 0);
        float dy = q.y - shape(i, 1);
        float u = (dx * shape(i, 2) + dy * shape(i, 3)) * shape(i, 4);
        float w = (-dx * shape(i, 3) + dy * shape(i, 2)) * shape(i, 5);
        float inside = 1.0 - maskStep(shape(i, 6), 1.0, sqrt(u * u + w * w));
        v = shape(i, 7) != 0.0 ? inside : 1.0 - inside;
    } else if (head.x == ${MaskPlan.TYPE_BRUSH}) {
        v = texelFetch(uBrush, cellOf(uBrushGrid, head.w) + p, 0).r;
    } else if (head.x == ${MaskPlan.TYPE_COLOUR}) {
        float best = 3.4028235e38;
        int n = int(shape(i, 0));
        for (int s = 0; s < n; s++) {
            float dl = pre.x - shape(i, 3 + 3 * s);
            float da = pre.y - shape(i, 4 + 3 * s);
            float db = pre.z - shape(i, 5 + 3 * s);
            best = min(best, ${glslFloat(MaskPlan.COLOUR_L_WEIGHT)} * dl * dl + da * da + db * db);
        }
        v = 1.0 - maskStep(shape(i, 1), shape(i, 2), sqrt(best));
    } else if (head.x == ${MaskPlan.TYPE_LUMA}) {
        float l = clamp(pre.x, 0.0, 1.0);
        float low = shape(i, 4) != 0.0 ? maskStep(shape(i, 0), shape(i, 1), l) : 1.0;
        float high = shape(i, 5) != 0.0 ? 1.0 - maskStep(shape(i, 2), shape(i, 3), l) : 1.0;
        v = low * high;
    } else if (head.x == ${MaskPlan.TYPE_AI}) {
        v = sampleAi(i, head.w, p + uOrigin);
    } else {
        return false;
    }
    v = clamp(v, 0.0, 1.0);
    return true;
}

float maskCoverage(int slot, ivec2 p, vec3 pre) {
    float cov = 0.0;
    int first = uShapeMasks[slot].x;
    int count = uShapeMasks[slot].y;
    for (int j = 0; j < count; j++) {
        int i = first + j;
        float v;
        if (!componentValue(i, p, pre, v)) {
            continue;
        }
        ivec4 head = shapeHead(i);
        if (head.z != 0) {
            v = 1.0 - v;
        }
        int op = head.y;
        cov = op == 0 ? max(cov, v) : (op == 1 ? cov * (1.0 - v) : cov * v);
    }
    return storeCoverage(cov);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) - uLayerOrigin;
    vec3 pre = texelFetch(uPre, p, 0).xyz;
    outColor = vec4(maskCoverage(0, p, pre), maskCoverage(1, p, pre), maskCoverage(2, p, pre),
        maskCoverage(3, p, pre));
}
"""

    /**
     * A brush layer (`MaskRaster.brush`): per pixel the dabs of its bin in order, painting `cov += (1 - cov) a`,
     * erasing `cov *= 1 - a`; stored as float16. Dab `k` is texels `2k`, `2k + 1` of [uDabs] (centre x, y, radius,
     * inner; flow, erase), bins hold (offset, count) into [uIndex].
     */
    val BRUSH_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uDabs;
uniform sampler2D uBins;
uniform sampler2D uIndex;
uniform ivec2 uOrigin;
uniform ivec2 uLayerOrigin;    // the brush layer's cell
out vec4 outColor;
""" + Shaders.Q16 + COMMON + """
ivec2 dataAt(int k) {
    return ivec2(k % $DATA_WIDTH, k / $DATA_WIDTH);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy) - uLayerOrigin;
    vec2 bin = texelFetch(uBins, p / ${dev.hfps.reveal.pipeline.mask.DabBins.BIN}, 0).xy;
    int offset = int(bin.x);
    int count = int(bin.y);
    vec2 q = vec2(p + uOrigin) + 0.5;
    float cov = 0.0;
    for (int j = 0; j < count; j++) {
        int k = int(texelFetch(uIndex, dataAt(offset + j), 0).r);
        vec4 d0 = texelFetch(uDabs, dataAt(2 * k), 0);
        vec4 d1 = texelFetch(uDabs, dataAt(2 * k + 1), 0);
        vec2 dq = q - d0.xy;
        float r = sqrt(dq.x * dq.x + dq.y * dq.y) / d0.z;
        float a = d1.x * (1.0 - maskStep(d0.w, 1.0, r));
        if (a <= 0.0) {
            continue;
        }
        cov = d1.y != 0.0 ? cov * (1.0 - a) : cov + (1.0 - cov) * a;
    }
    outColor = vec4(storeCoverage(cov), 0.0, 0.0, 1.0);
}
"""

    private fun mat3(rowMajor: FloatArray): String {
        val cols = (0 until 3).flatMap { c -> (0 until 3).map { r -> rowMajor[r * 3 + c] } }
        return "mat3(" + cols.joinToString(", ") { glslFloat(it) } + ")"
    }
}
