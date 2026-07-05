public interface Scene {

    /** One-time setup when the scene becomes active. */
    default void init(RenderContext ctx) {}

    /** Viewport size changed (window resize / fullscreen). */
    default void resize(int width, int height) {}

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
