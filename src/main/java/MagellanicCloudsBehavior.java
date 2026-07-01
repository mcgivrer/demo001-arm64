import java.util.Random;

import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

/**
 * Background layer: two irregular dwarf-galaxy clouds inspired by the Large
 * and Small Magellanic Clouds, joined by a faint bridge and sprinkled with
 * pinkish HII regions. Each cloud is a set of soft "puffs" — unit direction
 * vectors on the celestial sphere — generated once from the master SEED.
 * The puffs sit at infinity: they rotate with the camera but never translate
 * with forward travel, so the clouds form a stable backdrop.
 *
 * <p>GL rendering: the puffs live in a <b>static instanced VBO</b> (one soft
 * quad per puff, radial gradient in {@code cloud.frag}); the camera rotation
 * reaches the vertex shader as the cumulative orientation mat3 maintained by
 * {@link CameraState} — the layer costs zero CPU work per frame.
 *
 * <p>The overlapping puffs cause heavy fragment overdraw, expensive under a
 * software rasteriser (llvmpipe): the layer is therefore rendered into a
 * <b>half-resolution FBO</b> (4× fewer fragments, invisible on such soft
 * gradients, premultiplied alpha) and composited as one fullscreen textured
 * quad via the {@code blit} shader.
 */
public class MagellanicCloudsBehavior implements Behavior {

    private static final long   CLOUD_SUBSEED_INDEX = -1;   // spawnCounter is ≥ 0 → never collides
    private static final int    LMC_PUFFS    = 120;
    private static final int    SMC_PUFFS    = 70;
    private static final int    BRIDGE_PUFFS = 30;
    private static final int    HII_PUFFS    = 10;
    private static final int    TOTAL_PUFFS  = LMC_PUFFS + SMC_PUFFS + BRIDGE_PUFFS + HII_PUFFS;

    private static final double LMC_RADIUS   = 0.22;  // angular radius (rad)
    private static final double SMC_RADIUS   = 0.13;
    private static final double PAIR_ANGLE   = 0.60;  // angular separation LMC ↔ SMC (rad)

    private static final float  MIN_Z        = 0.15f; // cull puffs close to / behind the view plane
    private static final float  MAX_RADIUS   = 220f;  // projected puff radius cap (px)

    private static final int    FLOATS_PER_PUFF = 8;  // dir xyz, size, alpha, tint rgb
    private static final int    FBO_SCALE       = 2;  // offscreen layer at panel/2

    private static final double REFRESH_ROT_EPS    = 0.002;  // rad — ≈ 0.7 px of screen motion
    private static final int    REFRESH_MIN_FRAMES = 4;      // amortises the FBO pass
    private static final int    BBOX_MARGIN        = 24;     // px — covers cache lag + perspective stretch

    private static final float[][] TINTS = {
        {205 / 255f, 218 / 255f, 255 / 255f},   // bluish white — cloud body
        {255 / 255f, 240 / 255f, 224 / 255f},   // warm white — old stellar population
        {255 / 255f, 170 / 255f, 195 / 255f},   // pink — HII star-forming regions
    };

    // Interleaved per-instance data, filled at generation, uploaded once
    private final float[] puffData = new float[TOTAL_PUFFS * FLOATS_PER_PUFF];
    private int puffIndex;

    private int vao;
    private int fbo, fboTexture, blitVao;
    private int fboWidth, fboHeight;

    // Components (LMC, SMC, bridge) for CPU-side bounding-box culling:
    // sky-space centre direction + angular extent including puff sizes
    private final double[][] compCenter = new double[3][];
    private final double[]   compExtent = new double[3];
    private int currentComp = -1;
    private final double[] rotated = new double[3];   // scratch for applyOrientation

    // FBO cache: the puff pass only reruns when the camera moved enough
    private double  rotSinceRefresh;
    private int     framesSinceRefresh;
    private boolean cacheValid;

    private final CameraState camera;
    private final int    cx, cy;
    private final double projScaleX, projScaleY;

    public MagellanicCloudsBehavior(int width, int height, CameraState camera, long seed) {
        this.camera = camera;
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;

        Random gen = new Random(StarfieldBehavior.subSeed(seed, CLOUD_SUBSEED_INDEX));
        generate(gen);
    }

    @Override
    public void init(RenderContext ctx) {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);

