#version 300 es
// Star fragment shader — stylized arcade sprite with vivid twinkle,
// broad glow and pronounced diffraction spikes.
precision highp float;

in vec3  vColor;
in float vAlpha;
in float vSeed;

uniform float uTime;

out vec4 fragColor;

void main() {
    vec2 p = gl_PointCoord * 2.0 - 1.0;
    float d = length(p);
    if (d > 1.0) discard;

    // High-energy twinkle for a stylized arcade look.
    float phase = vSeed * 6.2831853;
    float twinkleWave =
        0.58 * sin(uTime * (3.3 + vSeed * 2.0) + phase) +
        0.42 * sin(uTime * (8.8 + vSeed * 3.5) + phase * 1.9);
    float twinkleAmp = mix(0.22, 0.08, clamp(vAlpha * 1.6, 0.0, 1.0));
    float twinkle = 1.0 + twinkleAmp * twinkleWave;

    // Bright core and generous halo/corona for stronger readability on motion.
    float core   = exp(-pow(d / 0.22, 2.0));
    float halo   = exp(-pow(d / 0.70, 2.0)) * 0.95;
    float corona = exp(-pow((d - 0.42) / 0.25, 2.0)) * 0.30;

    // Crisp diffraction spikes with visible temporal modulation.
    float spikePulse = 0.80 + 0.20 * sin(uTime * (6.4 + vSeed * 2.5) + phase * 2.4);
    float spikeX = exp(-abs(p.y) * 11.0) * (1.0 - smoothstep(0.20, 1.0, abs(p.x)));
    float spikeY = exp(-abs(p.x) * 11.0) * (1.0 - smoothstep(0.20, 1.0, abs(p.y)));
    float spikes = (spikeX + spikeY) * 0.24 * spikePulse * smoothstep(0.05, 0.90, vAlpha);

    float profile = core + halo + corona + spikes;
    float alpha = clamp(vAlpha * profile * twinkle, 0.0, 1.0);

    // Strong bloom-style whitening for punchy highlights.
    float whiten = clamp(core * 0.62 + spikes * 1.45, 0.0, 0.72);
    vec3 color = mix(vColor, vec3(1.0), whiten);

    fragColor = vec4(color, alpha);
}
