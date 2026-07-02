import java.awt.Color;
import java.util.Random;

import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

public class StarfieldBehavior implements Behavior {

    private static final int    STAR_COUNT   = 500;
    private static final double RANGE        = 2.0;
    private static final double NEAR_Z       = 0.06;
    private static final double TRAVEL_SPEED = 0.20;  // base forward speed (units/s at z=1)

    private static final int    FLOATS_PER_STAR = 8;  // x, y, z, size, brightness, r, g, b

    private static final double NAME_PROBABILITY = 0.25; // fraction of stars that get a name
    private static final double NAME_Z_THRESHOLD = 1.00; // label fades in below this depth
    private static final double NAME_FULL_Z      = 0.50; // label fully opaque at this depth
    private static final float  NAME_FONT_SIZE   = 9f;
    private static final int    NAME_PAD_X       = 4;    // label horizontal padding (px)
    private static final int    NAME_PAD_Y       = 2;    // label vertical padding (px)

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
        {"F / F11",                "Plein écran"},
        {"ESCAPE",                 "Quitter"},
        {"H",                      "Afficher/masquer l'aide"},
    };

    // Parallel arrays — one slot per star
    private final double[] sx, sy, sz;
    private final double[] travelSpeed;   // per-star speed multiplier (parallax depth)
    private final int[]    spectralIdx;   // index into SPECTRAL_TYPES
    private final float[]  brightness, baseSize;
    private final String[] starName;      // procedural name, or null for anonymous stars

    // GL resources: one dynamic interleaved VBO, re-uploaded every frame
    private int vao, vbo;
    private final float[] starData = new float[STAR_COUNT * FLOATS_PER_STAR];

    // FPS counter — frames accumulated over FPS_PERIOD, then averaged
    private double fpsTimer;
    private int    fpsFrames;
    private int    fps;

    private int             cx, cy;       // projection centre — follows the viewport size
    private double          projScaleX, projScaleY;
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

        for (int i = 0; i < STAR_COUNT; i++) initStar(i, true);
    }

    @Override
    public void init(RenderContext ctx) {
        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) starData.length * Float.BYTES, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_STAR * Float.BYTES;
        glEnableVertexAttribArray(0);                                  // position
        glVertexAttribPointer(0, 3, GL_FLOAT, false, stride, 0);
        glEnableVertexAttribArray(1);                                  // size
        glVertexAttribPointer(1, 1, GL_FLOAT, false, stride, 12);
        glEnableVertexAttribArray(2);                                  // brightness
        glVertexAttribPointer(2, 1, GL_FLOAT, false, stride, 16);
        glEnableVertexAttribArray(3);                                  // colour
        glVertexAttribPointer(3, 3, GL_FLOAT, false, stride, 20);
        glBindVertexArray(0);
    }

    @Override
    public void resize(int width, int height) {
        cx = width  / 2;
        cy = height / 2;
        projScaleX = width  * 0.45;
        projScaleY = height * 0.45;
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
        fpsTimer += dt;
        fpsFrames++;
        if (fpsTimer >= FPS_PERIOD) {
            fps = (int) Math.round(fpsFrames / fpsTimer);
            fpsTimer  = 0;
            fpsFrames = 0;
        }

        // Frame rotation computed once by CameraState (shared with the nebula layer)
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
            // Scaled by the shared engine thrust integrated by CameraState
            z -= TRAVEL_SPEED * travelSpeed[i] * camera.travelFactor() / z * dt;
            sz[i] = z;

            // Star passed in front of (or too close to) viewer → respawn at far depth
            if (z <= NEAR_Z) initStar(i, false);
        }
    }

    @Override
    public void draw(Entity entity, RenderContext ctx) {
        // Projection, point size, inverse-square brightness and the radial
        // core+halo profile all run in star.vert / star.frag
        for (int i = 0; i < STAR_COUNT; i++) {
            int o = i * FLOATS_PER_STAR;
            double[] spec = SPECTRAL_TYPES[spectralIdx[i]];
            starData[o]     = (float) sx[i];
            starData[o + 1] = (float) sy[i];
            starData[o + 2] = (float) sz[i];
            starData[o + 3] = baseSize[i];
            starData[o + 4] = brightness[i];
            starData[o + 5] = (float) (spec[1] / 255.0);
            starData[o + 6] = (float) (spec[2] / 255.0);
            starData[o + 7] = (float) (spec[3] / 255.0);
        }

        ShaderProgram shader = ctx.starShader;
        shader.use();
        shader.setVec2("uViewport", cx * 2, cy * 2);
        shader.setVec2("uProjScale", (float) projScaleX, (float) projScaleY);
        shader.setFloat("uNearZ", (float) NEAR_Z);

        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, starData);
        glBindVertexArray(vao);
        glDrawArrays(GL_POINTS, 0, STAR_COUNT);
        glBindVertexArray(0);

        drawStarLabels(ctx);
        drawFpsHud(ctx);
        drawThrustHud(ctx);
        if (input.showHelp) drawControlsHelp(ctx);
    }

    /**
     * Second pass over the stars: named stars close enough to the viewer get
     * a label that fades in on approach — 9 pt white text inside a dark-grey
     * frame over a translucent dark-grey background.
     */
    private void drawStarLabels(RenderContext ctx) {
        int ascent = ctx.text.ascent(NAME_FONT_SIZE, false);
        int th     = ctx.text.lineHeight(NAME_FONT_SIZE, false);

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
            int   tw = ctx.text.stringWidth(name, NAME_FONT_SIZE, false);
            int   lx = (int) (px + r + 8);
            int   ly = (int) (py - th / 2.0 - NAME_PAD_Y);
            int   boxW = tw + NAME_PAD_X * 2;
            int   boxH = th + NAME_PAD_Y * 2;

            // Translucent dark-grey background + dark-grey frame
            ctx.quads.fillRounded(lx, ly, boxW, boxH, 0,
                38 / 255f, 42 / 255f, 48 / 255f, fade * 150 / 255f,
                85 / 255f, 90 / 255f, 100 / 255f, fade * 210 / 255f);
            // White label text
            ctx.text.draw(name, lx + NAME_PAD_X, ly + NAME_PAD_Y + ascent,
                NAME_FONT_SIZE, false, 1f, 1f, 1f, fade);
        }
    }

    private void drawFpsHud(RenderContext ctx) {
        ctx.text.draw(fps + " FPS", FPS_X, FPS_Y, FPS_FONT_SIZE, false, 1f, 1f, 1f, 1f);
    }

    private void drawThrustHud(RenderContext ctx) {
        int panelHeight = cy * 2;
        int gaugeBottom = panelHeight - GAUGE_MARGIN;
        int gaugeTop    = gaugeBottom - GAUGE_HEIGHT;

        // Outline
        ctx.quads.outline(GAUGE_X, gaugeTop, GAUGE_WIDTH, GAUGE_HEIGHT,
            1f, 1f, 1f, 110 / 255f);

        // Power fill, bottom-up
        int fillHeight = (int) Math.round(GAUGE_HEIGHT * camera.enginePower);
        Color c = thrustColor(camera.enginePower);
        ctx.quads.fill(GAUGE_X + 1, gaugeBottom - fillHeight, GAUGE_WIDTH - 1, fillHeight,
            c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, 1f);

        // Speed readout, fictional but proportional to engine power
        double speedParsecPerSec = camera.enginePower * MAX_SPEED_PARSEC;
        ctx.text.draw(String.format("%.2f pc/s", speedParsecPerSec),
            GAUGE_X + GAUGE_WIDTH + 10, gaugeBottom, 12f, false, 1f, 1f, 1f, 1f);
    }

    private void drawControlsHelp(RenderContext ctx) {
        int panelWidth  = cx * 2;
        int panelHeight = cy * 2;
        int boxHeight   = 22 + HELP_ROWS.length * HELP_ROW_H;
        int x = panelWidth  - HELP_WIDTH  - HELP_MARGIN;
        int y = panelHeight - boxHeight   - HELP_MARGIN;

        ctx.quads.fillRounded(x, y, HELP_WIDTH, boxHeight, 10,
            15 / 255f, 18 / 255f, 26 / 255f, 140 / 255f,
            1f, 1f, 1f, 70 / 255f);

        ctx.text.draw("Controles (H)", x + 12, y + 17, 12f, true,
            1f, 1f, 1f, 200 / 255f);

        int rowY = y + 17 + HELP_ROW_H;
        for (String[] row : HELP_ROWS) {
            ctx.text.draw(row[0], x + 12, rowY, 11f, false,
                160 / 255f, 200 / 255f, 1f, 220 / 255f);
            ctx.text.draw(row[1], x + HELP_KEY_COL, rowY, 11f, false,
                220 / 255f, 220 / 255f, 220 / 255f, 200 / 255f);
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
