package dev.hfps.reveal.pipeline.gl

/** GLSL of the editor's full-resolution zoom tiles (docs/adr/0012). */
internal object ZoomShaders {
    /**
     * Writes one atlas slot from a developed tile region: slot texel `s` at level 0 is image pixel `uTile + s`, at
     * level 1 the mean of the four level-0 texels it covers. Image positions are clamped to the image first (the slot's
     * border beyond the image repeats the edge, like `CLAMP_TO_EDGE` on a whole-image texture), then read from the
     * region texture whose texel 0 is image pixel `uRegion`.
     */
    const val COPY_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uSrc;
uniform ivec2 uSlot;       // target texel of the slot's (0, 0) at this level
uniform ivec2 uTile;       // image pixel of the slot's level-0 texel (0, 0)
uniform ivec2 uRegion;     // image pixel of uSrc's texel 0
uniform ivec2 uImage;      // image size
uniform int uLevel;        // 0 or 1
out vec4 outColor;

vec3 at(ivec2 image) {
    return texelFetch(uSrc, clamp(image, ivec2(0), uImage - 1) - uRegion, 0).rgb;
}

void main() {
    ivec2 s = ivec2(gl_FragCoord.xy) - uSlot;
    if (uLevel == 0) {
        outColor = vec4(at(uTile + s), 1.0);
        return;
    }
    ivec2 b = uTile + 2 * s;
    vec3 sum = at(b) + at(b + ivec2(1, 0)) + at(b + ivec2(0, 1)) + at(b + ivec2(1, 1));
    outColor = vec4(0.25 * sum, 1.0);
}
"""
}
