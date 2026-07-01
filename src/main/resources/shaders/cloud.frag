#version 300 es
// Cloud fragment shader — soft radial puff, same stops as the former
// Java2D sprite gradient: opaque tinted centre (relative to the puff's own
// translucency), 43 % at 45 % of the radius, transparent edge.
// The output is PREMULTIPLIED alpha: the layer accumulates in an offscreen
// half-resolution FBO with glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA).
precision highp float;

in vec2  vCorner;
in vec3  vTint;
in float vAlpha;

out vec4 fragColor;

void main() {
    float d = length(vCorner);
    if (d > 1.0) discard;

    float a = d < 0.45
        ? mix(1.0, 0.431, d / 0.45)
        : mix(0.431, 0.0, (d - 0.45) / 0.55);

    a *= vAlpha;
    fragColor = vec4(vTint * a, a);
}
