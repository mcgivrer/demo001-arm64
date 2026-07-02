public interface Behavior {
    void update(Entity entity, double dt);

    /** One-time GL resource creation (VBOs, textures); GL context is current. */
    default void init(RenderContext ctx) {}

    /** Viewport size changed (window resize / fullscreen); GL context is current. */
    default void resize(int width, int height) {}

    void draw(Entity entity, RenderContext ctx);
}
