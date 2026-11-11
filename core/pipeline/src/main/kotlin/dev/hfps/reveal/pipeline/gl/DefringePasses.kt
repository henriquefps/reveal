package dev.hfps.reveal.pipeline.gl

import android.opengl.GLES30
import dev.hfps.reveal.pipeline.cpu.DefringeParams

/** GLSL of defringe (`cpu/OpticsOps.kt`, `DefringeOp`, docs/adr/0015), line for line. */
internal object DefringeShaders {
    private fun f(v: Float) = ColourShaders.glslFloat(v)

    val FRAG: String = """#version 300 es
precision highp float;
precision highp int;
precision highp sampler2D;

uniform sampler2D uLab;    // the develop output in OkLab (L, a, b, 0), float16-rounded
uniform ivec2 uOrigin;     // image position of texel (0, 0) of uLab and of the target
uniform ivec2 uImage;      // whole image size (taps clamp to the image, as on the CPU)
uniform ivec2 uSize;       // region size
uniform int uRadius;
uniform vec3 uPurple;      // strength, low hue, high hue (degrees)
uniform vec3 uGreen;
uniform int uRgbOut;       // 1: linear Rec.2020 out; 0: OkLab out (the Detail passes follow)
out vec4 outColor;
""" + ColourShaders.OKLAB + Shaders.Q16 + """
float defringeWindow(float h, float lo, float hi) {
    return smoothstep(lo - ${f(
        DefringeParams.FEATHER,
    )}, lo, h) * (1.0 - smoothstep(hi, hi + ${f(DefringeParams.FEATHER)}, h));
}

// DefringeOp.weight
float defringeWeight(float a, float b, float range) {
    float e = smoothstep(${f(DefringeParams.EDGE_LOW)}, ${f(DefringeParams.EDGE_HIGH)}, range);
    if (e == 0.0) {
        return 0.0;
    }
    float h = degrees(atan(b, a));
    if (h < 0.0) {
        h += 360.0;
    }
    float hp = h < 90.0 ? h + 360.0 : h;
    float purple = uPurple.x * defringeWindow(hp, uPurple.y, uPurple.z);
    float green = uGreen.x * defringeWindow(h, uGreen.y, uGreen.z);
    return e * max(purple, green);
}

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    ivec2 ip = p + uOrigin;
    float lo = 3.4e38;
    float hi = -3.4e38;
    for (int dy = -uRadius; dy <= uRadius; dy++) {
        for (int dx = -uRadius; dx <= uRadius; dx++) {
            ivec2 q = clamp(clamp(ip + ivec2(dx, dy), ivec2(0), uImage - 1) - uOrigin, ivec2(0), uSize - 1);
            float l = texelFetch(uLab, q, 0).x;
            lo = min(lo, l);
            hi = max(hi, l);
        }
    }
    vec3 lab = texelFetch(uLab, p, 0).xyz;
    float k = defringeWeight(lab.y, lab.z, hi - lo);
    lab.yz = lab.yz * (1.0 - k);
    outColor = uRgbOut == 1 ? vec4(fromOkLab(lab), 1.0) : vec4(q16(lab), 0.0);
}
"""
}

/** The defringe pass on the GL thread; the caller owns the textures. */
internal class DefringePasses {
    private var program = 0

    fun init() {
        program = GlSupport.compile(Shaders.FULLSCREEN_VERT, DefringeShaders.FRAG)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLab"), 0)
    }

    /**
     * Runs [p] on [lab] (the develop output in OkLab) over [region] into [output]: linear Rec.2020 when [rgbOut], else
     * OkLab for the Detail passes. [output] must not be [lab].
     */
    fun run(p: DefringeParams, lab: Int, output: GlTarget, region: DetailPasses.Region, rgbOut: Boolean) {
        GLES30.glUseProgram(program)
        fun loc(name: String) = GLES30.glGetUniformLocation(program, name)
        GLES30.glUniform2i(loc("uOrigin"), region.x, region.y)
        GLES30.glUniform2i(loc("uImage"), region.imageWidth, region.imageHeight)
        GLES30.glUniform2i(loc("uSize"), region.width, region.height)
        GLES30.glUniform1i(loc("uRadius"), p.radius)
        GLES30.glUniform3f(loc("uPurple"), p.purpleStrength, p.purpleLow, p.purpleHigh)
        GLES30.glUniform3f(loc("uGreen"), p.greenStrength, p.greenLow, p.greenHigh)
        GLES30.glUniform1i(loc("uRgbOut"), if (rgbOut) 1 else 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, lab)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, output.fbo)
        GLES30.glViewport(0, 0, region.width, region.height)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GlSupport.checkError("defringe pass")
    }

    fun release() {
        if (program != 0) GLES30.glDeleteProgram(program)
        program = 0
    }
}
