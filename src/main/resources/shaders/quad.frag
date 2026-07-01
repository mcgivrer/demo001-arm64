#version 300 es
// HUD quad fragment shader — rounded rectangle via signed distance field
// (SDF), with optional border band. Replaces Java2D's fillRect /
// fillRoundRect / drawRect / drawRoundRect.
precision highp float;

in vec2 vLocal;

uniform vec2  uSize;         // rectangle size (px)
uniform float uRadius;       // corner radius (px), 0 = sharp corners
uniform float uBorderWidth;  // border band width (px), 0 = fill only
uniform vec4  uFillColor;    // premultiplied nothing — plain RGBA
uniform vec4  uBorderColor;

out vec4 fragColor;

// Signed distance to a rounded rectangle centred on the origin
float sdRoundRect(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

void main() {
    vec2  p    = vLocal - uSize * 0.5;
    float dist = sdRoundRect(p, uSize * 0.5, uRadius);
    if (dist > 0.0) discard;

    fragColor = (uBorderWidth > 0.0 && dist > -uBorderWidth)
        ? uBorderColor
        : uFillColor;
}
