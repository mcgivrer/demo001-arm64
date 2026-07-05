public class TextObject extends Entity {

    private final String text;
    private final float sizePt;
    private final boolean bold;
    private final float r, g, b, a;

    public TextObject(String text, float baselineY, float sizePt, boolean bold,
                      float r, float g, float b, float a) {
        super(0, baselineY, 0, 0);
        this.text = text;
        this.sizePt = sizePt;
        this.bold = bold;
        this.r = r;
        this.g = g;
        this.b = b;
        this.a = a;
    }

    @Override
    public void draw(RenderContext ctx) {
        int textWidth = ctx.text.stringWidth(text, sizePt, bold);
        float drawX = (ctx.width - textWidth) * 0.5f;
        ctx.text.draw(text, drawX, (float) y, sizePt, bold, r, g, b, a);
    }
}
