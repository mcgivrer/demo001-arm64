import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

/**
 * Background layer: two irregular dwarf-galaxy clouds inspired by the Large
 * and Small Magellanic Clouds, joined by a faint bridge and sprinkled with
 * pinkish HII regions. Each cloud is a set of soft "puffs" — unit direction
 * vectors on the celestial sphere — generated once from the master SEED.
 * The puffs sit at infinity: they rotate with the camera (via the shared
 * {@link CameraState}) but never translate with forward travel, so the
 * clouds form a stable backdrop behind the starfield.
 *
 * <p>Rendering optimisations:
 * <ul>
 * <li>Each puff's translucency is <b>pre-baked</b> into a small sprite
 *     variant (3 tints × 8 quantised alpha levels, premultiplied ARGB) — the
 *     puff pass is plain scaled {@code drawImage} calls, no per-puff
 *     {@code AlphaComposite} (Java2D's slow general compositing path).</li>
 * <li>The composed layer is <b>cached</b> in a half-resolution off-screen
 *     buffer. The overlapping-puff overdraw (up to ~12 ms/frame when a cloud
 *     fills the view) is only paid when the camera has rotated enough since
 *     the last refresh; every other frame is a single scaled blit. Half
 *     resolution is invisible on such soft gradients and quarters the
 *     refresh cost.</li>
 * </ul>
 */
public class MagellanicCloudsBehavior implements Behavior {

    private static final long   CLOUD_SUBSEED_INDEX = -1;   // spawnCounter is ≥ 0 → never collides
    private static final int    LMC_PUFFS    = 120;
    private static final int    SMC_PUFFS    = 70;
    private static final int    BRIDGE_PUFFS = 30;
    private static final int    HII_PUFFS    = 10;

    private static final double LMC_RADIUS   = 0.22;  // angular radius (rad)
    private static final double SMC_RADIUS   = 0.13;
    private static final double PAIR_ANGLE   = 0.60;  // angular separation LMC ↔ SMC (rad)

    private static final double MIN_Z        = 0.15;  // cull puffs close to / behind the view plane
    private static final int    SPRITE_SIZE  = 32;    // base soft-sprite resolution (px)
    private static final float  MAX_RADIUS   = 220f;  // projected puff radius cap (px)

    private static final int    CACHE_SCALE        = 2;      // off-screen buffer at panel/2
    private static final double REFRESH_ROT_EPS    = 0.002;  // rad — ≈ 0.7 px of screen motion
    private static final int    REFRESH_MIN_FRAMES = 4;      // amortises worst-case refresh cost

    // Quantised translucency levels covering body (0.03–0.11) and HII (0.16–0.30)
    private static final float[] ALPHA_LEVELS = {
        0.03f, 0.05f, 0.07f, 0.09f, 0.11f, 0.16f, 0.23f, 0.30f
    };

    private static final Color[] TINTS = {
        new Color(205, 218, 255),   // bluish white — cloud body
        new Color(255, 240, 224),   // warm white — old stellar population
        new Color(255, 170, 195),   // pink — HII star-forming regions
    };

    // Puff data — unit direction vectors + appearance
    private final double[] vx, vy, vz;
    private final float[]  size;      // angular size (rad)
    private final int[]    variant;   // index into sprites[]: tint × alpha level

    // Pre-rendered sprites, one per (tint, alpha level) — alpha baked in
    private final BufferedImage[] sprites;

    private final CameraState camera;
    private final int    cx, cy;
    private final double projScaleX, projScaleY;

    // Half-resolution screen cache of the composed layer
    private final BufferedImage cache;
    private double  rotSinceRefresh;   // accumulated |yaw|+|pitch|+|roll| since last refresh
    private int     framesSinceRefresh;
    private boolean cacheValid;
    // Content bounding box inside the cache (cache pixels) — limits the blit
    private int bbMinX, bbMinY, bbMaxX, bbMaxY;

