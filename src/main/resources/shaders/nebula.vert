#version 300 es
// Nebula vertex shader — one instanced quad per gas puff. Unlike the former
// clouds-at-infinity, the puffs carry finite 3D positions already rotated on
// the CPU (star-style), so the shader only projects: centre = pos.xy/z·scale.
// Instanced quads are used instead of point sprites because projected puffs
// exceed the point-size limit. The per-zone fade (near/far) reaches us baked
// into aAlpha; a zero alpha collapses the quad to a degenerate off-screen
// vertex so the fragment stage never runs.
precision highp float;

layout(location = 0) in vec2  aCorner;   // unit quad corner, -1..1 (per vertex)
layout(location = 1) in vec3  aPos;      // rotated view-space position (per instance)
layout(location = 2) in float aSize;     // world-space radius (per instance)
layout(location = 3) in float aAlpha;    // baseAlpha × zone fade (per instance)
layout(location = 4) in vec3  aColIn;    // core colour (per instance)
layout(location = 5) in vec3  aColOut;   // rim colour (per instance)
layout(location = 6) in vec4  aNoise;    // uv offset, uv scale, channel (per instance)

uniform vec2  uViewport;     // render-target size (px)
uniform vec2  uProjScale;    // projection scale (px)
uniform float uMinZ;         // cull puffs near/behind the view plane
uniform float uMaxRadius;    // projected radius cap (px)

out vec2  vCorner;
out vec3  vColIn;
out vec3  vColOut;
out float vAlpha;
out vec2  vNoiseUv;
flat out vec4 vMask;

void main() {
    if (aPos.z < uMinZ || aAlpha <= 0.001) {
        gl_Position = vec4(-10.0, -10.0, 0.0, 1.0);
        vCorner = vec2(0.0); vColIn = vec3(0.0); vColOut = vec3(0.0);
        vAlpha = 0.0; vNoiseUv = vec2(0.0); vMask = vec4(0.0);
        return;
    }

    vec2 centre = aPos.xy / aPos.z * uProjScale;
    float r = min(aSize / aPos.z * uProjScale.x, uMaxRadius);
    vec2 px = centre + aCorner * r;

    vec2 clip = px / (uViewport * 0.5);
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);

    vCorner  = aCorner;
    vColIn   = aColIn;
    vColOut  = aColOut;
    vAlpha   = aAlpha;
    // UV computed here so the fragment stage does a single texture fetch
    vNoiseUv = aNoise.xy + (aCorner * 0.5 + 0.5) * aNoise.z;
    // Channel selector: picks one of the 4 independent noise fields (R/G/B/A)
    vMask = vec4(float(aNoise.w == 0.0), float(aNoise.w == 1.0),
                 float(aNoise.w == 2.0), float(aNoise.w == 3.0));
}
