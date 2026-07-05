public abstract class ControlUI extends Entity {

    protected boolean focused;
    protected boolean hovered;

    protected ControlUI(double x, double y, double width, double height) {
        super(x, y, width, height);
    }

    public void setFocused(boolean focused) {
        this.focused = focused;
    }

    public void setHovered(boolean hovered) {
        this.hovered = hovered;
    }

    public boolean contains(double px, double py) {
        return px >= x && px <= x + width && py >= y && py <= y + height;
    }

    public abstract void activate();
}
