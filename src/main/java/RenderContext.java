/**
 * Shared GL rendering resources handed to every {@link Behavior}: the five
 * shader programs, the HUD helpers (quads, text) and the viewport geometry.
 * Must be created after the GL context is current.
 */
public class RenderContext {

    public int width, height;

    public final ShaderProgram starShader;
    public final ShaderProgram nebulaShader;
    public final ShaderProgram quadShader;
    public final ShaderProgram textShader;
    public final ShaderProgram blitShader;

    public final QuadRenderer quads;
    public final TextRenderer text;

    public RenderContext(int width, int height) {
        this.width  = width;
        this.height = height;

        starShader   = new ShaderProgram("star");
        nebulaShader = new ShaderProgram("nebula");
        quadShader   = new ShaderProgram("quad");
        textShader   = new ShaderProgram("text");
        blitShader   = new ShaderProgram("blit");

        quads = new QuadRenderer(quadShader, width, height);
        text  = new TextRenderer(textShader, width, height);
    }

    /** Propagates a viewport size change to the pixel-space HUD helpers. */
    public void resize(int width, int height) {
        this.width  = width;
        this.height = height;
        quads.resize(width, height);
        text.resize(width, height);
    }
}
