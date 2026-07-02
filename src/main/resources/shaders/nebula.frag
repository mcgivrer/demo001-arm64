#version 300 es
// Nebula fragment shader — gaseous puff: a radial falloff modulated by a
// pre-baked fBm noise texture (one fetch per fragment — procedural fBm would
// be prohibitive on a software rasteriser). The squared density curve keeps
// the rims wispy; the tint blends from the core colour to the rim colour.
// The output is PREMULTIPLIED alpha: the layer accumulates in an offscreen
// half-resolution FBO with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA).
precision highp float;

in vec2  vCorner;
in vec3  vColIn;
in vec3  vColOut;
in float vAlpha;
in vec2  vNoiseUv;
flat in vec4 vMask;

uniform sampler2D uNoise;

out vec4 fragColor;

void main() {
    float d2 = dot(vCorner, vCorner);
    if (d2 > 1.0) discard;

    float falloff = 1.0 - d2;                          // 1 at centre, 0 at rim
    float n = dot(texture(uNoise, vNoiseUv), vMask);   // per-puff noise field
    float t = falloff * (0.35 + 0.75 * n);
    float a = vAlpha * t * t;

    vec3 tint = mix(vColIn, vColOut, d2);
    fragColor = vec4(tint * a, a);
}