        // Per-vertex: unit quad corners (-1..1), triangle strip
        int quadVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, quadVbo);
        glBufferData(GL_ARRAY_BUFFER,
            new float[] {-1, -1,  1, -1,  -1, 1,  1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);

        // Per-instance: direction, size, alpha, tint — static, uploaded once
        int instVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, instVbo);
        glBufferData(GL_ARRAY_BUFFER, puffData, GL_STATIC_DRAW);
        int stride = FLOATS_PER_PUFF * Float.BYTES;
        glEnableVertexAttribArray(1);                                  // direction
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribDivisor(1, 1);
        glEnableVertexAttribArray(2);                                  // angular size
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 12);
        glVertexAttribDivisor(2, 1);
        glEnableVertexAttribArray(3);                                  // alpha
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 16);
        glVertexAttribDivisor(3, 1);
        glEnableVertexAttribArray(4);                                  // tint
        glVertexAttribPointer(4, 3, GL_FLOAT, false, stride, 20);
        glVertexAttribDivisor(4, 1);
        glBindVertexArray(0);

        // Half-resolution offscreen layer + fullscreen quad for compositing
        fboWidth  = Math.max(1, cx * 2 / FBO_SCALE);
        fboHeight = Math.max(1, cy * 2 / FBO_SCALE);
        fboTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fboWidth, fboHeight, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, fboTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Cloud FBO incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        blitVao = glGenVertexArrays();
        glBindVertexArray(blitVao);
        int blitVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, blitVbo);
        glBufferData(GL_ARRAY_BUFFER,
            new float[] {0, 0,  1, 0,  0, 1,  1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glBindVertexArray(0);
    }

    // --- Procedural generation -------------------------------------------

    private void generate(Random gen) {
        // LMC centre: biased toward the initial forward hemisphere so the main
        // cloud is visible at startup (z ∈ [0.55, 0.9])
        double[] lmc = randomDirection(gen, 0.55, 0.90);
        // SMC: fixed angular distance from the LMC, random azimuth around it
        double[] smc = rotateAround(lmc, gen.nextDouble() * Math.PI * 2, PAIR_ANGLE);

        beginComponent(0, lmc);
        scatterCloud(gen, lmc, LMC_PUFFS, LMC_RADIUS, 0.55, 0.10f, 0.060f, 0.110f);
        beginComponent(1, smc);
        scatterCloud(gen, smc, SMC_PUFFS, SMC_RADIUS, 0.70, 0.075f, 0.055f, 0.100f);
        beginComponent(2, normalize(new double[] {
            (lmc[0] + smc[0]) / 2, (lmc[1] + smc[1]) / 2, (lmc[2] + smc[2]) / 2}));
        scatterBridge(gen, lmc, smc, BRIDGE_PUFFS);
        // HII puffs live inside the LMC/SMC — scatterHii switches components
        scatterHii(gen, lmc, smc);
    }

    private void beginComponent(int index, double[] center) {
        currentComp = index;
        compCenter[index] = center;
    }

    /**
     * Scatters {@code count} puffs around {@code center} with an anisotropic
     * (elongated) Gaussian in the tangent plane; ~1/3 of the puffs cluster
     * around sub-clumps for an irregular, patchy structure.
     */
    private void scatterCloud(Random gen, double[] center, int count,
                              double radius, double flatten,
                              float puffSize, float alphaMin, float alphaMax) {
        double[] u = tangentU(center);
        double[] v = cross(center, u);
        double theta = gen.nextDouble() * Math.PI;          // elongation orientation
        double cosT = Math.cos(theta), sinT = Math.sin(theta);

        // Sub-clump centres (offsets in the tangent plane)
        int nClumps = 2 + gen.nextInt(3);
        double[][] clumps = new double[nClumps][2];
        for (double[] c : clumps) {
            c[0] = gen.nextGaussian() * radius * 0.8;
            c[1] = gen.nextGaussian() * radius * 0.8 * flatten;
        }

        for (int k = 0; k < count; k++) {
            double a, b;
            if (k % 3 == 0) {                               // clumped puff
                double[] c = clumps[gen.nextInt(nClumps)];
                a = c[0] + gen.nextGaussian() * radius * 0.30;
                b = c[1] + gen.nextGaussian() * radius * 0.30 * flatten;
            } else {                                        // diffuse body
                a = gen.nextGaussian() * radius;
                b = gen.nextGaussian() * radius * flatten;
            }
            // Rotate the elongation axis within the tangent plane
            double oa = a * cosT - b * sinT;
            double ob = a * sinT + b * cosT;
            addPuff(offset(center, u, v, oa, ob),
                    puffSize * (0.6f + gen.nextFloat() * 0.9f),
                    alphaMin + gen.nextFloat() * (alphaMax - alphaMin),
                    gen.nextFloat() < 0.75f ? 0 : 1);
        }
    }

    /** Faint chain of puffs along the great-circle arc joining the two clouds. */
    private void scatterBridge(Random gen, double[] from, double[] to, int count) {
        for (int k = 0; k < count; k++) {
            double t = (k + gen.nextDouble()) / count;
            double[] p = normalize(new double[] {
                from[0] + (to[0] - from[0]) * t,
                from[1] + (to[1] - from[1]) * t,
                from[2] + (to[2] - from[2]) * t,
            });
            double[] u = tangentU(p);
            double[] v = cross(p, u);
            p = offset(p, u, v,
                       gen.nextGaussian() * 0.045, gen.nextGaussian() * 0.045);
            addPuff(p,
                    0.05f + gen.nextFloat() * 0.04f,
                    0.030f + gen.nextFloat() * 0.030f,
                    0);
        }
    }

    /** Small bright pink HII regions inside the two clouds (Tarantula-like). */
    private void scatterHii(Random gen, double[] lmc, double[] smc) {
        for (int k = 0; k < HII_PUFFS; k++) {
            currentComp     = k < 7 ? 0 : 1;
            double[] center = k < 7 ? lmc : smc;
            double radius   = (k < 7 ? LMC_RADIUS : SMC_RADIUS) * 0.6;
            double[] u = tangentU(center);
            double[] v = cross(center, u);
            double[] p = offset(center, u, v,
                                gen.nextGaussian() * radius,
                                gen.nextGaussian() * radius);
            addPuff(p,
                    0.020f + gen.nextFloat() * 0.020f,
                    0.16f + gen.nextFloat() * 0.14f,
                    2);
        }
    }

    private void addPuff(double[] dir, float size, float alpha, int tint) {
        int o = puffIndex++ * FLOATS_PER_PUFF;
        puffData[o]     = (float) dir[0];
        puffData[o + 1] = (float) dir[1];
        puffData[o + 2] = (float) dir[2];
        puffData[o + 3] = size;
        puffData[o + 4] = alpha;
        puffData[o + 5] = TINTS[tint][0];
        puffData[o + 6] = TINTS[tint][1];
        puffData[o + 7] = TINTS[tint][2];

        // Grow the owning component's angular extent (for bbox culling)
        double[] c = compCenter[currentComp];
        double dot = Math.clamp(c[0] * dir[0] + c[1] * dir[1] + c[2] * dir[2], -1.0, 1.0);
        compExtent[currentComp] = Math.max(compExtent[currentComp], Math.acos(dot) + size);
    }

    // --- Small vector helpers on the unit sphere -------------------------

    private static double[] randomDirection(Random gen, double zMin, double zMax) {
        double z   = zMin + gen.nextDouble() * (zMax - zMin);
        double phi = gen.nextDouble() * Math.PI * 2;
        double r   = Math.sqrt(1 - z * z);
        return new double[] {r * Math.cos(phi), r * Math.sin(phi), z};
    }

    /** Rotates {@code dir} by {@code angle} away from itself, azimuth {@code phi}. */
    private static double[] rotateAround(double[] dir, double phi, double angle) {
        double[] u = tangentU(dir);
        double[] v = cross(dir, u);
        double sinA = Math.sin(angle), cosA = Math.cos(angle);
        double ux = u[0] * Math.cos(phi) + v[0] * Math.sin(phi);
        double uy = u[1] * Math.cos(phi) + v[1] * Math.sin(phi);
        double uz = u[2] * Math.cos(phi) + v[2] * Math.sin(phi);
        return new double[] {
            dir[0] * cosA + ux * sinA,
            dir[1] * cosA + uy * sinA,
            dir[2] * cosA + uz * sinA,
        };
    }

    private static double[] tangentU(double[] dir) {
        // Any vector not parallel to dir works as a helper axis
        double[] up = Math.abs(dir[1]) < 0.9 ? new double[] {0, 1, 0}
                                             : new double[] {1, 0, 0};
        return normalize(cross(up, dir));
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[] {
            a[1] * b[2] - a[2] * b[1],
            a[2] * b[0] - a[0] * b[2],
            a[0] * b[1] - a[1] * b[0],
        };
    }

    private static double[] normalize(double[] a) {
        double n = Math.sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
        return new double[] {a[0] / n, a[1] / n, a[2] / n};
    }

    /** Point on the sphere at tangent-plane offsets (a, b) from {@code center}. */
    private static double[] offset(double[] center, double[] u, double[] v,
                                   double a, double b) {
        return normalize(new double[] {
            center[0] + u[0] * a + v[0] * b,
            center[1] + u[1] * a + v[1] * b,
            center[2] + u[2] * a + v[2] * b,
        });
    }

    // --- Behavior ---------------------------------------------------------

    @Override
    public void update(Entity entity, double dt) {
        // The puffs are static on the celestial sphere (the rotation reaches
        // the shader as CameraState's mat3 uniform); only track how much the
        // camera moved since the last FBO refresh.
        rotSinceRefresh += Math.abs(camera.frameYaw) + Math.abs(camera.framePitch)
                         + Math.abs(camera.frameRoll);
        framesSinceRefresh++;
    }

    @Override
    public void draw(Entity entity, RenderContext ctx) {
        // CPU bounding box of the visible components — skips both passes when
        // the clouds are behind the camera, and keeps the composite quad small
        int panelW = cx * 2, panelH = cy * 2;
        int bbMinX = panelW, bbMinY = panelH, bbMaxX = 0, bbMaxY = 0;
        double maxViewAngle = Math.atan(Math.hypot(cx / projScaleX, cy / projScaleY));

        for (int c = 0; c < compCenter.length; c++) {
            camera.applyOrientation(compCenter[c], rotated);
            double angleOffAxis = Math.acos(Math.clamp(rotated[2], -1.0, 1.0));
            if (angleOffAxis - compExtent[c] > maxViewAngle) continue;  // fully outside

            if (rotated[2] < MIN_Z * 1.5) {
                // Grazing the view plane: projection unstable → full screen
                bbMinX = 0; bbMinY = 0; bbMaxX = panelW; bbMaxY = panelH;
                break;
            }
            double px = cx + rotated[0] / rotated[2] * projScaleX;
            double py = cy + rotated[1] / rotated[2] * projScaleY;
            // Conservative pixel radius: 1.3 covers perspective stretching
            double r = compExtent[c] / rotated[2] * projScaleX * 1.3 + BBOX_MARGIN;
            bbMinX = Math.min(bbMinX, (int) (px - r));
            bbMinY = Math.min(bbMinY, (int) (py - r));
            bbMaxX = Math.max(bbMaxX, (int) (px + r));
            bbMaxY = Math.max(bbMaxY, (int) (py + r));
        }
        bbMinX = Math.max(0, bbMinX);      bbMinY = Math.max(0, bbMinY);
        bbMaxX = Math.min(panelW, bbMaxX); bbMaxY = Math.min(panelH, bbMaxY);
        if (bbMaxX <= bbMinX || bbMaxY <= bbMinY) return;   // nothing visible

        // Pass 1 — accumulate the puffs in the half-resolution FBO
        // (premultiplied alpha, hence the GL_ONE source factor). Cached:
        // reruns only once the camera has rotated ≈ a pixel's worth.
        if (!cacheValid
            || (rotSinceRefresh > REFRESH_ROT_EPS && framesSinceRefresh >= REFRESH_MIN_FRAMES)) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, fboWidth, fboHeight);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            ShaderProgram shader = ctx.cloudShader;
            shader.use();
            shader.setMat3("uOrientation", camera.orientationColumnMajor());
            shader.setVec2("uViewport", fboWidth, fboHeight);
            shader.setVec2("uProjScale",
                (float) projScaleX / FBO_SCALE, (float) projScaleY / FBO_SCALE);
            shader.setFloat("uMinZ", MIN_Z);
            shader.setFloat("uMaxRadius", MAX_RADIUS / FBO_SCALE);

            glBindVertexArray(vao);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, TOTAL_PUFFS);
            glBindVertexArray(0);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, panelW, panelH);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glClearColor(0f, 0f, 0f, 1f);
            rotSinceRefresh    = 0;
            framesSinceRefresh = 0;
            cacheValid         = true;
        }

        // Pass 2 — composite the cached layer over the scene, restricted to
        // the content bounding box. The clouds are the first thing drawn
        // after the clear: premultiplied colour over pure black equals the
        // source values, so blending is disabled (a plain store is much
        // cheaper on a software rasteriser).
        glDisable(GL_BLEND);
        ctx.blitShader.use();
        ctx.blitShader.setVec2("uViewport", panelW, panelH);
        ctx.blitShader.setVec2("uPos", bbMinX, bbMinY);
        ctx.blitShader.setVec2("uSize", bbMaxX - bbMinX, bbMaxY - bbMinY);
        ctx.blitShader.setInt("uTexture", 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glBindVertexArray(blitVao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glEnable(GL_BLEND);
    }
}
