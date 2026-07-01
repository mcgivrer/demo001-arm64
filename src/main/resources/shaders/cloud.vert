#version 300 es
// Cloud vertex shader — one instanced quad per puff. The puffs are static
// unit directions on the celestial sphere; the camera rotation is a single
// mat3 uniform (cumulative orientation from CameraState), so the whole
// layer needs zero per-frame CPU work. Instanced quads are used instead of
// point sprites because projected puffs exceed the point-size limit.
precision highp float;

layout(location = 0) in vec2 aCorner;   // unit quad corner, -1..1 (per vertex)
layout(location = 1) in vec3 aDir;      // unit direction (per instance)
layout(location = 2) in float aSize;    // angular size, rad (per instance)
layout(location = 3) in float aAlpha;   // translucency (per instance)
layout(location = 4) in vec3 aTint;     // colour (per instance)

uniform mat3  uOrientation;  // cumulative camera rotation
uniform vec2  uViewport;     // panel size (px)
uniform vec2  uProjScale;    // projection scale (px)
uniform float uMinZ;         // cull puffs near/behind the view plane
uniform float uMaxRadius;    // projected radius cap (px)

out vec2  vCorner;
out vec3  vTint;
out float vAlpha;

void main() {
    vec3 d = uOrientation * aDir;
    if (d.z < uMinZ) {
        gl_Position = vec4(-10.0, -10.0, 0.0, 1.0);
        vCorner = vec2(0.0); vTint = vec3(0.0); vAlpha = 0.0;
        return;
    }

    vec2 centre = vec2(d.x / d.z * uProjScale.x, d.y / d.z * uProjScale.y);
    float r = min(aSize / d.z * uProjScale.x, uMaxRadius);
    vec2 px = centre + aCorner * r;

    vec2 clip = px / (uViewport * 0.5);
    gl_Position = vec4(clip.x, -clip.y, 0.0, 1.0);

    vCorner = aCorner;
    vTint   = aTint;
    vAlpha  = aAlpha;
}
