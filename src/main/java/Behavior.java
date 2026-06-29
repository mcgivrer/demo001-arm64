import java.awt.Graphics2D;

public interface Behavior {
    void update(Entity entity, double dt);
    void draw(Entity entity, Graphics2D g);
}
