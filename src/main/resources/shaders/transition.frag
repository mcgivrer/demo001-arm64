#version 300 es
precision highp float;

in vec2 vUV;

uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform int uEffect;
uniform float uProgress;

out vec4 fragColor;

float easeInOutCubic(float t) {
    return t < 0.5
        ? 4.0 * t * t * t
        : 1.0 - pow(-2.0 * t + 2.0, 3.0) * 0.5;
}

float easeOutCubic(float t) {
    float m = 1.0 - t;
    return 1.0 - m * m * m;
}

vec4 sampleSafe(sampler2D tex, vec2 uv) {
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) return vec4(0.0);
    return texture(tex, uv);
}

void main() {
    float p = clamp(uProgress, 0.0, 1.0);
    float pe = easeInOutCubic(p);
    vec4 fromC = texture(uFrom, vUV);
    vec4 toC = texture(uTo, vUV);

    if (uEffect == 1) {
        // FadeOut/FadeIn through black with eased timing.
        if (pe < 0.5) {
            float k = 1.0 - pe * 2.0;
            fragColor = fromC * k;
        } else {
            float k = (pe - 0.5) * 2.0;
            fragColor = toC * k;
        }
        return;
    }

    if (uEffect == 2) {
        // True cross-fade between both scenes with nonlinear easing.
        fragColor = mix(fromC, toC, pe);
        return;
    }

    if (uEffect == 3) {
        // Left-to-right wipe with eased travel and adaptive feathered edge.
        float reveal = pe;
        float feather = mix(0.010, 0.028, 1.0 - abs(2.0 * reveal - 1.0));
        float mask = 1.0 - smoothstep(reveal - feather, reveal + feather, vUV.x);
        fragColor = mix(fromC, toC, mask);
        return;
    }

    if (uEffect == 4) {
        // Softer zoom motion: eased scales + eased dissolve.
        float z = easeOutCubic(pe);
        vec2 c = vec2(0.5);
        float fromScale = mix(1.0, 1.16, z);
        float toScale = mix(1.12, 1.0, z);
        vec2 fromUV = (vUV - c) * fromScale + c;
        vec2 toUV = (vUV - c) * toScale + c;
        vec4 zFrom = sampleSafe(uFrom, fromUV);
        vec4 zTo = sampleSafe(uTo, toUV);
        float blend = smoothstep(0.07, 0.93, pe);
        fragColor = mix(zFrom, zTo, blend);
        return;
    }

    // CUT / fallback
    fragColor = p < 1.0 ? fromC : toC;
}
