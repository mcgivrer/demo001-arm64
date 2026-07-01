import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.util.Random;

public class StarfieldBehavior implements Behavior {

    private static final int    STAR_COUNT   = 500;
    private static final double RANGE        = 2.0;
    private static final double NEAR_Z       = 0.06;
    private static final double MAX_VEL      = 0.35;  // rad/s
    private static final double DRIFT_ACC    = 0.06;  // rad/s² noise amplitude
    private static final double TRAVEL_SPEED = 0.20;  // base forward speed (units/s at z=1)
    private static final double LERP_RATE    = 4.0;   // response speed for user control (1/s)
    private static final double BRAKE_DECAY  = 8.0;   // deceleration rate when braking (1/s)
    private static final double MOUSE_DEAD   = 0.08;  // normalized dead zone for mouse joystick

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
    private final Color[]  starColor;
    private final float[]  brightness, baseSize;
    private final String[] starName;      // procedural name, or null for anonymous stars

    // Camera angular velocities (rad/s)
    private double velYaw = 0.05, velPitch = 0.02, velRoll = 0.01;

    // Engine power throttle, 0 (idle) .. 1 (full thrust)
    private double enginePower = INITIAL_THRUST;

    private final int       cx, cy;
    private final double    projScaleX, projScaleY;
    private final long      seed;         // master seed — the whole starfield derives from it
    private long            spawnCounter; // total stars generated; sub-seed index
    private final Random    rng;          // Brownian drift noise only (not star generation)
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

    public StarfieldBehavior(int width, int height, InputState input, long seed) {
        this.input = input;
        this.seed  = seed;
        this.rng   = new Random(seed);
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;

        sx          = new double[STAR_COUNT];
        sy          = new double[STAR_COUNT];
        sz          = new double[STAR_COUNT];
        travelSpeed = new double[STAR_COUNT];
        starColor   = new Color[STAR_COUNT];
        brightness  = new float[STAR_COUNT];
        baseSize    = new float[STAR_COUNT];
        starName    = new String[STAR_COUNT];

        for (int i = 0; i < STAR_COUNT; i++) initStar(i, true);
    }

    /**
     * SplitMix64 finalizer — decorrelates consecutive spawn indices so each
     * star gets an independent, reproducible sub-seed from the master seed.
     */
    private static long subSeed(long seed, long n) {
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
        double[] spec = SPECTRAL_TYPES[SPECTRAL_TYPES.length - 1];
        for (double[] s : SPECTRAL_TYPES) {
            if (r < s[0]) { spec = s; break; }
        }
        starColor[i]   = new Color((int) spec[1], (int) spec[2], (int) spec[3]);
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

        boolean anyKey = input.yawLeft || input.yawRight || input.pitchUp
                       || input.pitchDown || input.rollLeft || input.rollRight;
        boolean mouseActive = input.mouseDragging
                            && (Math.abs(input.mouseNormX) > MOUSE_DEAD
                                || Math.abs(input.mouseNormY) > MOUSE_DEAD);

        if (input.brake) {
            double decay = Math.max(0.0, 1.0 - BRAKE_DECAY * dt);
            velYaw *= decay; velPitch *= decay; velRoll *= decay;
        } else if (anyKey || mouseActive) {
            double tYaw = 0, tPitch = 0, tRoll = 0;
            if (input.yawLeft)   tYaw   = -MAX_VEL;
            if (input.yawRight)  tYaw   = +MAX_VEL;
            if (input.pitchUp)   tPitch = -MAX_VEL;
            if (input.pitchDown) tPitch = +MAX_VEL;
            if (input.rollLeft)  tRoll  = -MAX_VEL;
            if (input.rollRight) tRoll  = +MAX_VEL;
            if (mouseActive) {
                tYaw   = input.mouseNormX * MAX_VEL;
                tPitch = input.mouseNormY * MAX_VEL;
            }
            velYaw   += (tYaw   - velYaw)   * LERP_RATE * dt;
            velPitch += (tPitch - velPitch) * LERP_RATE * dt;
            velRoll  += (tRoll  - velRoll)  * LERP_RATE * dt;
        } else {
            // Brownian angular drift on 3 axes
            velYaw   += rng.nextGaussian() * DRIFT_ACC * dt;
            velPitch += rng.nextGaussian() * DRIFT_ACC * dt;
            velRoll  += rng.nextGaussian() * DRIFT_ACC * dt;
            velYaw   = Math.clamp(velYaw,   -MAX_VEL, MAX_VEL);
            velPitch = Math.clamp(velPitch, -MAX_VEL, MAX_VEL);
            velRoll  = Math.clamp(velRoll,  -MAX_VEL, MAX_VEL);
        }

        double ay = velYaw   * dt;
        double ap = velPitch * dt;
        double ar = velRoll  * dt;

        double cosY = Math.cos(ay), sinY = Math.sin(ay);
        double cosP = Math.cos(ap), sinP = Math.sin(ap);
        double cosR = Math.cos(ar), sinR = Math.sin(ar);

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
            int alpha = (int) (ab * 255);
            if (alpha < 8) continue;

            // Perspective-scaled radius
            float r = Math.clamp((float) (baseSize[i] / z), 0.4f, 8f);

            Color c = starColor[i];
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha));

            if (r < 1.0f) {
                // Sub-pixel star: single bright pixel
                g.fillRect((int) px, (int) py, 1, 1);
            } else {
                g.fill(new Ellipse2D.Float((float) px - r, (float) py - r, r * 2, r * 2));

                // Diffuse glow for luminous nearby stars (O, B, A types at close range)
                if (r > 2.0f && ab > 0.6f) {
                    float gr = r * 2.5f;
                    g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(),
                                         (int) (ab * 55)));
                    g.fill(new Ellipse2D.Float((float) px - gr, (float) py - gr,
                                               gr * 2, gr * 2));
                }
            }
        }

        drawStarLabels(g);
        drawThrustHud(g);
        if (input.showHelp) drawControlsHelp(g);
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
