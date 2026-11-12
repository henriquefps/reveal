package dev.hfps.reveal.ml.refine

/**
 * [GuidedRefiner] as GLES 3.0 fragment passes (docs/adr/0021 "Edge refinement", "M4/C"), run by [GpuGuidedRefiner].
 * Every read is a `texelFetch` and every weight explicit (never the hardware filter or derivatives), so the GPU
 * matches the CPU reference within float rounding.
 *
 * Reduced resolution (the coefficients, RGBA32F targets): [RESAMPLE_FRAG] (one axis of `Resample.taps`, the taps in a
 * texture), [PRODUCTS_FRAG] (the 13 window statistics' inputs into 4 targets), [boxFrag] (one axis of the clamped box
 * mean, on 4 or 1 textures), [SOLVE_FRAG] (`a = (Σ + eps U)⁻¹ cov(I, p)`, `b`). Full resolution: [APPLY_FRAG].
 *
 * [APPLY_FRAG] uniforms: `uGuide` (RGBA, sRGB display values in 0..1), `uCoef` (RGBA32F: a.rgb, b), `uSize` (output
 * size), `uCoefSize` (coefficient texture size). Output: the refined coverage in `.r`.
 */
object GuidedFilterShader {
    const val VERTEX: String = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    const val APPLY_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uGuide;
uniform highp sampler2D uCoef;
uniform ivec2 uSize;
uniform ivec2 uCoefSize;
out vec4 fragColor;

// Bilinear taps of output index i (size n) in a source of size m, pixel centres aligned, edges clamped.
void taps(int i, int n, int m, out int i0, out int i1, out float f) {
    float s = float(m) / float(n);
    float x = clamp((float(i) + 0.5) * s - 0.5, 0.0, float(m - 1));
    i0 = int(floor(x));
    i1 = min(i0 + 1, m - 1);
    f = x - float(i0);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int x0; int x1; float fx;
    int y0; int y1; float fy;
    taps(p.x, uSize.x, uCoefSize.x, x0, x1, fx);
    taps(p.y, uSize.y, uCoefSize.y, y0, y1, fy);
    vec4 c00 = texelFetch(uCoef, ivec2(x0, y0), 0);
    vec4 c10 = texelFetch(uCoef, ivec2(x1, y0), 0);
    vec4 c01 = texelFetch(uCoef, ivec2(x0, y1), 0);
    vec4 c11 = texelFetch(uCoef, ivec2(x1, y1), 0);
    vec4 c = (c00 * (1.0 - fx) + c10 * fx) * (1.0 - fy) + (c01 * (1.0 - fx) + c11 * fx) * fy;
    vec3 g = texelFetch(uGuide, p, 0).rgb;
    float q = clamp(dot(c.rgb, g) + c.a, 0.0, 1.0);
    fragColor = vec4(q, q, q, 1.0);
}
"""

    /**
     * One axis of an explicit-weight resample (`Resample.interleaved`): output pixel (x, y) is the sum over k <
     * `uTaps` of `weight(k) * src(index(k))` along x (`uAxis` 0) or y (1), where texel (i, k) of `uTapTex` holds
     * (index, weight) of output index i (weight 0 past its last tap). All four channels.
     */
    const val RESAMPLE_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
uniform highp sampler2D uSrc;
uniform highp sampler2D uTapTex;
uniform int uTaps;
uniform int uAxis;
out vec4 fragColor;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int i = uAxis == 0 ? p.x : p.y;
    vec4 sum = vec4(0.0);
    for (int k = 0; k < uTaps; k++) {
        vec2 t = texelFetch(uTapTex, ivec2(i, k), 0).rg;
        ivec2 q = uAxis == 0 ? ivec2(int(t.x), p.y) : ivec2(p.x, int(t.x));
        sum += texelFetch(uSrc, q, 0) * t.y;
    }
    fragColor = sum;
}
"""

