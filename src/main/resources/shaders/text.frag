#version 300 es
// Text fragment shader — the texture holds white glyphs rasterised by AWT
// (alpha = glyph coverage); the text colour is applied as a tint.
precision highp float;

in vec2 vUV;

uniform sampler2D uTexture;
uniform vec4      uColor;

out vec4 fragColor;

void main() {
    float coverage = texture(uTexture, vUV).a;
    fragColor = vec4(uColor.rgb, uColor.a * coverage);
}
