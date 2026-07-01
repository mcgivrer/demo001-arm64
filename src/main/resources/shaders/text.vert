#version 300 es
// Text vertex shader — one textured quad per cached string, pixel space.
precision highp float;

layout(location = 0) in vec2 aCorner;   // 0..1 unit quad

uniform vec2 uViewport;  // panel size (px)
uniform vec2 uPos;       // quad top-left (px)
uniform vec2 uSize;      // quad size (px)

out vec2 vUV;

void main() {
    vec2 px   = uPos + aCorner * uSize;
    vec2 clip = px / uViewport * 2.0 - 1.0;
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
    vUV = aCorner;
}
