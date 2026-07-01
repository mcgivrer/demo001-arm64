#version 300 es
// Fullscreen blit fragment shader — the sampled layer holds premultiplied
// alpha, so it must be composited with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA).
precision highp float;

in vec2 vUV;

uniform sampler2D uTexture;

out vec4 fragColor;

void main() {
    fragColor = texture(uTexture, vUV);
}
