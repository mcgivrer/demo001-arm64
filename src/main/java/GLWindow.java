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
 *
 * <p>The window is resizable and F / F11 toggles borderless fullscreen (the
 * monitor's current video mode). Size changes are reported through the
 * framebuffer-size callback: the render loop polls {@link #consumeResized()}
 * and propagates the new dimensions to the render context and behaviors.
 */
public class GLWindow {

    public interface InputHandler {
        boolean onKeyPressed(int key, int mods);
        boolean onKeyReleased(int key, int mods);
        boolean onMouseButtonPressed(int button, double x, double y, int mods);
        boolean onMouseButtonReleased(int button, double x, double y, int mods);
        boolean onMouseMoved(double x, double y);
        boolean onMouseScrolled(double xoffset, double yoffset);
    }

    private final long handle;
    private final InputHandler inputHandler;

    // Current framebuffer size, kept in sync by the GLFW callback
    private int width, height;
    private boolean resized;

    // Windowed-mode geometry, restored when leaving fullscreen
    private boolean fullscreen;
    private int windowedX, windowedY, windowedW, windowedH;

    public GLWindow(int width, int height, String title, InputHandler inputHandler) {
        this.width  = width;
        this.height = height;
        this.inputHandler = inputHandler;

        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");

        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_DECORATED, GLFW_TRUE); // keep title bar in windowed mode
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

        glfwSetKeyCallback(handle, (w, key, scancode, action, mods) -> onKey(key, action, mods));
        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> onMouseButton(button, action, mods));
        glfwSetCursorPosCallback(handle, (w, x, y) -> onMouseMove(x, y));
        glfwSetScrollCallback(handle, (w, xoffset, yoffset) -> onMouseScroll(xoffset, yoffset));
        glfwSetFramebufferSizeCallback(handle, (w, fbWidth, fbHeight) -> {
            if (fbWidth > 0 && fbHeight > 0) {   // 0×0 while minimised
                this.width  = fbWidth;
                this.height = fbHeight;
                resized     = true;
            }
        });

        glfwMakeContextCurrent(handle);
        GLES.createCapabilities();
        glfwSwapInterval(1);   // vsync — paces the loop at the display rate
        glfwShowWindow(handle);
    }

    /**
     * Borderless fullscreen toggle: switches to the monitor's current video
     * mode (no mode change, no decorations), and restores the previous
     * windowed geometry on the way back. The framebuffer-size callback fires
     * on both transitions, so the render loop resizes automatically.
     */
    private void toggleFullscreen() {
        if (!fullscreen) {
            int[] x = new int[1], y = new int[1], w = new int[1], h = new int[1];
            glfwGetWindowPos(handle, x, y);
            glfwGetWindowSize(handle, w, h);
            windowedX = x[0]; windowedY = y[0];
            windowedW = w[0]; windowedH = h[0];

            long monitor = glfwGetPrimaryMonitor();
            var mode = glfwGetVideoMode(monitor);
            glfwSetWindowMonitor(handle, monitor, 0, 0,
                mode.width(), mode.height(), mode.refreshRate());
        } else {
            glfwSetWindowMonitor(handle, NULL, windowedX, windowedY,
                windowedW, windowedH, 0);
            // Some window managers keep borderless hints after monitor switches.
            // Reassert decorations so the title bar is visible in windowed mode.
            glfwSetWindowAttrib(handle, GLFW_DECORATED, GLFW_TRUE);
        }
        fullscreen = !fullscreen;
        glfwSwapInterval(1);   // vsync can reset across a monitor switch
    }

    // --- Input callbacks ----------------------------------------------------

    private void onKey(int key, int action, int mods) {
        if (action == GLFW_REPEAT) return;
        if (action == GLFW_PRESS) {
            boolean handled = inputHandler != null && inputHandler.onKeyPressed(key, mods);
            if (handled) return;
        } else if (action == GLFW_RELEASE) {
            boolean handled = inputHandler != null && inputHandler.onKeyReleased(key, mods);
            if (handled) return;
        }

        if (action == GLFW_PRESS) {
            switch (key) {
                case GLFW_KEY_F, GLFW_KEY_F11 -> toggleFullscreen();
            }
        }
    }

    private void onMouseButton(int button, int action, int mods) {
        if (action != GLFW_PRESS && action != GLFW_RELEASE) return;
        double[] x = new double[1], y = new double[1];
        glfwGetCursorPos(handle, x, y);

        if (action == GLFW_PRESS) {
            if (inputHandler != null) inputHandler.onMouseButtonPressed(button, x[0], y[0], mods);
        } else {
            if (inputHandler != null) inputHandler.onMouseButtonReleased(button, x[0], y[0], mods);
        }
    }

    private void onMouseMove(double x, double y) {
        if (inputHandler != null) inputHandler.onMouseMoved(x, y);
    }

    private void onMouseScroll(double xoffset, double yoffset) {
        if (inputHandler != null) inputHandler.onMouseScrolled(xoffset, yoffset);
    }

    // --- Loop helpers -------------------------------------------------------

    public boolean shouldClose()   { return glfwWindowShouldClose(handle); }
    public boolean isConfirmQuit() { return false; }
    public void pollEvents()       { glfwPollEvents(); }
    public void swapBuffers()      { glfwSwapBuffers(handle); }

    public void requestClose() {
        glfwSetWindowShouldClose(handle, true);
    }

    public int width()  { return width; }
    public int height() { return height; }

    /** True once after each size change; reading it clears the flag. */
    public boolean consumeResized() {
        boolean r = resized;
        resized = false;
        return r;
    }

    public void destroy() {
        glfwDestroyWindow(handle);
        glfwTerminate();
        GLFWErrorCallback callback = glfwSetErrorCallback(null);
        if (callback != null) callback.free();
    }
}
