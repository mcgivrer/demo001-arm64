import java.util.ArrayList;
import java.util.List;

public class TravelScene implements Scene {

    private final InputState input;
    private final List<Entity> entities = new ArrayList<>();
    private SceneTransition pendingTransition;

    public TravelScene(int width, int height, InputState input, long seed) {
        this.input = input;
        entities.add(new ParticleSystem(width, height, input, seed));
    }

    @Override
    public void init(RenderContext ctx) {
        for (Entity entity : entities) entity.init(ctx);
    }

    @Override
    public void resize(int width, int height) {
        for (Entity entity : entities) entity.resize(width, height);
    }

    @Override
    public void update(double dt) {
        if (pendingTransition == null && input.consumeEscapeRequested()) {
            pendingTransition = SceneTransition.fadeTo("title", 0.35);
        }

        for (Entity entity : entities) entity.update(dt);
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
}
