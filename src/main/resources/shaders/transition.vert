#version 300 es
precision highp float;

layout(location = 0) in vec2 aCorner;
out vec2 vUV;

void main() {
    // Framebuffer textures use a bottom-left origin; flip v so the composited
    // scene keeps the same top-left visual orientation as on-screen rendering.
    vUV = vec2(aCorner.x, 1.0 - aCorner.y);
    vec2 clip = aCorner * 2.0 - 1.0;
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);
}
