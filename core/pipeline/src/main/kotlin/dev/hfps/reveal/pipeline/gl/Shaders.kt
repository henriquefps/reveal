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
     * Last stage: view transform (zoom/pan) onto the **output** of the geometry operator, the geometry mapping to the
     * working texture (slot 13, `GeometryOp`: texelFetch when nothing is straightened and the view is 1:1,
     * Catmull-Rom when straightened, textureLod with a CPU-computed LOD when minified), output transform, sRGB OETF,
     * 8-bit dithering and the optional clipping-warning overlay. Pixels outside the output get the neutral surround;
     * empty corners of an unconstrained straighten are black (the crop mode's editing plane shows the surround).
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
    uvec4 dither;            // x = seed, y = 1 when dithering is on, z = sampling mode (0 texelFetch, 1 textureLod,
                             // 2 Catmull-Rom), w = clipping-warning bits (1 shadows, 2 highlights; ClippingWarnings)
    vec4 geomX;              // GeometryMapping.toImage: image x = dot(xy, output) + z; w = output width
    vec4 geomY;              // image y = dot(xy, output) + z; w = output height
    uvec4 geomFlags;         // x = 1 snaps to output pixel centres, y = 1 when empty means the surround
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

// GeometryOp.weights: Catmull-Rom (a = -0.5) for the fraction t.
vec4 catmullRomWeights(float t) {
    return vec4(
        ((-0.5 * t + 1.0) * t - 0.5) * t,
        (1.5 * t - 2.5) * t * t + 1.0,
        ((-1.5 * t + 2.0) * t + 0.5) * t,
        (0.5 * t - 0.5) * t * t);
}

// GeometryOp.catmullRom: 4x4 explicit texel fetches at LOD 0, taps clamped to the image.
vec3 catmullRom(vec2 ip) {
    vec2 s = ip - 0.5;
    vec2 f = floor(s);
    vec4 wx = catmullRomWeights(s.x - f.x);
    vec4 wy = catmullRomWeights(s.y - f.y);
    ivec2 b = ivec2(f) - 1;
    ivec2 hi = ivec2(image.xy) - 1;
    vec3 sum = vec3(0.0);
    for (int j = 0; j < 4; j++) {
        int y = clamp(b.y + j, 0, hi.y);
        vec3 row = vec3(0.0);
        for (int i = 0; i < 4; i++) {
            row += wx[i] * texelFetch(uStage, ivec2(clamp(b.x + i, 0, hi.x), y), 0).rgb;
        }
        sum += wy[j] * row;
    }
    return sum;
}

void main() {
    // Screen pixel centre, origin top-left (image row 0 is the top).
    vec2 sp = vec2(gl_FragCoord.x, viewport.w - gl_FragCoord.y);
    // Output point (geometry operator's output), then the working-texture point through the geometry mapping.
    vec2 op = (sp - viewport.zw * 0.5) / image.z + viewport.xy;
    if (geomFlags.x != 0u) {
        op = floor(op) + 0.5;
    }
    bool insideOutput = all(greaterThanEqual(op, vec2(0.0))) && all(lessThanEqual(op, vec2(geomX.w, geomY.w)));
    vec2 ip = vec2(dot(geomX.xy, op) + geomX.z, dot(geomY.xy, op) + geomY.z);
    bool insideImage = all(greaterThanEqual(ip, vec2(0.0))) && all(lessThanEqual(ip, image.xy));
    vec3 c;
    if (dither.z == 0u) {
        // Exact texel: 1:1 without straightening (crop, quarter turns and flips land on texel centres).
        c = texelFetch(uStage, clamp(ivec2(floor(ip)), ivec2(0), ivec2(image.xy) - 1), 0).rgb;
    } else if (dither.z == 2u) {
        c = catmullRom(ip);
    } else {
        // Explicit LOD from the CPU (never from derivatives); magnified views run at LOD 0.
        c = textureLod(uStage, clamp(ip, vec2(0.0), image.xy) / image.xy, image.w).rgb;
    }
    if (!insideImage) {
        c = vec3(0.0);
    }
    c = clamp(mat3(workingToOutput) * c, 0.0, 1.0);
    vec3 e = srgbOetf(c);
    float n = dither.y != 0u ? ditherNoise(ivec2(floor(sp)), dither.x) : 0.5;
    vec3 q = min(floor(e * 255.0 + n), 255.0) / 255.0;
    // Clipping warnings (ClippingWarnings.classify): on the encoded value before dithering; not on empty corners.
    float m = max(e.r, max(e.g, e.b));
    if (insideImage && (dither.w & 2u) != 0u && m >= 254.5 / 255.0) {
        q = vec3(255.0, 26.0, 26.0) / 255.0;
    } else if (insideImage && (dither.w & 1u) != 0u && m < 0.5 / 255.0) {
        q = vec3(0.0, 102.0, 255.0) / 255.0;
    }
    bool shown = insideOutput && (insideImage || geomFlags.y == 0u);
    outColor = vec4(shown ? q : surround.rgb, 1.0);
}
"""
}
