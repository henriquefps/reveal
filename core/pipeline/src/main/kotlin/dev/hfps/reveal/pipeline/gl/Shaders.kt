package dev.hfps.reveal.pipeline.gl

/**
 * GLSL ES shaders of the develop pipeline. Every function mirrors the CPU reference in `cpu/Operators.kt` line for
 * line (formulas are documented there); the parity test keeps them equal.
 *
 * Stages: scene (WB, highlight reconstruction, matrix, exposure) -> [low-res Light passes] -> develop (local tone,
 * profile, global tone, vibrance/saturation) -> output. The Light group's GLSL lives in [LightShaders].
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

    /** The `Develop` uniform block (std140, binding 0), declared by both develop stages. */
    private const val DEVELOP_BLOCK = """
// One block, one sub-struct per operator (std140): each operator's parameters are uploaded from DevelopParams.
layout(std140) uniform Develop {
    vec4 wbMultiplier;       // RawWhiteBalanceOp: rgb = multipliers
    mat4 cameraToWorking;    // CameraMatrixOp: upper-left 3x3, column-major
    vec4 exposure;           // ExposureOp: x = linear gain 2^EV
};
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;
"""

    /**
     * Stage 1, scene-linear (slots 1-5 of ADR 0002): raw white balance, highlight reconstruction, camera matrix and
     * exposure. Reads the source texture texel by texel and writes the scene texture 1:1 (RGBA16F), which the
     * local-tone guide passes read.
     */
    val SCENE_FRAG: String = HEADER + DEVELOP_BLOCK + LightShaders.COMMON + """
uniform sampler2D uSource;
uniform sampler2D uChroma;
out vec4 outColor;
""" + LightShaders.RECONSTRUCT + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uSource, p, 0).rgb;
    c = c * wbMultiplier.rgb;
    if (grid.w != 0.0) {
        c = reconstructHighlights(c, p);
    }
    c = mat3(cameraToWorking) * c;
    c = c * exposure.x;
    outColor = vec4(c, 1.0);
}
"""

    /**
     * Stage 2, develop (slots 5b-9): highlights/shadows on scene-linear light, the profile (base look, slot 6,
     * [ColourShaders]), contrast/whites/blacks on display-referred light, then vibrance/saturation (slot 9). Reads
     * the scene texture 1:1 and writes the working-space texture (RGBA16F, mip-mapped) the output pass reads.
     */
    val DEVELOP_FRAG: String = HEADER + DEVELOP_BLOCK + LightShaders.COMMON + """
uniform sampler2D uScene;
uniform sampler2D uCoef;
uniform sampler2D uMoments;
out vec4 outColor;

""" + ColourShaders.BLOCK + ColourShaders.FUNCTIONS + LightShaders.TONE + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uScene, p, 0).rgb;
    if (tone.z != 0.0) {
        c = localTone(c, p);
    }
    c = profileLook(c);
    if (tone.w != 0.0) {
        c = globalTone(c);
    }
    c = vibranceSaturation(c);
    outColor = vec4(c, 1.0);
}
"""

    /**
     * Last stage: view transform (zoom/pan, texelFetch at 1:1, else textureLod with a CPU-computed LOD), output
     * transform, sRGB OETF, 8-bit dithering and the optional clipping-warning overlay. Pixels outside the image get
     * the neutral surround colour.
     */
    const val OUTPUT_FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

layout(std140) uniform Output {
    mat4 workingToOutput;    // OutputTransformOp: upper-left 3x3, column-major
    vec4 image;              // xy = image size in pixels, z = screen pixels per image pixel, w = explicit LOD
    vec4 viewport;           // xy = image point at the view centre (image px), zw = view size in pixels
    vec4 surround;           // rgb = display-encoded colour outside the image
    uvec4 dither;            // x = seed, y = 1 when dithering is on, z = sampling mode (0 texelFetch, 1 textureLod),
                             // w = clipping-warning bits (1 shadows, 2 highlights; ClippingWarnings)
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
    vec3 c;
    if (dither.z == 0u) {
        // 1:1: the exact texel, no filtering and no mips.
        c = texelFetch(uStage, clamp(ivec2(floor(ip)), ivec2(0), ivec2(image.xy) - 1), 0).rgb;
    } else {
        // Explicit LOD from the CPU (never from derivatives); magnified views run at LOD 0.
        c = textureLod(uStage, clamp(ip, vec2(0.0), image.xy) / image.xy, image.w).rgb;
    }
    c = clamp(mat3(workingToOutput) * c, 0.0, 1.0);
    vec3 e = srgbOetf(c);
    float n = dither.y != 0u ? ditherNoise(ivec2(floor(sp)), dither.x) : 0.5;
    vec3 q = min(floor(e * 255.0 + n), 255.0) / 255.0;
    // Clipping warnings (ClippingWarnings.classify): on the encoded value before dithering.
    float m = max(e.r, max(e.g, e.b));
    if ((dither.w & 2u) != 0u && m >= 254.5 / 255.0) {
        q = vec3(255.0, 26.0, 26.0) / 255.0;
    } else if ((dither.w & 1u) != 0u && m < 0.5 / 255.0) {
        q = vec3(0.0, 102.0, 255.0) / 255.0;
    }
    outColor = vec4(inside ? q : surround.rgb, 1.0);
}
"""
}
