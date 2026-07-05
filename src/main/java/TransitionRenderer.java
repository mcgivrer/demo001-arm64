import java.nio.ByteBuffer;

import static org.lwjgl.opengles.GLES20.*;
import static org.lwjgl.opengles.GLES30.*;

public class TransitionRenderer {

    private int width;
    private int height;

    private int fboFrom;
    private int texFrom;
    private int fboTo;
    private int texTo;
    private int vao;

    public TransitionRenderer(int width, int height) {
        this.width = width;
        this.height = height;

        texFrom = createColorTexture(width, height);
        texTo = createColorTexture(width, height);

        fboFrom = createFramebuffer(texFrom);
        fboTo = createFramebuffer(texTo);

        vao = glGenVertexArrays();
        glBindVertexArray(vao);
        int vbo = glGenBuffers();
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER,
            new float[] {0, 0, 1, 0, 0, 1, 1, 1}, GL_STATIC_DRAW);
        glEnableVertexAttribArray(0);
        glVertexAttribPointer(0, 2, GL_FLOAT, false, 0, 0);
        glBindVertexArray(0);
    }

    public void resize(int width, int height) {
        this.width = width;
        this.height = height;

        if (texFrom != 0) glDeleteTextures(texFrom);
        if (texTo != 0) glDeleteTextures(texTo);
        if (fboFrom != 0) glDeleteFramebuffers(fboFrom);
        if (fboTo != 0) glDeleteFramebuffers(fboTo);

        texFrom = createColorTexture(width, height);
        texTo = createColorTexture(width, height);
        fboFrom = createFramebuffer(texFrom);
        fboTo = createFramebuffer(texTo);
    }

    public void captureFrom(Scene scene, RenderContext ctx) {
        captureScene(scene, ctx, fboFrom);
    }

    public void captureTo(Scene scene, RenderContext ctx) {
        captureScene(scene, ctx, fboTo);
    }

    private void captureScene(Scene scene, RenderContext ctx, int fbo) {
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glViewport(0, 0, width, height);
        glClearColor(0f, 0f, 0f, 1f);
        glClear(GL_COLOR_BUFFER_BIT);
        scene.draw(ctx);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, width, height);
    }

    public void draw(RenderContext ctx, SceneTransitionEffect effect, float progress) {
        int effectCode = mapEffect(effect);

        glDisable(GL_BLEND);
        ctx.transitionShader.use();
        ctx.transitionShader.setInt("uFrom", 0);
        ctx.transitionShader.setInt("uTo", 1);
        ctx.transitionShader.setInt("uEffect", effectCode);
        ctx.transitionShader.setFloat("uProgress", progress);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texFrom);
        glActiveTexture(GL_TEXTURE1);
        glBindTexture(GL_TEXTURE_2D, texTo);

        glBindVertexArray(vao);
        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
        glBindVertexArray(0);
        glEnable(GL_BLEND);
    }

    private static int mapEffect(SceneTransitionEffect effect) {
        return switch (effect) {
            case CUT -> 0;
            case FADE -> 1;
            case CROSS_FADE -> 2;
            case WIPE_LEFT -> 3;
            case ZOOM -> 4;
        };
    }

    private static int createColorTexture(int width, int height) {
        int tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
            GL_RGBA, GL_UNSIGNED_BYTE, (ByteBuffer) null);
        return tex;
    }

    private static int createFramebuffer(int tex) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, tex, 0);
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException("Transition FBO incomplete");
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return fbo;
    }
}
