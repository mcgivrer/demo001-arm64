import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

public class Entity {
    public double x, y;
    public double width, height;
    public double dx, dy;
    private final List<Behavior> behaviors = new ArrayList<>();

    public Entity(double x, double y, double width, double height) {
        this.x = x;  this.y = y;
        this.width = width;  this.height = height;
    }

    public void addBehavior(Behavior b) { behaviors.add(b); }

    public void update(double dt) {
        for (Behavior b : behaviors) b.update(this, dt);
    }

    public void draw(Graphics2D g) {
        for (Behavior b : behaviors) b.draw(this, g);
    }
}
