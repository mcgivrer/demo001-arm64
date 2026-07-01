import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Random;

public class StarfieldBehavior implements Behavior {

    private static final int    STAR_COUNT   = 500;
    private static final double RANGE        = 2.0;
    private static final double NEAR_Z       = 0.06;
    private static final double TRAVEL_SPEED = 0.20;  // base forward speed (units/s at z=1)

    // Star sprites: filling per-star Ellipse2D shapes costs ~25 ms/frame in
    // software rendering (generic shape rasterisation); pre-rendered sprite
    // blits bring the whole starfield under ~2 ms. Core + glow live in the
    // same radial gradient, one variant per (spectral type, alpha level).
    private static final int   STAR_SPRITE_SIZE  = 40;   // sprite resolution (px)
    private static final int   STAR_ALPHA_LEVELS = 16;   // quantised brightness levels
    private static final float GLOW_FACTOR       = 2.5f; // glow radius / core radius

    private static final double NAME_PROBABILITY = 0.25; // fraction of stars that get a name
    private static final double NAME_Z_THRESHOLD = 1.00; // label fades in below this depth
    private static final double NAME_FULL_Z      = 0.50; // label fully opaque at this depth
    private static final float  NAME_FONT_SIZE   = 9f;
    private static final int    NAME_PAD_X       = 4;    // label horizontal padding (px)
    private static final int    NAME_PAD_Y       = 2;    // label vertical padding (px)

    private static final double THRUST_RATE     = 0.45; // engine power change (1/s) while CTRL/SHIFT held
    private static final double MIN_THRUST      = 0.0;
    private static final double MAX_THRUST      = 1.0;
    private static final double CRUISE_THRUST   = 0.5;  // speed reference — matches the original fixed TRAVEL_SPEED
    private static final double INITIAL_THRUST  = 0.15; // gentle start, leaves time to read star names
    private static final double MAX_SPEED_PARSEC = 70.0; // fictional top speed, displayed at full thrust

    private static final int    GAUGE_X      = 20;
    private static final int    GAUGE_WIDTH  = 14;
    private static final int    GAUGE_HEIGHT = 100;
    private static final int    GAUGE_MARGIN = 64; // distance from panel bottom edge

    private static final int    FPS_X         = 10;  // FPS readout, top-left corner
    private static final int    FPS_Y         = 40;
    private static final float  FPS_FONT_SIZE = 9f;
    private static final double FPS_PERIOD    = 0.5; // refresh interval (s)

    private static final int    HELP_MARGIN  = 60; // distance from panel right/bottom edges
    private static final int    HELP_WIDTH   = 250;
    private static final int    HELP_ROW_H   = 16;
    private static final int    HELP_KEY_COL = 110; // x-offset of the action column, from the panel's left edge

    // {key label, action description}
    private static final String[][] HELP_ROWS = {
        {"←→ / A D",     "Yaw"},
        {"↑↓ / W S",     "Pitch"},
        {"Q / E",                  "Roll"},
        {"SPACE",                  "Frein"},
        {"Clic + glisser",         "Joystick"},
        {"CTRL / SHIFT",           "Thrust +/-"},
        {"ESCAPE",                 "Quitter"},
        {"H",                      "Afficher/masquer l'aide"},
    };

    // Parallel arrays — one slot per star
    private final double[] sx, sy, sz;
    private final double[] travelSpeed;   // per-star speed multiplier (parallax depth)
    private final int[]    spectralIdx;   // index into SPECTRAL_TYPES / sprite tints
    private final float[]  brightness, baseSize;
    private final String[] starName;      // procedural name, or null for anonymous stars

    // Pre-rendered star sprites [spectral type × alpha level] and the matching
    // quantised colours for the sub-pixel fillRect path
    private final BufferedImage[] starSprites;
    private final Color[]         subPixelColors;

    // Engine power throttle, 0 (idle) .. 1 (full thrust)
    private double enginePower = INITIAL_THRUST;

    // FPS counter — frames accumulated over FPS_PERIOD, then averaged
    private double fpsTimer;
    private int    fpsFrames;
    private int    fps;

    private final int       cx, cy;
    private final double    projScaleX, projScaleY;
    private final long      seed;         // master seed — the whole starfield derives from it
    private long            spawnCounter; // total stars generated; sub-seed index
    private final CameraState camera;     // shared frame rotation (yaw/pitch/roll)
    private final InputState input;

