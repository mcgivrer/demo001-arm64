import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengles.GLES;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryUtil.NULL;

/**
 * GLFW window with an OpenGL ES 3.0 context (created through EGL, with a
 * fallback on the native context API). Replaces the former Swing JFrame +
 * GamePanel: the keyboard/mouse callbacks feed the same {@link InputState}
 * the behaviors already consume, and ESC drives a GL-rendered quit-confirm
 * overlay instead of a JOptionPane.
 */
public class GLWindow {

    private final long handle;
    private final int width, height;
    private final InputState input;
    private boolean confirmQuit;

    public GLWindow(int width, int height, String title, InputState input) {
        this.width  = width;
        this.height = height;
        this.input  = input;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_RESIZABLE, GLFW_FALSE);   // projection scales are fixed
        glfwWindowHint(GLFW_CLIENT_API, GLFW_OPENGL_ES_API);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 0);
        glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_EGL_CONTEXT_API);

        long h = glfwCreateWindow(width, height, title, NULL, NULL);
        if (h == NULL) {
            // Some driver stacks only expose ES through the native context API
            System.err.println("EGL context failed; retrying with the native context API");
            glfwWindowHint(GLFW_CONTEXT_CREATION_API, GLFW_NATIVE_CONTEXT_API);
            h = glfwCreateWindow(width, height, title, NULL, NULL);
        }
        if (h == NULL) throw new IllegalStateException("Cannot create OpenGL ES 3.0 window");
        handle = h;

        glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> onKey(key, action));
        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> onMouseButton(button, action));
        glfwSetCursorPosCallback(handle, (w, x, y) -> onMouseMove(x, y));

        glfwMakeContextCurrent(handle);
        GLES.createCapabilities();
        glfwSwapInterval(1);   // vsync — paces the loop at the display rate
        glfwShowWindow(handle);
    }

    // --- Input callbacks (same mapping as the former Swing GamePanel) ------

    private void onKey(int key, int action) {
        if (action == GLFW_REPEAT) return;
        boolean down = action == GLFW_PRESS;

        // Quit-confirm overlay captures ESC / ENTER
        if (down && key == GLFW_KEY_ESCAPE) {
            confirmQuit = !confirmQuit;
            return;
        }
        if (confirmQuit) {
            if (down && (key == GLFW_KEY_ENTER || key == GLFW_KEY_KP_ENTER)) {
                glfwSetWindowShouldClose(handle, true);
            }
            return;   // overlay open: ignore flight controls
        }

        switch (key) {
            case GLFW_KEY_LEFT, GLFW_KEY_A          -> input.yawLeft    = down;
            case GLFW_KEY_RIGHT, GLFW_KEY_D         -> input.yawRight   = down;
            case GLFW_KEY_UP, GLFW_KEY_W            -> input.pitchUp    = down;
            case GLFW_KEY_DOWN, GLFW_KEY_S          -> input.pitchDown  = down;
            case GLFW_KEY_Q                         -> input.rollLeft   = down;
            case GLFW_KEY_E                         -> input.rollRight  = down;
            case GLFW_KEY_SPACE                     -> input.brake      = down;
            case GLFW_KEY_LEFT_CONTROL, GLFW_KEY_RIGHT_CONTROL -> input.thrustUp   = down;
            case GLFW_KEY_LEFT_SHIFT, GLFW_KEY_RIGHT_SHIFT     -> input.thrustDown = down;
            case GLFW_KEY_H                         -> { if (down) input.showHelp = !input.showHelp; }
        }
    }

    private void onMouseButton(int button, int action) {
        if (button != GLFW_MOUSE_BUTTON_LEFT) return;
        if (action == GLFW_PRESS) {
            input.mouseDragging = true;
            double[] x = new double[1], y = new double[1];
            glfwGetCursorPos(handle, x, y);
            updateMouseNorm(x[0], y[0]);
        } else if (action == GLFW_RELEASE) {
            input.mouseDragging = false;
            input.mouseNormX = 0;
            input.mouseNormY = 0;
        }
    }

    private void onMouseMove(double x, double y) {
        if (input.mouseDragging) updateMouseNorm(x, y);
    }

    private void updateMouseNorm(double x, double y) {
        double halfW = width  / 2.0;
        double halfH = height / 2.0;
        input.mouseNormX = Math.clamp((x - halfW) / halfW, -1.0, 1.0);
        input.mouseNormY = Math.clamp((y - halfH) / halfH, -1.0, 1.0);
    }

    // --- Loop helpers -------------------------------------------------------

    public boolean shouldClose()   { return glfwWindowShouldClose(handle); }
    public boolean isConfirmQuit() { return confirmQuit; }
    public void pollEvents()       { glfwPollEvents(); }
    public void swapBuffers()      { glfwSwapBuffers(handle); }

    public void destroy() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) callback.free();
    }
}
