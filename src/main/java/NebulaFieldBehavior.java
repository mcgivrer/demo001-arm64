import java.nio.ByteBuffer;
import java.util.Random;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

/**
 * Background layer: several colourful volumetric gas nebulae. Each zone is an
 * anisotropic ellipsoidal cluster of soft noise-textured puffs at a finite 3D
 * position in the same coordinate system as the stars: the zones rotate with
 * the camera <b>and travel toward the viewer</b> under engine thrust, then
 * respawn on the far shell — the ship flies through them. Per-puff depth
 * offsets inside a zone give real internal parallax (the volumetric feel).
 *
 * <p>Colours come from a palette of vivid two-colour (core → rim) nebula
 * types; each zone picks one palette, each puff blends along the radial
 * distance with per-puff jitter, and a few "bright core" puffs are pushed
 * toward white. All colours are baked into the instance attributes at spawn.
 *
 * <p>GL rendering: instanced quads in a <b>dynamic VBO</b> (positions change
 * every frame), a soft radial profile modulated by a pre-baked fBm noise
 * texture in {@code nebula.frag}. The overlapping puffs cause heavy fragment
 * overdraw, expensive under a software rasteriser (llvmpipe): the layer is
 * rendered into a <b>half-resolution FBO</b> whose refresh is cached — it
 * reruns only when the accumulated camera rotation <b>or</b> forward travel
 * amounts to a pixel of screen motion — and composited over the freshly
 * cleared frame as one bounding-box quad with blending disabled.
 *
 * <p>Zones fade out before reaching the near plane and fade in after
 * respawning far away, so neither transition ever pops — and the largest,
 * closest (most expensive) puffs are never drawn at full strength.
 */
public class NebulaFieldBehavior implements Behavior {

    private static final long   NOISE_SUBSEED_INDEX = -1;   // spawnCounter is ≥ 0 → never collides
    private static final long   ZONE_SUBSEED_BASE   = -2;   // -2, -3, -4, ... one per zone spawn

    private static final int    ZONE_COUNT     = 6;
    private static final int    PUFFS_PER_ZONE = 25;
    private static final int    TOTAL_PUFFS    = ZONE_COUNT * PUFFS_PER_ZONE;
    private static final int    BRIGHT_CORES   = 3;    // per zone, whitened HII-like knots

    private static final double ZONE_XY         = 1.6;  // spawn range for x, y
    private static final double SCATTER_Z_MIN   = 0.5;  // initial spread through depth
    private static final double SCATTER_Z_MAX   = 2.0;
    private static final double RESPAWN_Z_MIN   = 1.95; // far shell, behind the far fade
    private static final double RESPAWN_Z_MAX   = 2.15;
    private static final double ZONE_RADIUS_MIN = 0.25;
    private static final double ZONE_RADIUS_MAX = 0.50;

    private static final double TRAVEL_SPEED   = 0.20;  // base forward speed (units/s at z=1)
    private static final double ZONE_SPEED_MIN = 0.6;   // per-zone speed multiplier range
    private static final double ZONE_SPEED_MAX = 1.2;

    // Fade windows along z: invisible at both ends → pop-free respawn and no
    // full-screen overdraw from a nebula grazing the camera
    private static final double FADE_NEAR_END   = 0.35;  // fully transparent below (respawn point)
    private static final double FADE_NEAR_START = 0.75;  // fully opaque above
    private static final double FADE_FAR_START  = 1.55;  // starts fading beyond
    private static final double FADE_FAR_END    = 1.95;  // fully transparent beyond

    private static final float  MIN_Z      = 0.10f; // cull puffs close to / behind the view plane
    private static final float  MAX_RADIUS = 128f;  // projected puff radius cap (px)

    private static final int    FLOATS_PER_PUFF = 15; // pos xyz, size, alpha, colIn rgb, colOut rgb, noise uvsc
    private static final int    FBO_SCALE       = 2;  // offscreen layer at panel/2
    private static final int    NOISE_SIZE      = 128;

    private static final double REFRESH_ROT_EPS    = 0.002; // rad — ≈ 0.7 px of screen motion
    private static final double REFRESH_TRAVEL_PX  = 1.0;   // px — travel-induced screen motion
    private static final int    REFRESH_MIN_FRAMES = 2;     // amortises the FBO pass
    private static final int    BBOX_MARGIN        = 24;    // px — covers cache lag + noise wisps

