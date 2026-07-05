public interface Scene {

    /** One-time setup when the scene becomes active. */
    default void init(RenderContext ctx) {}

    /** Viewport size changed (window resize / fullscreen). */
    default void resize(int width, int height) {}

    /** Key pressed once (GLFW_PRESS, repeats filtered by GLWindow). */
    default boolean onKeyPressed(int key, int mods) { return false; }

    /** Key released (GLFW_RELEASE). */
    default boolean onKeyReleased(int key, int mods) { return false; }

    /** Mouse button pressed at current cursor position. */
    default boolean onMouseButtonPressed(int button, double x, double y, int mods) { return false; }

    /** Mouse button released at current cursor position. */
    default boolean onMouseButtonReleased(int button, double x, double y, int mods) { return false; }

    /** Mouse moved to the provided cursor position. */
    default boolean onMouseMoved(double x, double y) { return false; }

    /** Mouse wheel scroll delta (GLFW x/y offsets). */
    default boolean onMouseScrolled(double xoffset, double yoffset) { return false; }

    void update(double dt);

    void draw(RenderContext ctx);

    /**
     * Optional transition request emitted by the scene (null = stay on scene).
     * Transition rendering is managed by Main / scene manager.
     */
    default SceneTransition pollTransition() { return null; }

    /** Called right before leaving the scene. */
    default void dispose() {}
}
