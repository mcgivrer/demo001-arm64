#version 300 es
// HUD quad vertex shader — rectangles specified in pixel space (top-left
// origin, y down, like Java2D).
precision highp float;

layout(location = 0) in vec2 aCorner;   // 0..1 unit quad

uniform vec2 uViewport;  // panel size (px)
uniform vec2 uPos;       // rectangle top-left (px)
uniform vec2 uSize;      // rectangle size (px)

out vec2 vLocal;         // position within the rectangle (px)

void main() {
    vec2 px   = uPos + aCorner * uSize;
    vec2 clip = px / uViewport * 2.0 - 1.0;
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
    vLocal = aCorner * uSize;
}