    // Vivid two-colour nebula palettes: {core RGB, rim RGB} (0-255)
    private static final float[][][] PALETTES = {
        {{255,  80, 140}, {200,  40,  70}},  // H-alpha — magenta core, deep red rim
        {{ 80, 235, 220}, { 30, 140, 180}},  // O-III — cyan core, teal-blue rim
        {{170, 200, 255}, { 60, 100, 230}},  // reflection — pale blue core, deep blue rim
        {{255, 200,  90}, {240, 120,  50}},  // S-II — gold core, orange rim
        {{200, 130, 255}, {110,  60, 220}},  // violet core, indigo rim
        {{140, 255, 150}, { 40, 180, 120}},  // green core, emerald rim
    };

    // Per-zone state — centres share the stars' rotated-coordinates convention
    private final double[] zcx = new double[ZONE_COUNT];
    private final double[] zcy = new double[ZONE_COUNT];
    private final double[] zcz = new double[ZONE_COUNT];
    private final double[] zoneRadius = new double[ZONE_COUNT];
    private final double[] zoneSpeed  = new double[ZONE_COUNT];
    private final int[]    zonePalette = new int[ZONE_COUNT];
    private long zoneSpawnCounter;

    // Per-puff parallel arrays (rotated view-space positions, like the stars)
    private final double[] px = new double[TOTAL_PUFFS];
    private final double[] py = new double[TOTAL_PUFFS];
    private final double[] pz = new double[TOTAL_PUFFS];
    private final float[]  puffSize  = new float[TOTAL_PUFFS];
    private final float[]  puffAlpha = new float[TOTAL_PUFFS];
    private final float[]  colIn  = new float[TOTAL_PUFFS * 3];
    private final float[]  colOut = new float[TOTAL_PUFFS * 3];
    private final float[]  noiseUvsc = new float[TOTAL_PUFFS * 4]; // u, v, scale, channel

    // Interleaved per-instance data, re-uploaded on each FBO refresh
    private final float[] puffData = new float[TOTAL_PUFFS * FLOATS_PER_PUFF];

    private int vao, instVbo;
    private int fbo, fboTexture, blitVao;
    private int fboWidth, fboHeight;
    private int noiseTexture;

    // FBO cache: the puff pass only reruns when the camera moved enough —
    // by rotation (rad) or by travel-induced screen motion (px)
    private double  rotSinceRefresh;
    private double  travelPxSinceRefresh;
    private int     framesSinceRefresh;
    private boolean cacheValid;

    private final CameraState camera;
    private final long   seed;
    private int    viewW, viewH;              // viewport size — follows resizes
    private int    cx, cy;                    // projection centre
    private double projScaleX, projScaleY;

