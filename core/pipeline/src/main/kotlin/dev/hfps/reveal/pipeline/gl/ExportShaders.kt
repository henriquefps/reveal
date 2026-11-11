package dev.hfps.reveal.pipeline.gl

import dev.hfps.reveal.pipeline.cpu.LanczosResampleOp

/**
 * GLSL of the tiled export (docs/adr/0009): the Lanczos-3 resampling passes (`LanczosResampleOp`, line for line) and
 * the export output pass. Everything reads with `texelFetch` at integer positions; positions in the image and the
 * output are the tile's local position plus a uniform origin, so a tile computes exactly what a whole-image render
 * computes for the same pixels.
 */
internal object ExportShaders {
    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    /**
     * One axis of `LanczosResampleOp`. The source texture holds the image region starting at `uSrcOrigin` (on the
     * resampled axis) at texel 0; the target's pixel (0, 0) is output pixel `uDstOrigin`.
     */
    val RESAMPLE_FRAG: String = HEADER + """
const float LOBES = ${LanczosResampleOp.LOBES}.0;
const float PI = 3.14159265358979;

uniform sampler2D uSrc;
uniform ivec2 uDir;        // (1, 0) resamples x, (0, 1) resamples y
uniform int uDstOrigin;    // output index of this target's first pixel along the axis
uniform int uSrcOrigin;    // image index of the source texture's first texel along the axis
uniform int uOffset;       // crop offset on the axis (image pixels)
uniform int uSize;         // image size on the axis (taps clamp to it)
uniform float uScale;      // source pixels per output pixel
out vec4 outColor;

float kernel(float x) {
    if (x == 0.0) {
        return 1.0;
    }
    if (x <= -LOBES || x >= LOBES) {
        return 0.0;
    }
    float px = PI * x;
    return LOBES * sin(px) * sin(px / LOBES) / (px * px);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    int along = uDir.x == 1 ? p.x : p.y;
    int o = uDstOrigin + along;
    float c = float(uOffset) + (float(o) + 0.5) * uScale - 0.5;
    float r = LOBES * uScale;
    int j0 = int(ceil(c - r));
    int j1 = int(floor(c + r));
    vec3 acc = vec3(0.0);
    float sum = 0.0;
    for (int j = j0; j <= j1; j++) {
        float w = kernel((float(j) - c) / uScale);
        sum += w;
        int t = clamp(j, 0, uSize - 1) - uSrcOrigin;
        ivec2 q = uDir.x == 1 ? ivec2(t, p.y) : ivec2(p.x, t);
        acc += w * texelFetch(uSrc, q, 0).rgb;
    }
    outColor = vec4(acc / sum, 1.0);
}
"""

    /**
     * Slot 13 for an export tile, `GeometryOp` line for line: target pixel `p` is geometry-output pixel `uDst + p`;
     * its image point `ip = toImage * (o + 0.5)`; outside the image -> black; no straightening -> the texel
     * `floor(ip)`; otherwise Catmull-Rom over 4x4 texels clamped to the image. The source texture holds the developed
     * image region starting at `uSrc` (texel 0), which [dev.hfps.reveal.pipeline.geometry.GeometryMapping.sourceRegion]
     * sized to cover every tap. Writes linear working-space RGB (RGBA32F).
     */
    const val GEOMETRY_FRAG: String = HEADER + """
uniform sampler2D uStage;
uniform ivec2 uSrc;        // image position of the source texture's texel 0
uniform ivec2 uImage;      // image size
uniform ivec2 uDst;        // geometry-output position of this target's pixel (0, 0)
uniform vec3 uGeomX;       // GeometryMapping.toImage row x: image x = dot(xy, o) + z
uniform vec3 uGeomY;       // row y
uniform int uAxis;         // 1 when nothing is straightened (an exact texel copy)
out vec4 outColor;

vec3 fetch(ivec2 q) {
    return texelFetch(uStage, clamp(q, ivec2(0), uImage - 1) - uSrc, 0).rgb;
}

vec4 catmullRomWeights(float t) {
    return vec4(
        ((-0.5 * t + 1.0) * t - 0.5) * t,
        (1.5 * t - 2.5) * t * t + 1.0,
        ((-1.5 * t + 2.0) * t + 0.5) * t,
        (0.5 * t - 0.5) * t * t);
}

void main() {
    vec2 o = vec2(uDst + ivec2(gl_FragCoord.xy)) + 0.5;
    float ix = uGeomX.x * o.x + uGeomX.y * o.y + uGeomX.z;
    float iy = uGeomY.x * o.x + uGeomY.y * o.y + uGeomY.z;
    if (ix < 0.0 || iy < 0.0 || ix > float(uImage.x) || iy > float(uImage.y)) {
        outColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }
    if (uAxis == 1) {
        outColor = vec4(fetch(ivec2(floor(ix), floor(iy))), 1.0);
        return;
    }
    vec2 s = vec2(ix, iy) - 0.5;
    vec2 f = floor(s);
    vec4 wx = catmullRomWeights(s.x - f.x);
    vec4 wy = catmullRomWeights(s.y - f.y);
    ivec2 b = ivec2(f) - 1;
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        vec3 row = vec3(0.0);
        for (int i = 0; i < 4; i++) {
            row += wx[i] * fetch(b + ivec2(i, j));
        }
        sum += wy[j] * row;
    }
    outColor = vec4(sum, 1.0);
}
"""

    /**
     * The output transform of an export tile: the working-space texel 1:1 (`texelFetch`), the output matrix, clamp,
     * sRGB OETF, then either 8-bit quantisation with the dither of the output pixel `uOrigin + p` (the same noise as
     * `OutputTransformOp` and the on-screen pass), or (`uHigh = 1`) the encoded value unquantised for 10/16-bit
     * outputs. Row 0 of the target is the top row of the tile.
     */
    val OUTPUT_FRAG: String = HEADER + """
uniform sampler2D uStage;
uniform mat3 uToOutput;
uniform ivec2 uOrigin;
uniform uvec2 uDither;     // x = seed, y = 1 when dithering is on
uniform int uHigh;
out vec4 outColor;
""" + Shaders.OUTPUT_FUNCTIONS + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uStage, p, 0).rgb;
    c = clamp(uToOutput * c, 0.0, 1.0);
    vec3 e = srgbOetf(c);
    if (uHigh == 1) {
        outColor = vec4(e, 1.0);
        return;
    }
    float n = uDither.y != 0u ? ditherNoise(p + uOrigin, uDither.x) : 0.5;
    outColor = vec4(min(floor(e * 255.0 + n), 255.0) / 255.0, 1.0);
}
"""
}
