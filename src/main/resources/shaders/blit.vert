#version 300 es
// Blit vertex shader — composites a sub-rectangle of an offscreen layer
// (e.g. the half-resolution cloud FBO) onto the default framebuffer. The
// rectangle is given in screen pixels (top-left origin); UVs address the
// matching region of the layer texture.
precision highp float;

layout(location = 0) in vec2 aCorner;   // 0..1 unit quad

uniform vec2 uViewport;  // panel size (px)
uniform vec2 uPos;       // rectangle top-left (px)
uniform vec2 uSize;      // rectangle size (px)

out vec2 vUV;

void main() {
    vec2 px   = uPos + aCorner * uSize;
    vec2 clip = px / uViewport * 2.0 - 1.0;
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
    // The layer was rendered with the same y-down convention: v flips back
    vUV = vec2(px.x / uViewport.x, 1.0 - px.y / uViewport.y);
}
