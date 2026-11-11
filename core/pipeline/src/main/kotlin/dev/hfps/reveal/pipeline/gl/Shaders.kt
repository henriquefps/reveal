package dev.hfps.reveal.pipeline.gl

/**
 * GLSL ES shaders of the develop pipeline. Every function mirrors the CPU reference in `cpu/Operators.kt` line for
 * line (formulas are documented there); the parity test keeps them equal.
 *
 * Version: `#version 300 es`. The plan asks for OpenGL ES 3.2 as the default target; the shaders only use ES 3.0
 * features (uniform blocks, `texelFetch`, `gl_VertexID`, 32-bit unsigned integer arithmetic, float render targets
 * through `EXT_color_buffer_float`, which is core in ES 3.2), so they run unchanged on a 3.2 phone and on emulator
 * GPUs that stop at 3.0 (docs/adr/0002, "GPU").
 */
internal object Shaders {
    /** Full-screen triangle from `gl_VertexID`; no vertex buffers. */
    const val FULLSCREEN_VERT: String = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

    /**
     * Stage 1, fused: raw white balance, camera matrix, exposure and the base tone curve. Reads the source texture
     * texel by texel and writes the working-space texture 1:1 (RGBA16F).
     */
    const val DEVELOP_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

// One block, one sub-struct per operator (std140): each operator's parameters are uploaded from DevelopParams.
layout(std140) uniform Develop {
    vec4 wbMultiplier;       // RawWhiteBalanceOp: rgb = multipliers, w = highlight clip level
    mat4 cameraToWorking;    // CameraMatrixOp: upper-left 3x3, column-major
    vec4 exposure;           // ExposureOp: x = linear gain 2^EV
    vec4 baseTone;           // BaseToneOp: x = knee
};

uniform sampler2D uSource;
out vec4 outColor;

float toneCurve(float x) {
    float k = baseTone.x;
    if (x <= k) {
        return x;
    }
    float u = (min(x, 10000.0) - k) / (1.0 - k);
    float u2 = u * u;
    float u4 = u2 * u2;
    float u8 = u4 * u4;
    float d = sqrt(sqrt(sqrt(1.0 + u8)));
    return k + (1.0 - k) * (u / d);
}

void main() {
    vec3 c = texelFetch(uSource, ivec2(gl_FragCoord.xy), 0).rgb;
    c = min(c * wbMultiplier.rgb, vec3(wbMultiplier.w));
    c = mat3(cameraToWorking) * c;
    c = c * exposure.x;
    outColor = vec4(toneCurve(c.r), toneCurve(c.g), toneCurve(c.b), 1.0);
}
"""

    /**
     * Last stage: view transform (zoom/pan, mip-mapped bilinear sampling of the working-space texture), output
     * transform, sRGB OETF and 8-bit dithering. Pixels outside the image get the neutral surround colour.
     */
    const val OUTPUT_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout(std140) uniform Output {
    mat4 workingToOutput;    // OutputTransformOp: upper-left 3x3, column-major
    vec4 image;              // xy = image size in pixels, z = screen pixels per image pixel
    vec4 viewport;           // xy = image point at the view centre (image px), zw = view size in pixels
    vec4 surround;           // rgb = display-encoded colour outside the image
    uvec4 dither;            // x = seed, y = 1 when dithering is on
};

uniform sampler2D uStage;
out vec4 outColor;

uint hashLowBias32(uint v) {
    v ^= v >> 16;
    v *= 0x7feb352du;
    v ^= v >> 15;
    v *= 0x846ca68bu;
    v ^= v >> 16;
    return v;
}

float ditherNoise(ivec2 p, uint seed) {
    uint h = hashLowBias32(uint(p.x) + hashLowBias32(uint(p.y) + seed * 0x9E3779B9u));
    return float(h >> 8) * (1.0 / 16777216.0);
}

vec3 srgbOetf(vec3 v) {
    vec3 low = 12.92 * v;
    vec3 high = 1.055 * pow(max(v, vec3(0.0031308)), vec3(1.0 / 2.4)) - 0.055;
    return mix(high, low, lessThanEqual(v, vec3(0.0031308)));
}

void main() {
    // Screen pixel centre, origin top-left (image row 0 is the top).
    vec2 sp = vec2(gl_FragCoord.x, viewport.w - gl_FragCoord.y);
    vec2 ip = (sp - viewport.zw * 0.5) / image.z + viewport.xy;
    bool inside = all(greaterThanEqual(ip, vec2(0.0))) && all(lessThanEqual(ip, image.xy));
    vec3 c = texture(uStage, clamp(ip, vec2(0.0), image.xy) / image.xy).rgb;
    c = clamp(mat3(workingToOutput) * c, 0.0, 1.0);
    vec3 e = srgbOetf(c);
    float n = dither.y != 0u ? ditherNoise(ivec2(floor(sp)), dither.x) : 0.5;
    vec3 q = min(floor(e * 255.0 + n), 255.0) / 255.0;
    outColor = vec4(inside ? q : surround.rgb, 1.0);
}
"""
}
