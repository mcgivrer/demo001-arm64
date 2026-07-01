#version 300 es
// Star vertex shader — perspective projection, point size and inverse-square
// brightness are all computed here; one point sprite per star.
precision highp float;

layout(location = 0) in vec3 aPos;         // star position (view space)
layout(location = 1) in float aSize;       // base size (px at z=1)
layout(location = 2) in float aBrightness; // intrinsic brightness 0..1
layout(location = 3) in vec3 aColor;       // spectral colour

uniform vec2  uViewport;    // panel size (px)
uniform vec2  uProjScale;   // projection scale (px), width*0.45 / height*0.45
uniform float uNearZ;       // near plane

out vec3  vColor;
out float vAlpha;

const float GLOW_FACTOR = 2.5;             // glow radius / core radius

void main() {
    float z = aPos.z;
    if (z <= uNearZ) {
        // Behind the near plane: move outside the clip volume
        gl_Position  = vec4(-10.0, -10.0, 0.0, 1.0);
        gl_PointSize = 0.0;
        vColor = vec3(0.0);
        vAlpha = 0.0;
        return;
    }

    // Perspective projection to pixel offsets, then to clip space (y down)
    vec2 px   = vec2(aPos.x / z * uProjScale.x, aPos.y / z * uProjScale.y);
    vec2 clip = px / (uViewport * 0.5);
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);

    // Apparent brightness — inverse-square law (reference distance = 1)
    vAlpha = clamp(aBrightness / (z * z), 0.0, 1.0);

    // Perspective-scaled core radius; the point covers the full glow
    float r = clamp(aSize / z, 0.4, 8.0);
    gl_PointSize = r * GLOW_FACTOR * 2.0;

    vColor = aColor;
}