    public MagellanicCloudsBehavior(int width, int height, CameraState camera, long seed) {
        this.camera = camera;
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;
        cache = new BufferedImage(Math.max(1, width / CACHE_SCALE),
                                  Math.max(1, height / CACHE_SCALE),
                                  BufferedImage.TYPE_INT_ARGB_PRE);

        sprites = new BufferedImage[TINTS.length * ALPHA_LEVELS.length];
        for (int t = 0; t < TINTS.length; t++) {
            for (int l = 0; l < ALPHA_LEVELS.length; l++) {
                sprites[t * ALPHA_LEVELS.length + l] = makeSprite(TINTS[t], ALPHA_LEVELS[l]);
            }
        }

        int total = LMC_PUFFS + SMC_PUFFS + BRIDGE_PUFFS + HII_PUFFS;
        vx      = new double[total];
        vy      = new double[total];
        vz      = new double[total];
        size    = new float[total];
        variant = new int[total];

        Random gen = new Random(StarfieldBehavior.subSeed(seed, CLOUD_SUBSEED_INDEX));
        generate(gen);
    }

    /**
     * Soft round sprite: tinted centre fading to a fully transparent edge,
     * with the puff's translucency pre-multiplied into the pixels.
     */
    private static BufferedImage makeSprite(Color c, float alpha) {
        BufferedImage img = new BufferedImage(SPRITE_SIZE, SPRITE_SIZE,
                                              BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g = img.createGraphics();
        float half = SPRITE_SIZE / 2f;
        g.setPaint(new RadialGradientPaint(
            half, half, half,
            new float[] {0f, 0.45f, 1f},
            new Color[] {
                new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (255 * alpha)),
                new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (110 * alpha)),
                new Color(c.getRed(), c.getGreen(), c.getBlue(), 0),
            }));
        g.fillRect(0, 0, SPRITE_SIZE, SPRITE_SIZE);
        g.dispose();
        return img;
    }

    /** Maps a continuous translucency to the nearest pre-baked sprite variant. */
    private static int variantOf(int tint, float alpha) {
        int best = 0;
        for (int l = 1; l < ALPHA_LEVELS.length; l++) {
            if (Math.abs(ALPHA_LEVELS[l] - alpha) < Math.abs(ALPHA_LEVELS[best] - alpha)) {
                best = l;
            }
        }
        return tint * ALPHA_LEVELS.length + best;
    }

    // --- Procedural generation -------------------------------------------

    private void generate(Random gen) {
        // LMC centre: biased toward the initial forward hemisphere so the main
        // cloud is visible at startup (z ∈ [0.55, 0.9])
        double[] lmc = randomDirection(gen, 0.55, 0.90);
        // SMC: fixed angular distance from the LMC, random azimuth around it
        double[] smc = rotateAround(lmc, gen.nextDouble() * Math.PI * 2, PAIR_ANGLE);

        int i = 0;
        i = scatterCloud(gen, i, lmc, LMC_PUFFS, LMC_RADIUS, 0.55,
                         0.10f, 0.060f, 0.110f);
        i = scatterCloud(gen, i, smc, SMC_PUFFS, SMC_RADIUS, 0.70,
                         0.075f, 0.055f, 0.100f);
        i = scatterBridge(gen, i, lmc, smc, BRIDGE_PUFFS);
        scatterHii(gen, i, lmc, smc);
    }

    /**
     * Scatters {@code count} puffs around {@code center} with an anisotropic
     * (elongated) Gaussian in the tangent plane; ~1/3 of the puffs cluster
     * around sub-clumps for an irregular, patchy structure.
     */
    private int scatterCloud(Random gen, int i, double[] center, int count,
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

        for (int k = 0; k < count; k++, i++) {
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
            setPuff(i, offset(center, u, v, oa, ob),
                    puffSize * (0.6f + gen.nextFloat() * 0.9f),
                    alphaMin + gen.nextFloat() * (alphaMax - alphaMin),
                    gen.nextFloat() < 0.75f ? 0 : 1);
        }
        return i;
    }

    /** Faint chain of puffs along the great-circle arc joining the two clouds. */
    private int scatterBridge(Random gen, int i, double[] from, double[] to, int count) {
        for (int k = 0; k < count; k++, i++) {
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
            setPuff(i, p,
                    0.05f + gen.nextFloat() * 0.04f,
                    0.030f + gen.nextFloat() * 0.030f,
                    0);
        }
        return i;
    }

    /** Small bright pink HII regions inside the two clouds (Tarantula-like). */
    private void scatterHii(Random gen, int i, double[] lmc, double[] smc) {
        for (int k = 0; k < HII_PUFFS; k++, i++) {
            double[] center = k < 7 ? lmc : smc;
            double radius   = (k < 7 ? LMC_RADIUS : SMC_RADIUS) * 0.6;
            double[] u = tangentU(center);
            double[] v = cross(center, u);
            double[] p = offset(center, u, v,
                                gen.nextGaussian() * radius,
                                gen.nextGaussian() * radius);
            setPuff(i, p,
                    0.020f + gen.nextFloat() * 0.020f,
                    0.16f + gen.nextFloat() * 0.14f,
                    2);
        }
    }

    private void setPuff(int i, double[] dir, float s, float a, int t) {
        vx[i] = dir[0]; vy[i] = dir[1]; vz[i] = dir[2];
        size[i]    = s;
        variant[i] = variantOf(t, a);
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
        // Same frame rotation as the starfield — no forward travel: the
        // clouds are at infinity and only rotate with the camera.
        double cosY = camera.cosYaw,   sinY = camera.sinYaw;
        double cosP = camera.cosPitch, sinP = camera.sinPitch;
        double cosR = camera.cosRoll,  sinR = camera.sinRoll;

        for (int i = 0; i < vx.length; i++) {
            double x = vx[i], y = vy[i], z = vz[i];

            double tx = x * cosY + z * sinY;                // yaw
            double tz = -x * sinY + z * cosY;
            x = tx; z = tz;

            double ty = y * cosP - z * sinP;                // pitch
            tz = y * sinP + z * cosP;
            y = ty; z = tz;

            tx = x * cosR - y * sinR;                       // roll
            ty = x * sinR + y * cosR;
            x = tx; y = ty;

            vx[i] = x; vy[i] = y; vz[i] = z;
        }

        rotSinceRefresh += Math.abs(camera.frameYaw) + Math.abs(camera.framePitch)
                         + Math.abs(camera.frameRoll);
        framesSinceRefresh++;
    }

    @Override
    public void draw(Entity entity, Graphics2D g) {
        if (!cacheValid
            || (rotSinceRefresh > REFRESH_ROT_EPS && framesSinceRefresh >= REFRESH_MIN_FRAMES)) {
            refreshCache();
        }
        if (bbMaxX <= bbMinX || bbMaxY <= bbMinY) return;   // nothing visible

        // Single scaled blit of the content bounding box — bounded per-frame cost
        g.drawImage(cache,
                    bbMinX * CACHE_SCALE, bbMinY * CACHE_SCALE,
                    bbMaxX * CACHE_SCALE, bbMaxY * CACHE_SCALE,
                    bbMinX, bbMinY, bbMaxX, bbMaxY, null);
    }

    /** Re-composes all visible puffs into the half-resolution cache. */
    private void refreshCache() {
        int cw = cache.getWidth(), ch = cache.getHeight();
        double ccx = cw / 2.0, ccy = ch / 2.0;
        double scaleX = projScaleX / CACHE_SCALE, scaleY = projScaleY / CACHE_SCALE;
        int minX = cw, minY = ch, maxX = 0, maxY = 0;

        Graphics2D g = cache.createGraphics();
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, cw, ch);
        g.setComposite(AlphaComposite.SrcOver);

        for (int i = 0; i < vx.length; i++) {
            double z = vz[i];
            if (z < MIN_Z) continue;

            double px = ccx + vx[i] / z * scaleX;
            double py = ccy + vy[i] / z * scaleY;
            float  r  = Math.min((float) (size[i] / z * scaleX), MAX_RADIUS / CACHE_SCALE);

            if (px + r < 0 || px - r > cw || py + r < 0 || py - r > ch) continue;

            int x0 = (int) (px - r), y0 = (int) (py - r);
            int d  = (int) (r * 2);
            // Plain scaled blit — the puff's alpha is baked into the sprite
            g.drawImage(sprites[variant[i]], x0, y0, d, d, null);

            minX = Math.min(minX, x0);      minY = Math.min(minY, y0);
            maxX = Math.max(maxX, x0 + d);  maxY = Math.max(maxY, y0 + d);
        }
        g.dispose();

        bbMinX = Math.max(0, minX);  bbMinY = Math.max(0, minY);
        bbMaxX = Math.min(cw, maxX); bbMaxY = Math.min(ch, maxY);
        rotSinceRefresh    = 0;
        framesSinceRefresh = 0;
        cacheValid         = true;
    }
}
