import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.Properties;
import java.util.ResourceBundle;

import static org.lwjgl.opengles.GLES20.*;

public class Main {

    private static final int    DEFAULT_WIDTH  = 800;
    private static final int    DEFAULT_HEIGHT = 600;
    private static final String DEFAULT_LANG   = "EN";
    private static final long   DEFAULT_SEED   = 42L;
    private static final double MIN_TRANSITION_DURATION = 0.15;
    private static final String SCENE_TITLE    = "title";
    private static final String SCENE_TRAVEL   = "travel";
    private static final String SCENE_MAP      = "map";
    private static final String SCENE_QUIT     = "quit";

    private final int    windowWidth;
    private final int    windowHeight;
    private final long   starSeed;
    private final String windowTitle;
    private final ResourceBundle bundle;

    private final InputState    inputState = new InputState();
    private long lastTime;
    private Scene activeScene;
    private GLWindow window;
    private TransitionRenderer transitionRenderer;

    private boolean transitionActive;
    private double transitionElapsed;
    private double transitionDuration;
    private String transitionTargetSceneId;
    private SceneTransitionEffect transitionEffect = SceneTransitionEffect.CUT;
    private Scene transitionFromScene;
    private Scene transitionToScene;

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
        runRenderLoop();
        dispose();
    }

    /**
     * Classic GLFW game loop, replacing the former Swing EDT + Timer: poll
     * events, integrate with the measured delta-time, draw every layer, then
     * swap — vsync (swapInterval 1) paces the loop at the display rate.
     */
    private void runRenderLoop() {
        window = new GLWindow(windowWidth, windowHeight, windowTitle, new GLWindow.InputHandler() {
            @Override
            public boolean onKeyPressed(int key, int mods) {
                Scene scene = inputScene();
                return scene != null && scene.onKeyPressed(key, mods);
            }

            @Override
            public boolean onKeyReleased(int key, int mods) {
                Scene scene = inputScene();
                return scene != null && scene.onKeyReleased(key, mods);
            }

            @Override
            public boolean onMouseButtonPressed(int button, double x, double y, int mods) {
                Scene scene = inputScene();
                return scene != null && scene.onMouseButtonPressed(button, x, y, mods);
            }

            @Override
            public boolean onMouseButtonReleased(int button, double x, double y, int mods) {
                Scene scene = inputScene();
                return scene != null && scene.onMouseButtonReleased(button, x, y, mods);
            }

            @Override
            public boolean onMouseMoved(double x, double y) {
                Scene scene = inputScene();
                return scene != null && scene.onMouseMoved(x, y);
            }

            @Override
            public boolean onMouseScrolled(double xoffset, double yoffset) {
                Scene scene = inputScene();
                return scene != null && scene.onMouseScrolled(xoffset, yoffset);
            }
        });
        RenderContext ctx = new RenderContext(windowWidth, windowHeight);
        transitionRenderer = new TransitionRenderer(windowWidth, windowHeight);
        switchScene(SCENE_TITLE, ctx);

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
                transitionRenderer.resize(w, h);
                if (transitionActive) {
                    if (transitionFromScene != null) transitionFromScene.resize(w, h);
                    if (transitionToScene != null) transitionToScene.resize(w, h);
                } else if (activeScene != null) {
                    activeScene.resize(w, h);
                }
            }

            long now = System.nanoTime();
            double dt = (now - lastTime) / 1_000_000_000.0;
            lastTime = now;

            if (!transitionActive && activeScene != null) {
                activeScene.update(dt);
                SceneTransition transition = activeScene.pollTransition();
                if (transition != null) applyTransition(transition, ctx);
            } else if (transitionActive) {
                updateTransition(dt, ctx);

                if (transitionToScene != null && transitionElapsed >= transitionDuration * 0.5) {
                    transitionToScene.update(dt);
                } else if (transitionFromScene != null) {
                    transitionFromScene.update(dt);
                }
            }

            glClear(GL_COLOR_BUFFER_BIT);
            if (transitionActive) {
                drawTransition(ctx);
            } else if (activeScene != null) {
                activeScene.draw(ctx);
            }
            window.swapBuffers();
        }
        if (transitionActive) {
            if (transitionFromScene != null) transitionFromScene.dispose();
            if (transitionToScene != null) transitionToScene.dispose();
        } else if (activeScene != null) {
            activeScene.dispose();
        }
        window.destroy();
    }

    private Scene inputScene() {
        if (transitionActive) {
            if (transitionToScene != null && transitionElapsed >= transitionDuration * 0.5) {
                return transitionToScene;
            }
            if (transitionFromScene != null) {
                return transitionFromScene;
            }
        }
        return activeScene;
    }

    private Scene newScene(String sceneId, int width, int height) {
        return switch (sceneId) {
            case SCENE_TRAVEL -> new TravelScene(width, height, inputState, starSeed);
            case SCENE_MAP -> new MapScene(width, height, inputState, starSeed);
            case SCENE_TITLE -> new TitleScene(
                width,
                height,
                inputState,
                getMessage("app.title", "Demo001"),
                getMessage("scene.title.subtitle", "Simulation de voyage interstellaire"),
                getMessage("scene.title.start", "Appuyez sur Entrée pour démarrer"),
                getMessage("scene.title.quit", "Quitter")
            );
            default -> throw new IllegalArgumentException("Unknown scene id: " + sceneId);
        };
    }

    private void switchScene(String sceneId, RenderContext ctx) {
        if (activeScene != null) activeScene.dispose();
        activeScene = newScene(sceneId, ctx.width, ctx.height);
        activeScene.init(ctx);
        activeScene.resize(ctx.width, ctx.height);
    }

    private void applyTransition(SceneTransition transition, RenderContext ctx) {
        if (SCENE_QUIT.equals(transition.targetSceneId())) {
            if (window != null) window.requestClose();
            return;
        }

        if (transitionActive) return;

        SceneTransitionEffect effect = transition.effect();
        if (effect == SceneTransitionEffect.CUT || transition.durationSeconds() <= 0.0) {
            switchScene(transition.targetSceneId(), ctx);
            return;
        }

        transitionFromScene = activeScene;
        transitionToScene = newScene(transition.targetSceneId(), ctx.width, ctx.height);
        transitionToScene.init(ctx);
        transitionToScene.resize(ctx.width, ctx.height);

        transitionActive = true;
        transitionElapsed = 0.0;
        transitionDuration = Math.max(MIN_TRANSITION_DURATION, transition.durationSeconds());
        transitionTargetSceneId = transition.targetSceneId();
        transitionEffect = effect;
    }

    private void updateTransition(double dt, RenderContext ctx) {
        transitionElapsed += dt;

        if (transitionElapsed >= transitionDuration) {
            if (transitionFromScene != null) transitionFromScene.dispose();
            activeScene = transitionToScene;

            transitionActive = false;
            transitionElapsed = 0.0;
            transitionDuration = 0.0;
            transitionTargetSceneId = null;
            transitionEffect = SceneTransitionEffect.CUT;

            transitionFromScene = null;
            transitionToScene = null;
        }
    }

    private void drawTransition(RenderContext ctx) {
        if (transitionFromScene == null || transitionToScene == null) return;

        transitionRenderer.captureFrom(transitionFromScene, ctx);
        transitionRenderer.captureTo(transitionToScene, ctx);
        float progress = (float) Math.min(1.0, transitionElapsed / transitionDuration);
        transitionRenderer.draw(ctx, transitionEffect, progress);
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
