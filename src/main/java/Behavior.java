public interface Behavior {
    void update(Entity entity, double dt);

    /** One-time GL resource creation (VBOs, textures); GL context is current. */
    default void init(RenderContext ctx) {}

    void draw(Entity entity, RenderContext ctx);
}
