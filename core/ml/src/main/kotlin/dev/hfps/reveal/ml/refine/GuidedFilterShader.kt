package dev.hfps.reveal.ml.refine

/**
 * The full-resolution half of [GuidedRefiner] as a GLES 3.0 fragment pass: one output pixel per fragment, the guide
 * read with `texelFetch` at 1:1 and the RGBA32F coefficients upsampled with explicit fp32 bilinear weights (never the
 * hardware filter: the same taps as `Resample.taps`, so the GPU matches the CPU reference). M4 renders it into the
 * mask texture of a local adjustment; the spike measures it (`GuidedFilterGpuTest`).
 *
 * Uniforms: `uGuide` (RGBA, sRGB display values in 0..1), `uCoef` (RGBA32F: a.rgb, b), `uSize` (output size),
 * `uCoefSize` (coefficient texture size). Output: the refined coverage in `.r`.
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
}