    // Harvard spectral classification: {cumProb, R, G, B, minBrightness, baseSizePx}
    private static final double[][] SPECTRAL_TYPES = {
        {0.760, 255, 140,  80, 0.35, 1.1},  // M — red dwarf      (76 %, most common)
        {0.880, 255, 185, 110, 0.50, 1.5},  // K — orange          (12 %)
        {0.956, 255, 235, 195, 0.65, 2.0},  // G — yellow, Sun     ( 7.6 %)
        {0.986, 245, 245, 255, 0.75, 2.4},  // F — yellow-white    ( 3.0 %)
        {0.992, 200, 215, 255, 0.85, 2.9},  // A — white           ( 0.6 %)
        {0.993, 170, 191, 255, 0.93, 3.6},  // B — blue-white      ( 0.1 %)
        {1.000, 155, 176, 255, 1.00, 5.0},  // O — blue giant      (rarest)
    };

    public StarfieldBehavior(int width, int height, InputState input,
                             CameraState camera, long seed) {
        this.input  = input;
        this.camera = camera;
        this.seed   = seed;
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;

        sx          = new double[STAR_COUNT];
        sy          = new double[STAR_COUNT];
        sz          = new double[STAR_COUNT];
        travelSpeed = new double[STAR_COUNT];
        spectralIdx = new int[STAR_COUNT];
        brightness  = new float[STAR_COUNT];
        baseSize    = new float[STAR_COUNT];
        starName    = new String[STAR_COUNT];

        starSprites    = new BufferedImage[SPECTRAL_TYPES.length * STAR_ALPHA_LEVELS];
        subPixelColors = new Color[SPECTRAL_TYPES.length * STAR_ALPHA_LEVELS];
        for (int t = 0; t < SPECTRAL_TYPES.length; t++) {
            double[] spec = SPECTRAL_TYPES[t];
            for (int l = 0; l < STAR_ALPHA_LEVELS; l++) {
                float alpha = (l + 1f) / STAR_ALPHA_LEVELS;
                int idx = t * STAR_ALPHA_LEVELS + l;
                starSprites[idx] = makeStarSprite(
                    (int) spec[1], (int) spec[2], (int) spec[3], alpha);
                subPixelColors[idx] = new Color(
                    (int) spec[1], (int) spec[2], (int) spec[3], (int) (alpha * 255));
            }
        }

        for (int i = 0; i < STAR_COUNT; i++) initStar(i, true);
    }

