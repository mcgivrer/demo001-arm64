#version 300 es
// Star fragment shader — radial core + diffuse glow profile, same stops as
// the former Java2D sprite gradient: opaque plateau on the inner 1/2.5 of
// the radius, fast falloff to a faint halo (~21 %), fading to zero.
precision highp float;

in vec3  vColor;
in float vAlpha;

out vec4 fragColor;

const float CORE = 1.0 / 2.5;   // core/glow boundary (fraction of radius)

void main() {
    // Distance from sprite centre, 0 at centre .. 1 at glow edge
    float d = length(gl_PointCoord - vec2(0.5)) * 2.0;

    float a;
    if (d < CORE * 0.9) {
        a = 1.0;                                                  // opaque core
    } else if (d < CORE * 1.15) {
        a = mix(1.0, 0.235, (d - CORE * 0.9) / (CORE * 0.25));    // falloff
    } else {
        a = mix(0.235, 0.0, (d - CORE * 1.15) / (1.0 - CORE * 1.15)); // halo
    }

    fragColor = vec4(vColor, vAlpha * a);
}
