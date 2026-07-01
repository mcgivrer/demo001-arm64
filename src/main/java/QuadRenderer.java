import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

/**
 * Draws HUD rectangles in pixel space (top-left origin, like Java2D) through
 * the {@code quad} shader: solid fill, optional rounded corners (SDF in the
 * fragment shader) and optional border band. Replaces fillRect /
 * fillRoundRect / drawRect / drawRoundRect from the Java2D renderer.
 */
public class QuadRenderer {

    private final ShaderProgram shader;
    private final int vao;
    private final float viewportW, viewportH;

    public QuadRenderer(ShaderProgram shader, int viewportW, int viewportH) {
        this.shader    = shader;
        this.viewportW = viewportW;
        this.viewportH = viewportH;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        // Unit quad 0..1, drawn as a triangle strip
        glBufferData(GL_ARRAY_BUFFER,
            new float[] {0, 0,  1, 0,  0, 1,  1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glBindVertexArray(0);
    }

    /** Plain filled rectangle (sharp corners). */
    public void fill(float x, float y, float w, float h,
                     float r, float g, float b, float a) {
        draw(x, y, w, h, 0, 0, r, g, b, a, 0, 0, 0, 0);
    }

    /** Filled rounded rectangle with a 1 px border, Java2D fillRoundRect+drawRoundRect. */
    public void fillRounded(float x, float y, float w, float h, float radius,
                            float fr, float fg, float fb, float fa,
                            float br, float bg, float bb, float ba) {
        draw(x, y, w, h, radius, 1f, fr, fg, fb, fa, br, bg, bb, ba);
    }

    /** Outline-only rectangle (1 px band), Java2D drawRect. */
    public void outline(float x, float y, float w, float h,
                        float r, float g, float b, float a) {
        draw(x, y, w, h, 0, 1f, 0, 0, 0, 0, r, g, b, a);
    }

    private void draw(float x, float y, float w, float h, float radius, float borderWidth,
                      float fr, float fg, float fb, float fa,
                      float br, float bg, float bb, float ba) {
        shader.use();
        shader.setVec2("uViewport", viewportW, viewportH);
        shader.setVec2("uPos", x, y);
        shader.setVec2("uSize", w, h);
        shader.setFloat("uRadius", radius);
        shader.setFloat("uBorderWidth", borderWidth);
        shader.setVec4("uFillColor", fr, fg, fb, fa);
        shader.setVec4("uBorderColor", br, bg, bb, ba);
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
    }
}