    /**
     * Radial star sprite: hard bright core in the inner 1/GLOW_FACTOR of the
     * radius, then a fast falloff into a faint diffuse glow. The brightness
     * level is baked into the pixels (premultiplied ARGB) so drawing needs no
     * per-star AlphaComposite.
     */
    private static BufferedImage makeStarSprite(int r, int g, int b, float alpha) {
        BufferedImage img = new BufferedImage(STAR_SPRITE_SIZE, STAR_SPRITE_SIZE,
                                              BufferedImage.TYPE_INT_ARGB_PRE);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                            RenderingHints.VALUE_ANTIALIAS_ON);
        float half = STAR_SPRITE_SIZE / 2f;
        float core = 1f / GLOW_FACTOR;   // core/glow boundary, fraction of radius
        g2.setPaint(new RadialGradientPaint(
            half, half, half,
            new float[] {0f, core * 0.9f, core * 1.15f, 1f},
            new Color[] {
                new Color(r, g, b, (int) (255 * alpha)),
                new Color(r, g, b, (int) (255 * alpha)),
                new Color(r, g, b, (int) (60 * alpha)),
                new Color(r, g, b, 0),
            }));
        g2.fillRect(0, 0, STAR_SPRITE_SIZE, STAR_SPRITE_SIZE);
        g2.dispose();
        return img;
    }

    /**
     * SplitMix64 finalizer — decorrelates consecutive spawn indices so each
     * star gets an independent, reproducible sub-seed from the master seed.
     */
    static long subSeed(long seed, long n) {
        long z = seed + n * 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    private void initStar(int i, boolean scatter) {
        // Every spawned star draws all its properties (and name) from its own
        // sub-seeded generator: star #n is identical across runs with the same seed.
        Random gen = new Random(subSeed(seed, spawnCounter++));

        sx[i] = (gen.nextDouble() * 2 - 1) * RANGE;
        sy[i] = (gen.nextDouble() * 2 - 1) * RANGE;
        // On scatter: spread throughout depth; on respawn: push to far shell
        sz[i] = scatter
            ? NEAR_Z + gen.nextDouble() * (RANGE - NEAR_Z)
            : RANGE * (0.7 + gen.nextDouble() * 0.3);

        double r = gen.nextDouble();
        int specI = SPECTRAL_TYPES.length - 1;
        for (int s = 0; s < SPECTRAL_TYPES.length; s++) {
            if (r < SPECTRAL_TYPES[s][0]) { specI = s; break; }
        }
        double[] spec  = SPECTRAL_TYPES[specI];
        spectralIdx[i] = specI;
        brightness[i]  = (float) (spec[4] + gen.nextDouble() * (1.0 - spec[4]));
        baseSize[i]    = (float) (spec[5]  * (0.7 + gen.nextDouble() * 0.6));
        // Speed multiplier: range 0.4–1.6, independent of position → true parallax
        travelSpeed[i] = 0.4 + gen.nextDouble() * 1.2;

        starName[i] = gen.nextDouble() < NAME_PROBABILITY
            ? StarNameGenerator.generate(gen)
            : null;
    }

    @Override
    public void update(Entity entity, double dt) {
        if (input.thrustUp)   enginePower += THRUST_RATE * dt;
        if (input.thrustDown) enginePower -= THRUST_RATE * dt;
        enginePower = Math.clamp(enginePower, MIN_THRUST, MAX_THRUST);

        fpsTimer += dt;
        fpsFrames++;
        if (fpsTimer >= FPS_PERIOD) {
            fps = (int) Math.round(fpsFrames / fpsTimer);
            fpsTimer  = 0;
            fpsFrames = 0;
        }

        // Frame rotation computed once by CameraState (shared with the cloud layer)
        double cosY = camera.cosYaw,   sinY = camera.sinYaw;
        double cosP = camera.cosPitch, sinP = camera.sinPitch;
        double cosR = camera.cosRoll,  sinR = camera.sinRoll;

        for (int i = 0; i < STAR_COUNT; i++) {
            double x = sx[i], y = sy[i], z = sz[i];

            // Yaw — rotation around Y axis
            double tx = x * cosY + z * sinY;
            double tz = -x * sinY + z * cosY;
            x = tx; z = tz;

            // Pitch — rotation around X axis
            double ty = y * cosP - z * sinP;
            tz = y * sinP + z * cosP;
            y = ty; z = tz;

            // Roll — rotation around Z axis
            tx = x * cosR - y * sinR;
            ty = x * sinR + y * cosR;
            x = tx; y = ty;

            sx[i] = x; sy[i] = y;
            // Forward travel: speed ∝ 1/z → slow far away, fast when close (parallax + warp)
            // Scaled by engine thrust: enginePower=0 → stopped, =CRUISE_THRUST → original speed, =1 → 2×
            z -= TRAVEL_SPEED * travelSpeed[i] * (enginePower / CRUISE_THRUST) / z * dt;
            sz[i] = z;

            // Star passed in front of (or too close to) viewer → respawn at far depth
            if (z <= NEAR_Z) initStar(i, false);
        }
    }

    @Override
    public void draw(Entity entity, Graphics2D g) {
        for (int i = 0; i < STAR_COUNT; i++) {
            double z = sz[i];
            if (z <= NEAR_Z) continue;

            double px = cx + sx[i] / z * projScaleX;
            double py = cy + sy[i] / z * projScaleY;

            // Cull off-screen stars (keep a small margin for glow bleed)
            if (px < -20 || px > cx * 2 + 20 || py < -20 || py > cy * 2 + 20) continue;

            // Apparent brightness — inverse-square law (reference distance = 1)
            float ab = Math.clamp((float) (brightness[i] / (z * z)), 0f, 1f);
            if (ab * 255 < 8) continue;

            // Perspective-scaled radius
            float r = Math.clamp((float) (baseSize[i] / z), 0.4f, 8f);

            int level   = Math.min(STAR_ALPHA_LEVELS - 1, (int) (ab * STAR_ALPHA_LEVELS));
            int variant = spectralIdx[i] * STAR_ALPHA_LEVELS + level;

            if (r < 1.0f) {
                // Sub-pixel star: single bright pixel
                g.setColor(subPixelColors[variant]);
                g.fillRect((int) px, (int) py, 1, 1);
            } else {
                // One sprite blit renders core + diffuse glow together
                float gr = r * GLOW_FACTOR;
                g.drawImage(starSprites[variant],
                            (int) (px - gr), (int) (py - gr),
                            (int) (gr * 2), (int) (gr * 2), null);
            }
        }

        drawStarLabels(g);
        drawFpsHud(g);
        drawThrustHud(g);
        if (input.showHelp) drawControlsHelp(g);
    }

    private void drawFpsHud(Graphics2D g) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, FPS_FONT_SIZE));
        g.setColor(Color.WHITE);
        g.drawString(fps + " FPS", FPS_X, FPS_Y);
    }

    /**
     * Second pass over the stars: named stars close enough to the viewer get
     * a label that fades in on approach — 9 pt white text inside a dark-grey
     * frame over a translucent dark-grey background.
     */
    private void drawStarLabels(Graphics2D g) {
        g.setFont(g.getFont().deriveFont(Font.PLAIN, NAME_FONT_SIZE));
        FontMetrics fm = g.getFontMetrics();

        for (int i = 0; i < STAR_COUNT; i++) {
            String name = starName[i];
            double z    = sz[i];
            if (name == null || z <= NEAR_Z || z >= NAME_Z_THRESHOLD) continue;

            double px = cx + sx[i] / z * projScaleX;
            double py = cy + sy[i] / z * projScaleY;
            if (px < 0 || px > cx * 2 || py < 0 || py > cy * 2) continue;

            // Fade in as the star closes in on the viewer; fully opaque from NAME_FULL_Z
            float fade = Math.clamp(
                (float) ((NAME_Z_THRESHOLD - z) / (NAME_Z_THRESHOLD - NAME_FULL_Z)), 0f, 1f);

            float r  = Math.clamp((float) (baseSize[i] / z), 0.4f, 8f);
            int   tw = fm.stringWidth(name);
            int   th = fm.getAscent() + fm.getDescent();
            int   lx = (int) (px + r + 8);
            int   ly = (int) (py - th / 2.0 - NAME_PAD_Y);
            int   boxW = tw + NAME_PAD_X * 2;
            int   boxH = th + NAME_PAD_Y * 2;

            // Translucent dark-grey background
            g.setColor(new Color(38, 42, 48, (int) (fade * 150)));
            g.fillRect(lx, ly, boxW, boxH);
            // Dark-grey frame
            g.setColor(new Color(85, 90, 100, (int) (fade * 210)));
            g.drawRect(lx, ly, boxW, boxH);
            // White label text
            g.setColor(new Color(255, 255, 255, (int) (fade * 255)));
            g.drawString(name, lx + NAME_PAD_X, ly + NAME_PAD_Y + fm.getAscent());
        }
    }

    private void drawThrustHud(Graphics2D g) {
        int panelHeight = cy * 2;
        int gaugeBottom = panelHeight - GAUGE_MARGIN;
        int gaugeTop    = gaugeBottom - GAUGE_HEIGHT;

        // Outline
        g.setColor(new Color(255, 255, 255, 110));
        g.drawRect(GAUGE_X, gaugeTop, GAUGE_WIDTH, GAUGE_HEIGHT);

        // Power fill, bottom-up
        int fillHeight = (int) Math.round(GAUGE_HEIGHT * enginePower);
        g.setColor(thrustColor(enginePower));
        g.fillRect(GAUGE_X + 1, gaugeBottom - fillHeight, GAUGE_WIDTH - 1, fillHeight);

        // Speed readout, fictional but proportional to engine power
        double speedParsecPerSec = enginePower * MAX_SPEED_PARSEC;
        g.setColor(Color.WHITE);
        g.setFont(g.getFont().deriveFont(Font.PLAIN, 12f));
        g.drawString(String.format("%.2f pc/s", speedParsecPerSec),
            GAUGE_X + GAUGE_WIDTH + 10, gaugeBottom);
    }

    private void drawControlsHelp(Graphics2D g) {
        int panelWidth  = cx * 2;
        int panelHeight = cy * 2;
        int boxHeight   = 22 + HELP_ROWS.length * HELP_ROW_H;
        int x = panelWidth  - HELP_WIDTH  - HELP_MARGIN;
        int y = panelHeight - boxHeight   - HELP_MARGIN;

        g.setColor(new Color(15, 18, 26, 140));
        g.fillRoundRect(x, y, HELP_WIDTH, boxHeight, 10, 10);
        g.setColor(new Color(255, 255, 255, 70));
        g.drawRoundRect(x, y, HELP_WIDTH, boxHeight, 10, 10);

        g.setFont(g.getFont().deriveFont(Font.BOLD, 12f));
        g.setColor(new Color(255, 255, 255, 200));
        g.drawString("Controles (H)", x + 12, y + 17);

        g.setFont(g.getFont().deriveFont(Font.PLAIN, 11f));
        int rowY = y + 17 + HELP_ROW_H;
        for (String[] row : HELP_ROWS) {
            g.setColor(new Color(160, 200, 255, 220));
            g.drawString(row[0], x + 12, rowY);
            g.setColor(new Color(220, 220, 220, 200));
            g.drawString(row[1], x + HELP_KEY_COL, rowY);
            rowY += HELP_ROW_H;
        }
    }

    // Cyan (idle) -> yellow (cruise) -> red (full thrust)
    private static Color thrustColor(double power) {
        Color from, to;
        float  t;
        if (power < 0.5) {
            from = new Color(64, 200, 255);
            to   = new Color(255, 225, 70);
            t    = (float) (power / 0.5);
        } else {
            from = new Color(255, 225, 70);
            to   = new Color(255, 70, 70);
            t    = (float) ((power - 0.5) / 0.5);
        }
        return new Color(
            (int) (from.getRed()   + (to.getRed()   - from.getRed())   * t),
            (int) (from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            (int) (from.getBlue()  + (to.getBlue()  - from.getBlue())  * t)
        );
    }
}
