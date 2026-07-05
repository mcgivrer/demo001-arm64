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

    private final long handle;
    private final InputState input;

    // Current framebuffer size, kept in sync by the GLFW callback
    private int width, height;
    private boolean resized;

    // Windowed-mode geometry, restored when leaving fullscreen
    private boolean fullscreen;
    private int windowedX, windowedY, windowedW, windowedH;

    public GLWindow(int width, int height, String title, InputState input) {
        this.width  = width;
        this.height = height;
        this.input  = input;

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
        glfwSetMouseButtonCallback(handle, (w, button, action, mods) -> onMouseButton(button, action));
        glfwSetCursorPosCallback(handle, (w, x, y) -> onMouseMove(x, y));
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

    // --- Input callbacks (same mapping as the former Swing GamePanel) ------

    private void onKey(int key, int action, int mods) {
        if (action == GLFW_REPEAT) return;
        boolean down = action == GLFW_PRESS;

        if (down && key == GLFW_KEY_ESCAPE) {
            input.escapeRequested = true;
            return;
        }

        switch (key) {
            case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                if (down) {
                    input.startRequested = true;
                    input.uiActivateRequested = true;
                }
            }
            case GLFW_KEY_TAB -> {
                if (down) {
                    input.uiTabStep += (mods & GLFW_MOD_SHIFT) != 0 ? -1 : 1;
                }
            }
            case GLFW_KEY_F, GLFW_KEY_F11           -> { if (down) toggleFullscreen(); }
            case GLFW_KEY_LEFT, GLFW_KEY_A -> {
                input.yawLeft = down;
                if (down && key == GLFW_KEY_LEFT) input.uiFocusStep -= 1;
            }
            case GLFW_KEY_RIGHT, GLFW_KEY_D -> {
                input.yawRight = down;
                if (down && key == GLFW_KEY_RIGHT) input.uiFocusStep += 1;
            }
            case GLFW_KEY_UP, GLFW_KEY_W -> {
                input.pitchUp = down;
                if (down && key == GLFW_KEY_UP) input.uiFocusStep -= 1;
            }
            case GLFW_KEY_DOWN, GLFW_KEY_S -> {
                input.pitchDown = down;
                if (down && key == GLFW_KEY_DOWN) input.uiFocusStep += 1;
            }
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
            input.pointerX = x[0];
            input.pointerY = y[0];
            input.uiClickX = x[0];
            input.uiClickY = y[0];
            input.uiClickRequested = true;
            updateMouseNorm(x[0], y[0]);
        } else if (action == GLFW_RELEASE) {
            input.mouseDragging = false;
            input.mouseNormX = 0;
            input.mouseNormY = 0;
        }
    }

    private void onMouseMove(double x, double y) {
        input.pointerX = x;
        input.pointerY = y;
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
