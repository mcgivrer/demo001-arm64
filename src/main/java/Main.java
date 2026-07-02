import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.lwjgl.opengles.GLES20.*;

public class Main {

    private static final int    DEFAULT_WIDTH  = 800;
    private static final int    DEFAULT_HEIGHT = 600;
    private static final String DEFAULT_LANG   = "EN";
    private static final long   DEFAULT_SEED   = 42L;

    private final int    windowWidth;
    private final int    windowHeight;
    private final long   starSeed;
    private final String windowTitle;
    private final ResourceBundle bundle;

    private final List<Entity>  entities   = new ArrayList<>();
    private final InputState    inputState = new InputState();
    private long lastTime;

    public Main() {
        System.out.println("Starting Application...");

        Properties config = loadConfig();

        windowWidth  = Integer.parseInt(
            config.getProperty("app.window.width",  String.valueOf(DEFAULT_WIDTH)));
        windowHeight = Integer.parseInt(
            config.getProperty("app.window.height", String.valueOf(DEFAULT_HEIGHT)));

        starSeed = Long.parseLong(
            config.getProperty("app.stars.seed", String.valueOf(DEFAULT_SEED)));

        String langCode = config.getProperty("app.language.default", DEFAULT_LANG);
        Locale locale   = Locale.of(langCode.toLowerCase(Locale.ROOT));

        bundle      = loadBundle(locale);
        windowTitle = getMessage("app.title", "Demo001");

        System.out.printf("Window: %dx%d  locale: %s  seed: %d  title: \"%s\"%n",
            windowWidth, windowHeight, locale, starSeed, windowTitle);
    }

    private Properties loadConfig() {
        Properties props = new Properties();
        try (InputStream in = Main.class.getResourceAsStream("/config.properties")) {
            if (in != null) {
                props.load(in);
            } else {
                System.out.println("config.properties not found on classpath; using defaults.");
            }
        } catch (IOException e) {
            System.err.println("Failed to load config.properties: " + e.getMessage());
        }
        return props;
    }

    private ResourceBundle loadBundle(Locale locale) {
        try {
            return ResourceBundle.getBundle("i18n.messages", locale);
        } catch (MissingResourceException e) {
            System.err.println("Bundle 'i18n.messages' not found; using hard-coded text.");
            return null;
        }
    }

    private String getMessage(String key, String fallback) {
        if (bundle == null) return fallback;
        try {
            return bundle.getString(key);
        } catch (MissingResourceException e) {
            return fallback;
        }
    }

    public void run(String[] args) {
        for (String arg : args) {
            System.out.printf("-> arg:%s%n", arg);
        }
        initEntities();
        runRenderLoop();
        dispose();
    }

    private void initEntities() {
        entities.add(new ParticleSystem(windowWidth, windowHeight, inputState, starSeed));
    }

    /**
     * Classic GLFW game loop, replacing the former Swing EDT + Timer: poll
     * events, integrate with the measured delta-time, draw every layer, then
     * swap — vsync (swapInterval 1) paces the loop at the display rate.
     */
    private void runRenderLoop() {
        GLWindow window = new GLWindow(windowWidth, windowHeight, windowTitle, inputState);
        RenderContext ctx = new RenderContext(windowWidth, windowHeight);
        for (Entity entity : entities) entity.init(ctx);

        glDisable(GL_DEPTH_TEST);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);   // Java2D SrcOver equivalent
        glClearColor(0f, 0f, 0f, 1f);

        lastTime = System.nanoTime();
        while (!window.shouldClose()) {
            window.pollEvents();

            // Window resize / fullscreen toggle (F or F11): propagate the new
            // framebuffer size to the viewport, the HUD helpers and every layer
            if (window.consumeResized()) {
                int w = window.width(), h = window.height();
                glViewport(0, 0, w, h);
                ctx.resize(w, h);
                for (Entity entity : entities) entity.resize(w, h);
            }

            long now = System.nanoTime();
            double dt = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            for (Entity entity : entities) entity.update(dt);

            glClear(GL_COLOR_BUFFER_BIT);
            for (Entity entity : entities) entity.draw(ctx);
            if (window.isConfirmQuit()) drawExitOverlay(ctx);

            window.swapBuffers();
        }
        window.destroy();
    }

    /** Quit-confirmation overlay (ESC), replacing the former JOptionPane. */
    private void drawExitOverlay(RenderContext ctx) {
        String title   = getMessage("app.exit.confirm.title",   "Confirm Exit");
        String message = getMessage("app.exit.confirm.message", "Are you sure you want to quit?");
        String hint    = getMessage("app.exit.confirm.hint",    "Enter = yes  /  Esc = no");

        // Dim the scene
        ctx.quads.fill(0, 0, ctx.width, ctx.height, 0f, 0f, 0f, 0.55f);

        int boxW = Math.max(300, ctx.text.stringWidth(message, 11f, false) + 60);
        int boxH = 110;
        int x = (ctx.width  - boxW) / 2;
        int y = (ctx.height - boxH) / 2;

        ctx.quads.fillRounded(x, y, boxW, boxH, 12,
            15 / 255f, 18 / 255f, 26 / 255f, 0.92f,
            1f, 1f, 1f, 0.35f);

        drawCentered(ctx, title,   y + 30, 13f, true,  1f, 1f, 1f, 1f);
        drawCentered(ctx, message, y + 60, 11f, false, 0.86f, 0.86f, 0.86f, 1f);
        drawCentered(ctx, hint,    y + 88, 10f, false, 160 / 255f, 200 / 255f, 1f, 1f);
    }

    private void drawCentered(RenderContext ctx, String text, int baselineY,
                              float sizePt, boolean bold,
                              float r, float g, float b, float a) {
        int w = ctx.text.stringWidth(text, sizePt, bold);
        ctx.text.draw(text, (ctx.width - w) / 2f, baselineY, sizePt, bold, r, g, b, a);
    }

    private void dispose() {
        System.out.println("done.");
    }

    public static void main(String[] args) {
        Main app = new Main();
        app.run(args);
    }
}
