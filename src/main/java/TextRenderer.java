import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

/**
 * Text rendering for the GL pipeline. Strings are rasterised once with AWT
 * (headless Java2D — white glyphs on a transparent background), uploaded as
 * RGBA textures and cached in an LRU map; drawing a string is then a single
 * textured quad through the {@code text} shader, tinted by uniform colour.
 */
public class TextRenderer {

    private static final int LRU_CAPACITY = 64;

    private record CachedText(int texture, int width, int height, int ascent) {}

    private final ShaderProgram shader;
    private final int vao;
    private float viewportW, viewportH;
    private final BufferedImage scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private final Map<String, CachedText> cache =
        new LinkedHashMap<>(LRU_CAPACITY, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, CachedText> eldest) {
                if (size() > LRU_CAPACITY) {
                    glDeleteTextures(eldest.getValue().texture());
                    return true;
                }
                return false;
            }
        };

    public TextRenderer(ShaderProgram shader, int viewportW, int viewportH) {
        this.shader    = shader;
        this.viewportW = viewportW;
        this.viewportH = viewportH;

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
            new float[] {0, 0,  1, 0,  0, 1,  1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glBindVertexArray(0);
    }

    public void resize(int viewportW, int viewportH) {
        this.viewportW = viewportW;
        this.viewportH = viewportH;
    }

    private Font font(float sizePt, boolean bold) {
        return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, Math.round(sizePt));
    }

    /** Text width in pixels, for layout (Java2D FontMetrics equivalent). */
    public int stringWidth(String text, float sizePt, boolean bold) {
        Graphics2D g = scratch.createGraphics();
        int w = g.getFontMetrics(font(sizePt, bold)).stringWidth(text);
        g.dispose();
        return w;
    }

    /** Line height in pixels for the given font size. */
    public int lineHeight(float sizePt, boolean bold) {
        Graphics2D g = scratch.createGraphics();
        FontMetrics fm = g.getFontMetrics(font(sizePt, bold));
        int h = fm.getAscent() + fm.getDescent();
        g.dispose();
        return h;
    }

    public int ascent(float sizePt, boolean bold) {
        Graphics2D g = scratch.createGraphics();
        int a = g.getFontMetrics(font(sizePt, bold)).getAscent();
        g.dispose();
        return a;
    }

    /**
     * Draws {@code text} with its baseline at (x, y) — the Java2D
     * {@code drawString} convention — in the given RGBA colour (0..1).
     */
    public void draw(String text, float x, float y, float sizePt, boolean bold,
                     float r, float g, float b, float a) {
        if (text.isEmpty()) return;
        CachedText entry = cache.computeIfAbsent(
            sizePt + (bold ? "B:" : "P:") + text, k -> rasterise(text, sizePt, bold));

        shader.use();
        shader.setVec2("uViewport", viewportW, viewportH);
        shader.setVec2("uPos", x, y - entry.ascent());
        shader.setVec2("uSize", entry.width(), entry.height());
        shader.setVec4("uColor", r, g, b, a);
        shader.setInt("uTexture", 0);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, entry.texture());
        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
    }

    /** AWT rasterisation: white antialiased glyphs, alpha = coverage. */
    private CachedText rasterise(String text, float sizePt, boolean bold) {
        Font f = font(sizePt, bold);
        Graphics2D mg = scratch.createGraphics();
        FontMetrics fm = mg.getFontMetrics(f);
        int w = Math.max(1, fm.stringWidth(text));
        int h = fm.getAscent() + fm.getDescent();
        mg.dispose();

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(f);
        g.setColor(Color.WHITE);
        g.drawString(text, 0, fm.getAscent());
        g.dispose();

        // ARGB ints → RGBA bytes
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        for (int p : argb) {
            buf.put((byte) ((p >> 16) & 0xFF));
            buf.put((byte) ((p >> 8) & 0xFF));
            buf.put((byte) (p & 0xFF));
            buf.put((byte) ((p >> 24) & 0xFF));
        }
        buf.flip();

        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        MemoryUtil.memFree(buf);

        return new CachedText(tex, w, h, fm.getAscent());
    }
}