    /** The window statistics' inputs: `I.rgb, p` / `I.rgb * p` / `rr, rg, rb, gg` / `gb, bb` (4 targets). */
    const val PRODUCTS_FRAG: String = """#version 300 es
precision highp float;
uniform highp sampler2D uGuideSmall;
uniform highp sampler2D uMaskSmall;
layout(location = 0) out vec4 o0;
layout(location = 1) out vec4 o1;
layout(location = 2) out vec4 o2;
layout(location = 3) out vec4 o3;

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec3 i = texelFetch(uGuideSmall, q, 0).rgb;
    float p = texelFetch(uMaskSmall, q, 0).r;
    o0 = vec4(i, p);
    o1 = vec4(i * p, 0.0);
    o2 = vec4(i.r * i.r, i.r * i.g, i.r * i.b, i.g * i.g);
    o3 = vec4(i.g * i.b, i.b * i.b, 0.0, 0.0);
}
"""

    /**
     * One axis of the box mean over a (2r + 1) window clamped at the borders (the mean of the valid part, like
     * `BoxMean`), over [n] textures `uSrc0..` into [n] targets. Uniforms: `uRadius`, `uAxis`, `uSize`.
     */
    fun boxFrag(n: Int): String {
        val decl = (0 until n).joinToString("\n") {
            "uniform highp sampler2D uSrc$it;\nlayout(location = $it) out vec4 o$it;"
        }
        val zero = (0 until n).joinToString("\n    ") { "vec4 s$it = vec4(0.0);" }
        val add = (0 until n).joinToString("\n        ") { "s$it += texelFetch(uSrc$it, q, 0);" }
        val write = (0 until n).joinToString("\n    ") { "o$it = s$it / c;" }
        return """#version 300 es
precision highp float;
precision highp int;
$decl
uniform int uRadius;
uniform int uAxis;
uniform ivec2 uSize;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int x = uAxis == 0 ? p.x : p.y;
    int m = uAxis == 0 ? uSize.x : uSize.y;
    int lo = max(0, x - uRadius);
    int hi = min(m, x + uRadius + 1);
    $zero
    for (int j = lo; j < hi; j++) {
        ivec2 q = uAxis == 0 ? ivec2(j, p.y) : ivec2(p.x, j);
        $add
    }
    float c = float(hi - lo);
    $write
}
"""
    }

    /** Per window: `a = (Σ + eps U)⁻¹ cov(I, p)` by cofactors, `b = mean(p) - a · mean(I)`. Output (a.rgb, b). */
    const val SOLVE_FRAG: String = """#version 300 es
precision highp float;
uniform highp sampler2D uM0;
uniform highp sampler2D uM1;
uniform highp sampler2D uM2;
uniform highp sampler2D uM3;
uniform float uEps;
out vec4 fragColor;

void main() {
    ivec2 q = ivec2(gl_FragCoord.xy);
    vec4 m0 = texelFetch(uM0, q, 0);
    vec4 m1 = texelFetch(uM1, q, 0);
    vec4 m2 = texelFetch(uM2, q, 0);
    vec4 m3 = texelFetch(uM3, q, 0);
    float r = m0.r;
    float g = m0.g;
    float bl = m0.b;
    float p = m0.a;
    float vrr = m2.r - r * r + uEps;
    float vrg = m2.g - r * g;
    float vrb = m2.b - r * bl;
    float vgg = m2.a - g * g + uEps;
    float vgb = m3.r - g * bl;
    float vbb = m3.g - bl * bl + uEps;
    float cr = m1.r - r * p;
    float cg = m1.g - g * p;
    float cb = m1.b - bl * p;
    float i00 = vgg * vbb - vgb * vgb;
    float i01 = vgb * vrb - vrg * vbb;
    float i02 = vrg * vgb - vgg * vrb;
    float i11 = vrr * vbb - vrb * vrb;
    float i12 = vrb * vrg - vrr * vgb;
    float i22 = vrr * vgg - vrg * vrg;
    float det = vrr * i00 + vrg * i01 + vrb * i02;
    float ar = (i00 * cr + i01 * cg + i02 * cb) / det;
    float ag = (i01 * cr + i11 * cg + i12 * cb) / det;
    float ab = (i02 * cr + i12 * cg + i22 * cb) / det;
    fragColor = vec4(ar, ag, ab, p - ar * r - ag * g - ab * bl);
}
"""
}