    public NebulaFieldBehavior(int width, int height, CameraState camera, long seed) {
        this.camera = camera;
        this.seed   = seed;
        viewW = width;
        viewH = height;
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;

        for (int k = 0; k < ZONE_COUNT; k++) initZone(k, true);
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

        // Per-instance: position, size, alpha, colours, noise — dynamic
        instVbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, instVbo);
        glBufferData(GL_ARRAY_BUFFER, (long) puffData.length * Float.BYTES, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_PUFF * Float.BYTES;
        glEnableVertexAttribArray(1);                                  // position
        glVertexAttribPointer(1, 3, GL_FLOAT, false, stride, 0);
        glVertexAttribDivisor(1, 1);
        glEnableVertexAttribArray(2);                                  // world-space radius
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 12);
        glVertexAttribDivisor(2, 1);
        glEnableVertexAttribArray(3);                                  // alpha (with zone fade)
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 16);
        glVertexAttribDivisor(3, 1);
        glEnableVertexAttribArray(4);                                  // core colour
        glVertexAttribPointer(4, 3, GL_FLOAT, false, stride, 20);
        glVertexAttribDivisor(4, 1);
        glEnableVertexAttribArray(5);                                  // rim colour
        glVertexAttribPointer(5, 3, GL_FLOAT, false, stride, 32);
        glVertexAttribDivisor(5, 1);
        glEnableVertexAttribArray(6);                                  // noise uv/scale/channel
        glVertexAttribPointer(6, 4, GL_FLOAT, false, stride, 44);
        glVertexAttribDivisor(6, 1);
        glBindVertexArray(0);

        // Pre-baked fBm noise: 4 independent value-noise fields (R/G/B/A),
        // tileable, generated once from the master seed
        noiseTexture = createNoiseTexture(StarfieldBehavior.subSeed(seed, NOISE_SUBSEED_INDEX));

        // Half-resolution offscreen layer + quad for compositing
        fboWidth  = Math.max(1, viewW / FBO_SCALE);
        fboHeight = Math.max(1, viewH / FBO_SCALE);
        fboTexture = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fboWidth, fboHeight, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, fboTexture, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Nebula FBO incomplete");
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

    @Override
    public void resize(int width, int height) {
        viewW = width;
        viewH = height;
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;

        // Re-allocate the half-resolution layer storage at the new size and
        // force a re-render on the next draw
        fboWidth  = Math.max(1, width  / FBO_SCALE);
        fboHeight = Math.max(1, height / FBO_SCALE);
        glBindTexture(GL_TEXTURE_2D, fboTexture);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, fboWidth, fboHeight, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        cacheValid = false;
    }

    // --- Procedural generation -------------------------------------------

    /**
     * (Re)generates zone {@code k}: centre, palette, and its ellipsoidal puff
     * cluster. Every spawn draws from its own sub-seeded generator, so zone
     * spawn #n is identical across runs with the same master seed.
     */
    private void initZone(int k, boolean scatter) {
        Random gen = new Random(StarfieldBehavior.subSeed(
            seed, ZONE_SUBSEED_BASE - zoneSpawnCounter++));

        zcx[k] = (gen.nextDouble() * 2 - 1) * ZONE_XY;
        zcy[k] = (gen.nextDouble() * 2 - 1) * ZONE_XY;
        // On scatter: spread throughout depth; on respawn: push to far shell
        zcz[k] = scatter
            ? SCATTER_Z_MIN + gen.nextDouble() * (SCATTER_Z_MAX - SCATTER_Z_MIN)
            : RESPAWN_Z_MIN + gen.nextDouble() * (RESPAWN_Z_MAX - RESPAWN_Z_MIN);

        double radius = ZONE_RADIUS_MIN + gen.nextDouble() * (ZONE_RADIUS_MAX - ZONE_RADIUS_MIN);
        zoneRadius[k] = radius;
        zoneSpeed[k]  = ZONE_SPEED_MIN + gen.nextDouble() * (ZONE_SPEED_MAX - ZONE_SPEED_MIN);

        // Initial scatter: one palette per zone so all six types are visible.
        // Respawn: random pick avoiding the zone's previous palette.
        int palette;
        if (scatter) {
            palette = k % PALETTES.length;
        } else {
            palette = gen.nextInt(PALETTES.length);
            if (palette == zonePalette[k]) palette = (palette + 1) % PALETTES.length;
        }
        zonePalette[k] = palette;
        float[] core = PALETTES[palette][0];
        float[] rim  = PALETTES[palette][1];

        // Random orthonormal basis (u, v, w) + per-axis flattening → the
        // cluster is an arbitrarily oriented ellipsoid, never a plain sphere
        double[] w = randomDirection(gen);
        double[] u = tangentU(w);
        double[] v = cross(w, u);
        double flatV = 0.35 + gen.nextDouble() * 0.45;
        double flatW = 0.50 + gen.nextDouble() * 0.50;

        // Gaussian sub-clumps for an irregular, patchy structure
        int nClumps = 2 + gen.nextInt(2);
        double[][] clumps = new double[nClumps][3];
        for (double[] c : clumps) {
            c[0] = gen.nextGaussian() * radius * 0.7;
            c[1] = gen.nextGaussian() * radius * 0.7;
            c[2] = gen.nextGaussian() * radius * 0.7;
        }

        for (int j = 0; j < PUFFS_PER_ZONE; j++) {
            int i = k * PUFFS_PER_ZONE + j;

            double a, b, c;
            if (j % 3 == 0) {                                // clumped puff
                double[] cl = clumps[gen.nextInt(nClumps)];
                a = cl[0] + gen.nextGaussian() * radius * 0.30;
                b = cl[1] + gen.nextGaussian() * radius * 0.30;
                c = cl[2] + gen.nextGaussian() * radius * 0.30;
            } else {                                         // diffuse body
                a = gen.nextGaussian() * radius;
                b = gen.nextGaussian() * radius;
                c = gen.nextGaussian() * radius;
            }
            b *= flatV;
            c *= flatW;

            double ox = u[0] * a + v[0] * b + w[0] * c;
            double oy = u[1] * a + v[1] * b + w[1] * c;
            double oz = u[2] * a + v[2] * b + w[2] * c;
            px[i] = zcx[k] + ox;
            py[i] = zcy[k] + oy;
            pz[i] = zcz[k] + oz;

            boolean brightCore = j < BRIGHT_CORES;
            puffSize[i] = (float) (radius * (brightCore
                ? 0.15 + gen.nextDouble() * 0.15
                : 0.35 + gen.nextDouble() * 0.50));
            puffAlpha[i] = (float) (brightCore
                ? 0.28 + gen.nextDouble() * 0.17
                : 0.12 + gen.nextDouble() * 0.18);

            // Radial colour position: core near the centre, rim at the edge,
            // with per-puff jitter and brightness variation
            double dist = Math.sqrt(ox * ox + oy * oy + oz * oz) / radius;
            double t = Math.clamp(dist + (gen.nextDouble() - 0.5) * 0.4, 0.0, 1.0);
            float jitter = (float) (0.85 + gen.nextDouble() * 0.30);
            for (int ch = 0; ch < 3; ch++) {
                float in  = lerp(core[ch], rim[ch], (float) (0.7 * t)) / 255f;
                float out = lerp(core[ch], rim[ch], (float) Math.min(1.0, 0.7 * t + 0.4)) / 255f;
                if (brightCore) in = lerp(in, 1f, 0.4f);     // push toward white
                colIn[i * 3 + ch]  = Math.min(1f, in * jitter);
                colOut[i * 3 + ch] = Math.min(1f, out * 0.9f * jitter);
            }

            noiseUvsc[i * 4]     = gen.nextFloat();                  // uv offset
            noiseUvsc[i * 4 + 1] = gen.nextFloat();
            noiseUvsc[i * 4 + 2] = 0.5f + gen.nextFloat() * 0.7f;    // uv scale
            noiseUvsc[i * 4 + 3] = gen.nextInt(4);                   // channel
        }
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    // --- Small vector helpers ---------------------------------------------

    private static double[] randomDirection(Random gen) {
        double z   = gen.nextDouble() * 2 - 1;
        double phi = gen.nextDouble() * Math.PI * 2;
        double r   = Math.sqrt(1 - z * z);
        return new double[] {r * Math.cos(phi), r * Math.sin(phi), z};
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

    // --- Noise texture ------------------------------------------------------

    /**
     * Bakes 4 independent tileable value-noise fields (3 octaves each) into
     * one RGBA texture. Sampled once per fragment in {@code nebula.frag} —
     * far cheaper than procedural fBm under a software rasteriser.
     */
    private static int createNoiseTexture(long noiseSeed) {
        Random gen = new Random(noiseSeed);
        float[][] fields = new float[4][NOISE_SIZE * NOISE_SIZE];

        for (float[] field : fields) {
            float ampSum = 0;
            for (int octave = 0; octave < 3; octave++) {
                int   freq = 8 << octave;                    // 8, 16, 32 cells
                float amp  = 1f / (1 << octave);             // 1, 0.5, 0.25
                ampSum += amp;
                float[] grid = new float[freq * freq];
                for (int g = 0; g < grid.length; g++) grid[g] = gen.nextFloat();

                for (int yPix = 0; yPix < NOISE_SIZE; yPix++) {
                    double gy = (double) yPix * freq / NOISE_SIZE;
                    int    y0 = (int) gy, y1 = (y0 + 1) % freq;
                    double fy = smooth(gy - y0);
                    for (int xPix = 0; xPix < NOISE_SIZE; xPix++) {
                        double gx = (double) xPix * freq / NOISE_SIZE;
                        int    x0 = (int) gx, x1 = (x0 + 1) % freq;
                        double fx = smooth(gx - x0);
                        double top = grid[y0 * freq + x0] * (1 - fx) + grid[y0 * freq + x1] * fx;
                        double bot = grid[y1 * freq + x0] * (1 - fx) + grid[y1 * freq + x1] * fx;
                        field[yPix * NOISE_SIZE + xPix] += (float) ((top * (1 - fy) + bot * fy) * amp);
                    }
                }
            }
            for (int p = 0; p < field.length; p++) field[p] /= ampSum;
        }

        ByteBuffer buf = MemoryUtil.memAlloc(NOISE_SIZE * NOISE_SIZE * 4);
        for (int p = 0; p < NOISE_SIZE * NOISE_SIZE; p++) {
            for (int ch = 0; ch < 4; ch++) {
                buf.put((byte) (int) (fields[ch][p] * 255));
            }
        }
        buf.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, NOISE_SIZE, NOISE_SIZE, 0,
                     GL_RGBA, GL_UNSIGNED_BYTE, buf);
        MemoryUtil.memFree(buf);
        return tex;
    }

    private static double smooth(double t) {
        return t * t * (3 - 2 * t);   // smoothstep interpolant
    }

    // --- Behavior ---------------------------------------------------------

    @Override
    public void update(Entity entity, double dt) {
        // Same incremental yaw→pitch→roll as the stars, applied to every puff
        // and zone centre (positions are stored rotated)
        double cosY = camera.cosYaw,   sinY = camera.sinYaw;
        double cosP = camera.cosPitch, sinP = camera.sinPitch;
        double cosR = camera.cosRoll,  sinR = camera.sinRoll;

        double maxTravelPx = 0;
        double travelFactor = camera.travelFactor();

        for (int k = 0; k < ZONE_COUNT; k++) {
            double x = zcx[k], y = zcy[k], z = zcz[k];
            double tx = x * cosY + z * sinY;
            double tz = -x * sinY + z * cosY;
            x = tx; z = tz;
            double ty = y * cosP - z * sinP;
            tz = y * sinP + z * cosP;
            y = ty; z = tz;
            tx = x * cosR - y * sinR;
            ty = x * sinR + y * cosR;
            zcx[k] = tx; zcy[k] = ty; zcz[k] = z;

            // Rigid travel: one Δz from the zone centre, applied to the whole
            // cluster so the nebula keeps its shape while flying by
            double dz = TRAVEL_SPEED * zoneSpeed[k] * travelFactor / Math.max(z, 0.05) * dt;
            zcz[k] = z - dz;

            for (int j = 0; j < PUFFS_PER_ZONE; j++) {
                int i = k * PUFFS_PER_ZONE + j;
                x = px[i]; y = py[i]; z = pz[i];
                tx = x * cosY + z * sinY;
                tz = -x * sinY + z * cosY;
                x = tx; z = tz;
                ty = y * cosP - z * sinP;
                tz = y * sinP + z * cosP;
                y = ty; z = tz;
                tx = x * cosR - y * sinR;
                ty = x * sinR + y * cosR;
                px[i] = tx; py[i] = ty; pz[i] = z - dz;
            }

            // Screen-space motion caused by travel (for cache invalidation)
            if (zcz[k] > MIN_Z) {
                double motion = (Math.abs(zcx[k]) + zoneRadius[k])
                              / (zcz[k] * zcz[k]) * dz * projScaleX;
                maxTravelPx = Math.max(maxTravelPx, motion);
            }

            // Zone flew past the fade-out point (already invisible) → respawn
            if (zcz[k] <= FADE_NEAR_END) initZone(k, false);
        }

        rotSinceRefresh += Math.abs(camera.frameYaw) + Math.abs(camera.framePitch)
                         + Math.abs(camera.frameRoll);
        travelPxSinceRefresh += maxTravelPx;
        framesSinceRefresh++;
    }

    /** Fade to zero near the camera (before respawn) and at the far shell. */
    private static float zoneFade(double z) {
        double nearFade = smoothstep(FADE_NEAR_END, FADE_NEAR_START, z);
        double farFade  = 1.0 - smoothstep(FADE_FAR_START, FADE_FAR_END, z);
        return (float) (nearFade * farFade);
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.clamp((x - e0) / (e1 - e0), 0.0, 1.0);
        return t * t * (3 - 2 * t);
    }

    @Override
    public void draw(Entity entity, RenderContext ctx) {
        // Fill the instance data (alpha baked with the zone fade) and compute
        // the exact screen bounding box of the visible puffs in the same pass
        int panelW = viewW, panelH = viewH;
        int bbMinX = panelW, bbMinY = panelH, bbMaxX = 0, bbMaxY = 0;

        for (int k = 0; k < ZONE_COUNT; k++) {
            float fade = zoneFade(zcz[k]);
            for (int j = 0; j < PUFFS_PER_ZONE; j++) {
                int i = k * PUFFS_PER_ZONE + j;
                int o = i * FLOATS_PER_PUFF;
                float alpha = puffAlpha[i] * fade;
                puffData[o]     = (float) px[i];
                puffData[o + 1] = (float) py[i];
                puffData[o + 2] = (float) pz[i];
                puffData[o + 3] = puffSize[i];
                puffData[o + 4] = alpha;
                puffData[o + 5] = colIn[i * 3];
                puffData[o + 6] = colIn[i * 3 + 1];
                puffData[o + 7] = colIn[i * 3 + 2];
                puffData[o + 8]  = colOut[i * 3];
                puffData[o + 9]  = colOut[i * 3 + 1];
                puffData[o + 10] = colOut[i * 3 + 2];
                puffData[o + 11] = noiseUvsc[i * 4];
                puffData[o + 12] = noiseUvsc[i * 4 + 1];
                puffData[o + 13] = noiseUvsc[i * 4 + 2];
                puffData[o + 14] = noiseUvsc[i * 4 + 3];

                if (alpha <= 0.001f || pz[i] < MIN_Z) continue;
                double sxp = cx + px[i] / pz[i] * projScaleX;
                double syp = cy + py[i] / pz[i] * projScaleY;
                double r = Math.min(puffSize[i] / pz[i] * projScaleX, MAX_RADIUS)
                         + BBOX_MARGIN;
                bbMinX = Math.min(bbMinX, (int) (sxp - r));
                bbMinY = Math.min(bbMinY, (int) (syp - r));
                bbMaxX = Math.max(bbMaxX, (int) (sxp + r));
                bbMaxY = Math.max(bbMaxY, (int) (syp + r));
            }
        }
        bbMinX = Math.max(0, bbMinX);      bbMinY = Math.max(0, bbMinY);
        bbMaxX = Math.min(panelW, bbMaxX); bbMaxY = Math.min(panelH, bbMaxY);
        if (bbMaxX <= bbMinX || bbMaxY <= bbMinY) return;   // nothing visible

        // Pass 1 — accumulate the puffs in the half-resolution FBO
        // (premultiplied alpha, hence the GL_ONE source factor). Cached:
        // reruns only when the accumulated rotation or travel reaches a
        // pixel's worth of screen motion.
        if (!cacheValid
            || ((rotSinceRefresh > REFRESH_ROT_EPS
                 || travelPxSinceRefresh > REFRESH_TRAVEL_PX)
                && framesSinceRefresh >= REFRESH_MIN_FRAMES)) {
            glBindFramebuffer(GL_FRAMEBUFFER, fbo);
            glViewport(0, 0, fboWidth, fboHeight);
            glClearColor(0f, 0f, 0f, 0f);
            glClear(GL_COLOR_BUFFER_BIT);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            ShaderProgram shader = ctx.nebulaShader;
            shader.use();
            shader.setVec2("uViewport", fboWidth, fboHeight);
            shader.setVec2("uProjScale",
                (float) projScaleX / FBO_SCALE, (float) projScaleY / FBO_SCALE);
            shader.setFloat("uMinZ", MIN_Z);
            shader.setFloat("uMaxRadius", MAX_RADIUS / FBO_SCALE);
            shader.setInt("uNoise", 0);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, noiseTexture);

            glBindBuffer(GL_ARRAY_BUFFER, instVbo);
            glBufferSubData(GL_ARRAY_BUFFER, 0, puffData);
            glBindVertexArray(vao);
            glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, TOTAL_PUFFS);
            glBindVertexArray(0);

            glBindFramebuffer(GL_FRAMEBUFFER, 0);
            glViewport(0, 0, panelW, panelH);
            glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
            glClearColor(0f, 0f, 0f, 1f);
            rotSinceRefresh      = 0;
            travelPxSinceRefresh = 0;
            framesSinceRefresh   = 0;
            cacheValid           = true;
        }

        // Pass 2 — composite the cached layer over the scene, restricted to
        // the content bounding box. The nebulae are the first thing drawn
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
