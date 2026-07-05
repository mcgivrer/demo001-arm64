public class ButtonObject extends ControlUI {

    public interface ActivationListener {
        void onActivate(ButtonObject button);
    }

    private final String label;
    private final ActivationListener listener;

    private float pulse;

    public ButtonObject(double x, double y, double width, double height,
                        String label, ActivationListener listener) {
        super(x, y, width, height);
        this.label = label;
        this.listener = listener;
    }

    @Override
    public void update(double dt) {
        pulse += (float) dt;
        if (pulse > 1000f) pulse -= 1000f;
    }

    @Override
    public void activate() {
        if (listener != null) listener.onActivate(this);
    }

    @Override
    public void draw(RenderContext ctx) {
        float emphasis = focused ? 1f : (hovered ? 0.7f : 0.35f);
        float glow = focused ? (0.82f + 0.18f * (float) Math.sin(pulse * 6.5f)) : 1f;

        ctx.quads.fillRounded((float) x, (float) y, (float) width, (float) height, 12,
            18 / 255f, 32 / 255f, 66 / 255f, 0.42f + emphasis * 0.28f,
            120 / 255f * glow, 185 / 255f * glow, 1f, 0.28f + emphasis * 0.55f);

        int textW = ctx.text.stringWidth(label, 13f, true);
        int textAscent = ctx.text.ascent(13f, true);
        float tx = (float) x + ((float) width - textW) * 0.5f;
        float ty = (float) y + ((float) height - textAscent) * 0.5f + textAscent;

        float textAlpha = 0.86f + emphasis * 0.14f;
        ctx.text.draw(label, tx + 1f, ty + 1f, 13f, true,
            8 / 255f, 12 / 255f, 20 / 255f, textAlpha * 0.65f);
        ctx.text.draw(label, tx, ty, 13f, true,
            235 / 255f, 246 / 255f, 1f, textAlpha);
    }
}
