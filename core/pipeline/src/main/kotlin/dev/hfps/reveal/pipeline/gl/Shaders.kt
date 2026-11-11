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

    /**
     * `q16(x)`: [dev.hfps.reveal.pipeline.cpu.Half.round] on the GPU, the nearest float16 by integer operations on the
     * bits, so an RGBA16F target stores the value exactly whatever the driver's own rounding (docs/adr/0012).
     */
    const val Q16: String = """
float q16(float x) {
    uint u = floatBitsToUint(x);
    return uintBitsToFloat((u + 0x0FFFu + ((u >> 13) & 1u)) & 0xFFFFE000u);
}

vec3 q16(vec3 v) {
    return vec3(q16(v.x), q16(v.y), q16(v.z));
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
     * Stage 1, scene-linear (slots 1-5 of ADR 0002): raw white balance, highlight reconstruction, camera matrix, the
     * lens's vignetting correction (slot 3, `VignetteOp`, docs/adr/0015) and exposure. Reads the source texture texel
     * by texel and writes the scene texture 1:1 (RGBA16F), which the local-tone guide passes read.
     */
    val SCENE_FRAG: String = HEADER + DEVELOP_BLOCK + LightShaders.COMMON + """
uniform sampler2D uSource;
uniform sampler2D uChroma;
// Tiled export (TiledRenderer): image position of this target's pixel (0, 0); 0 for whole-image renders. The tile
// textures are read at the local position, the low-res grids at the image position.
uniform ivec2 uOrigin;
// VignetteOp: x = 1 when on, yz = image centre (pixels of the whole image), w = 1 / half diagonal.
uniform vec4 uVignette;
uniform sampler2D uVignetteLut;
out vec4 outColor;
""" + LightShaders.RECONSTRUCT + WarpShaders.FUNCTIONS + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uSource, p, 0).rgb;
    c = c * wbMultiplier.rgb;
    if (grid.w != 0.0) {
        c = reconstructHighlights(c, p + uOrigin);
    }
    c = mat3(cameraToWorking) * c;
    if (uVignette.x != 0.0) {
        float dx = (float(p.x + uOrigin.x) + 0.5) - uVignette.y;
        float dy = (float(p.y + uOrigin.y) + 0.5) - uVignette.z;
        c = c * lensLookup(uVignetteLut, 0, sqrt(dx * dx + dy * dy) * uVignette.w);
    }
    c = c * exposure.x;
    outColor = vec4(c, 1.0);
}
"""

    /**
     * Stage 2, develop (slots 5b-9): highlights/shadows on scene-linear light, the profile (base look, slot 6,
     * [ColourShaders]), contrast/whites/blacks and the tone curve on display-referred light, then vibrance/saturation,
     * the colour mixer or black & white, and colour grading (slot 9, [ColourToolShaders]). Reads the scene texture 1:1
     * and writes the working-space texture (RGBA16F, mip-mapped) the output pass reads, or, when the Detail operator
     * runs, its OkLab input ([DetailShaders]).
     */
    val DEVELOP_FRAG: String = HEADER + DEVELOP_BLOCK + LightShaders.COMMON + """
uniform sampler2D uScene;
uniform sampler2D uCoef;
uniform sampler2D uMoments;
uniform ivec2 uOrigin;   // see SCENE_FRAG
// 1 when the Detail operator (slot 11, DetailShaders) follows: write OkLab with a zero luminance accumulator in a.
uniform int uLabOut;
out vec4 outColor;

""" + ColourShaders.BLOCK + ColourShaders.FUNCTIONS + ColourToolShaders.BLOCK + ColourToolShaders.FUNCTIONS +
        LightShaders.TONE + Q16 + """
void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec3 c = texelFetch(uScene, p, 0).rgb;
    if (tone.z != 0.0) {
        c = localTone(c, p + uOrigin);
    }
    c = profileLook(c);
    if (tone.w != 0.0) {
        c = globalTone(c);
    }
    c = toneCurve(c);
    c = colourStage(c);
    outColor = uLabOut == 1 ? vec4(q16(toOkLab(c)), 0.0) : vec4(c, 1.0);
}
"""

    /**
     * Output maths shared by the on-screen output pass and the export output pass (`ExportShaders`): the dither hash
     * (`Dither` on the CPU) and the sRGB OETF (`OutputTransformOp`).
     */
    const val OUTPUT_FUNCTIONS: String = """
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
"""

    /**
     * Last stage: view transform (zoom/pan) onto the **output** of the geometry operator, the geometry mapping to the
     * working texture (slot 13, `GeometryOp`: texelFetch when nothing is straightened and the view is 1:1,
     * Catmull-Rom when straightened, textureLod with a CPU-computed LOD when minified), output transform, sRGB OETF,
     * 8-bit dithering and the optional clipping-warning overlay. Pixels outside the output get the neutral surround;
     * empty corners of an unconstrained straighten are black (the crop mode's editing plane shows the surround).
     */
    val OUTPUT_FRAG: String = """#version 300 es
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
    // Full-resolution zoom tiles (ZoomTiles, docs/adr/0012); tileView.y = 0 when this pane shows none.
    vec4 tileView;           // x = full-res pixels per proxy pixel (k), y = tiles on, zw = full-res output size
    vec4 tileGeomX;          // full-res GeometryMapping.toImage row x (xy, z); w = proxy output width
    vec4 tileGeomY;          // row y; w = proxy output height
    vec4 tileAtlas;          // x = tile core, y = slot border, z = slot size, w = explicit LOD (0..1)
    vec4 tileImage;          // xy = full-res image size, zw = atlas size
    uvec4 tileFlags;         // x = sampling mode (as dither.z), y = 1 snaps to full-res output pixel centres
    // Post-crop vignette and grain (EffectsShaders.POST, docs/adr/0014); post1.w = 0 when off.
    vec4 post0;              // vignette, midpoint, feather, highlights
    vec4 post1;              // round, exponent, norm, flags (1 vignette, 2 grain)
    vec4 post2;              // grain, roughness
    vec4 post3;              // lattice cells per output pixel: x = proxy output, y = full-resolution output
    // Lens corrections and Transform (Warp, docs/adr/0015): the plane point to each channel's source point.
    vec4 warpNorm;           // centre xy, half diagonal, 1 / half diagonal (this pane's image)
    vec4 warpRow0;           // plane -> corrected homography rows (normalised coordinates)
    vec4 warpRow1;
    vec4 warpRow2;
    uvec4 warpFlags;         // x = 1 warp on, y = 1 lens table on, z = 1 channels differ (CA)
    vec4 tileWarpNorm;       // as warpNorm for the full-resolution image
};

uniform sampler2D uStage;
uniform sampler2D uLensLut;    // LensWarp table: rgb = per-channel radial scale
uniform sampler2D uAtlas;      // zoom tiles: RGBA16F slots, levels 0-1
uniform sampler2D uTileIndex;  // zoom tiles: RGBA8 per tile, rg = slot, b = fade-in alpha, a = ready
out vec4 outColor;

""" + OUTPUT_FUNCTIONS + EffectsShaders.POST + WarpShaders.FUNCTIONS + """
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

// A zoom tile's colour at full-resolution image point fp of tile t, from the atlas slot the index names: the same
// three samplings as the proxy (exact texel at 1:1, Catmull-Rom when straightened, textureLod at the explicit LOD),
// taps clamped to the image; the slot's border holds the neighbours (or the repeated edge).
vec3 sampleTile(vec2 fp, ivec2 t, vec4 idx) {
    vec2 slot = floor(idx.rg * 255.0 + 0.5) * tileAtlas.z;
    ivec2 base = ivec2(slot) + ivec2(tileAtlas.y) - t * ivec2(tileAtlas.x);
    ivec2 hi = ivec2(tileImage.xy) - 1;
    if (tileFlags.x == 0u) {
        return texelFetch(uAtlas, base + clamp(ivec2(floor(fp)), ivec2(0), hi), 0).rgb;
    }
    if (tileFlags.x == 2u) {
        vec2 s = fp - 0.5;
        vec2 f = floor(s);
        vec4 wx = catmullRomWeights(s.x - f.x);
        vec4 wy = catmullRomWeights(s.y - f.y);
        ivec2 b = ivec2(f) - 1;
        vec3 sum = vec3(0.0);
        for (int j = 0; j < 4; j++) {
            vec3 row = vec3(0.0);
            for (int i = 0; i < 4; i++) {
                row += wx[i] * texelFetch(uAtlas, base + clamp(b + ivec2(i, j), ivec2(0), hi), 0).rgb;
            }
            sum += wy[j] * row;
        }
        return sum;
    }
    vec2 ap = vec2(base) + clamp(fp, vec2(0.0), tileImage.xy);
    return textureLod(uAtlas, ap / tileImage.zw, tileAtlas.w).rgb;
}

// The proxy's stage texture at image point ip in the pass's sampling mode.
vec3 sampleStage(vec2 ip) {
    if (dither.z == 0u) {
        // Exact texel: 1:1 without straightening (crop, quarter turns and flips land on texel centres).
        return texelFetch(uStage, clamp(ivec2(floor(ip)), ivec2(0), ivec2(image.xy) - 1), 0).rgb;
    } else if (dither.z == 2u) {
        return catmullRom(ip);
    }
    // Explicit LOD from the CPU (never from derivatives); magnified views run at LOD 0.
    return textureLod(uStage, clamp(ip, vec2(0.0), image.xy) / image.xy, image.w).rgb;
}

void main() {
    // Screen pixel centre, origin top-left (image row 0 is the top).
    vec2 sp = vec2(gl_FragCoord.x, viewport.w - gl_FragCoord.y);
    // Output point (geometry operator's output), then the working-texture point through the geometry mapping.
    vec2 op0 = (sp - viewport.zw * 0.5) / image.z + viewport.xy;
    vec2 op = op0;
    if (geomFlags.x != 0u) {
        op = floor(op) + 0.5;
    }
    bool insideOutput = all(greaterThanEqual(op, vec2(0.0))) && all(lessThanEqual(op, vec2(geomX.w, geomY.w)));
    vec2 ip = vec2(dot(geomX.xy, op) + geomX.z, dot(geomY.xy, op) + geomY.z);
    bool insideImage = all(greaterThanEqual(ip, vec2(0.0))) && all(lessThanEqual(ip, image.xy));
    vec3 c;
    if (warpFlags.x != 0u) {
        // GeometryOp with a warp: the plane point to each channel's source point (green decides inside/outside).
        vec2 sR;
        vec2 sG;
        vec2 sB;
        bool front = warpSource(ip, warpNorm, warpRow0.xyz, warpRow1.xyz, warpRow2.xyz, warpFlags.y != 0u,
            uLensLut, sR, sG, sB);
        insideImage = insideImage && front && all(greaterThanEqual(sG, vec2(0.0))) && all(lessThanEqual(sG, image.xy));
        if (warpFlags.z != 0u) {
            c = vec3(sampleStage(sR).r, sampleStage(sG).g, sampleStage(sB).b);
        } else {
            c = sampleStage(sG);
        }
    } else {
        c = sampleStage(ip);
    }
    if (!insideImage) {
        c = vec3(0.0);
    }
    vec2 fo = op;
    if (tileView.y != 0.0) {
        // The full-resolution output point of the same screen pixel (ZoomView.fullOutput), then its image point.
        fo = (op0 - vec2(tileGeomX.w, tileGeomY.w) * 0.5) * tileView.x + tileView.zw * 0.5;
        if (tileFlags.y != 0u) {
            fo = floor(fo) + 0.5;
        }
        vec2 fp = vec2(dot(tileGeomX.xy, fo) + tileGeomX.z, dot(tileGeomY.xy, fo) + tileGeomY.z);
        vec2 fR = fp;
        vec2 fB = fp;
        bool front = true;
        if (warpFlags.x != 0u) {
            bool inPlane = all(greaterThanEqual(fp, vec2(0.0))) && all(lessThanEqual(fp, tileImage.xy));
            vec2 fG = fp;
            front = inPlane && warpSource(fp, tileWarpNorm, warpRow0.xyz, warpRow1.xyz, warpRow2.xyz,
                warpFlags.y != 0u, uLensLut, fR, fG, fB);
            fp = fG;
        }
        if (front && all(greaterThanEqual(fp, vec2(0.0))) && all(lessThan(fp, tileImage.xy))) {
            ivec2 t = ivec2(floor(fp / tileAtlas.x));
            vec4 idx = texelFetch(uTileIndex, t, 0);
            if (idx.a > 0.5) {
                // Red and blue sit within a few pixels of green: inside the same slot's border.
                vec3 tc = warpFlags.z != 0u
                    ? vec3(sampleTile(fR, t, idx).r, sampleTile(fp, t, idx).g, sampleTile(fB, t, idx).b)
                    : sampleTile(fp, t, idx);
                c = mix(c, tc, idx.b);
            }
        }
    }
    if (post1.w != 0.0) {
        // Post-crop, in the output's own terms: the full-resolution output where tiles are on, else the proxy's.
        c = tileView.y != 0.0
            ? postCrop(c, fo, tileView.zw, post3.y, post0, post1, post2)
            : postCrop(c, op, vec2(geomX.w, geomY.w), post3.x, post0, post1, post2);
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
