import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

public class TravelScene implements Scene {

    private final InputState input;
    private final List<Entity> entities = new ArrayList<>();
    private SceneTransition pendingTransition;
    private int width;
    private int height;

    public TravelScene(int width, int height, InputState input, long seed) {
        this.width = width;
        this.height = height;
        this.input = input;
        entities.add(new ParticleSystem(width, height, input, seed));
    }

    @Override
    public void init(RenderContext ctx) {
        for (Entity entity : entities) entity.init(ctx);
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        for (Entity entity : entities) entity.resize(width, height);
    }

    @Override
    public void update(double dt) {
        for (Entity entity : entities) entity.update(dt);
    }

    @Override
    public boolean onKeyPressed(int key, int mods) {
        return handleKey(key, true);
    }

    @Override
    public boolean onKeyReleased(int key, int mods) {
        return handleKey(key, false);
    }

    @Override
    public boolean onMouseButtonPressed(int button, double x, double y, int mods) {
        input.pointerX = x;
        input.pointerY = y;
        if (button != GLFW_MOUSE_BUTTON_LEFT) return false;
        input.mouseDragging = true;
        updateMouseNorm(x, y);
        return true;
    }

    @Override
    public boolean onMouseButtonReleased(int button, double x, double y, int mods) {
        input.pointerX = x;
        input.pointerY = y;
        if (button != GLFW_MOUSE_BUTTON_LEFT) return false;
        input.mouseDragging = false;
        input.mouseNormX = 0;
        input.mouseNormY = 0;
        return true;
    }

    @Override
    public boolean onMouseMoved(double x, double y) {
        input.pointerX = x;
        input.pointerY = y;
        if (input.mouseDragging) {
            updateMouseNorm(x, y);
            return true;
        }
        return false;
    }

    @Override
    public void draw(RenderContext ctx) {
        for (Entity entity : entities) entity.draw(ctx);
    }

    @Override
    public SceneTransition pollTransition() {
        SceneTransition out = pendingTransition;
        pendingTransition = null;
        return out;
    }

    private boolean handleKey(int key, boolean down) {
        switch (key) {
            case GLFW_KEY_LEFT, GLFW_KEY_A -> {
                input.yawLeft = down;
                return true;
            }
            case GLFW_KEY_RIGHT, GLFW_KEY_D -> {
                input.yawRight = down;
                return true;
            }
            case GLFW_KEY_UP, GLFW_KEY_W -> {
                input.pitchUp = down;
                return true;
            }
            case GLFW_KEY_DOWN, GLFW_KEY_S -> {
                input.pitchDown = down;
                return true;
            }
            case GLFW_KEY_Q -> {
                input.rollLeft = down;
                return true;
            }
            case GLFW_KEY_E -> {
                input.rollRight = down;
                return true;
            }
            case GLFW_KEY_SPACE -> {
                input.brake = down;
                return true;
            }
            case GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> {
                input.thrustUp = down;
                return true;
            }
            case GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT -> {
                input.thrustDown = down;
                return true;
            }
            case GLFW_KEY_F1 -> {
                if (down) input.showHelp = !input.showHelp;
                return true;
            }
            case GLFW_KEY_ESCAPE -> {
                if (down && pendingTransition == null) {
                    pendingTransition = SceneTransition.fadeTo("title", 0.35);
                }
                return true;
            }
            case GLFW_KEY_H -> {
                if (down && pendingTransition == null) {
                    pendingTransition = SceneTransition.zoomTo("map", 0.55);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void updateMouseNorm(double x, double y) {
        double halfW = width / 2.0;
        double halfH = height / 2.0;
        input.mouseNormX = Math.clamp((x - halfW) / halfW, -1.0, 1.0);
        input.mouseNormY = Math.clamp((y - halfH) / halfH, -1.0, 1.0);
    }
}
