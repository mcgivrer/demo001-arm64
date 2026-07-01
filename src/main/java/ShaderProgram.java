import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.opengles.GLES20.*;

/**
 * Compiles and links a GLSL ES program from a pair of classpath resources
 * {@code /shaders/<name>.vert} + {@code /shaders/<name>.frag}, and caches
 * uniform locations. Shader sources live as plain files under
 * {@code src/main/resources/shaders/} so they can be edited without touching
 * Java code.
 */
public class ShaderProgram {

    private final int id;
    private final String name;
    private final Map<String, Integer> uniforms = new HashMap<>();

    public ShaderProgram(String name) {
        this.name = name;
        int vert = compile(GL_VERTEX_SHADER,   "/shaders/" + name + ".vert");
        int frag = compile(GL_FRAGMENT_SHADER, "/shaders/" + name + ".frag");

        id = glCreateProgram();
        glAttachShader(id, vert);
        glAttachShader(id, frag);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == GL_FALSE) {
            throw new IllegalStateException(
                "Shader link failed [" + name + "]: " + glGetProgramInfoLog(id));
        }
        glDeleteShader(vert);
        glDeleteShader(frag);
    }

    private int compile(int type, String resource) {
        String source;
        try (InputStream in = ShaderProgram.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Shader not found: " + resource);
            source = new String(in.readAllBytes());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read shader: " + resource, e);
        }
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            throw new IllegalStateException(
                "Shader compile failed [" + resource + "]: " + glGetShaderInfoLog(shader));
        }
        return shader;
    }

    public void use() {
        glUseProgram(id);
    }

    private int uniform(String uniformName) {
        return uniforms.computeIfAbsent(uniformName, n -> {
            int loc = glGetUniformLocation(id, n);
            if (loc < 0) {
                System.err.println("Warning: uniform '" + n + "' not found in shader '"
                                   + name + "'");
            }
            return loc;
        });
    }

    public void setFloat(String n, float v)                      { glUniform1f(uniform(n), v); }
    public void setInt(String n, int v)                          { glUniform1i(uniform(n), v); }
    public void setVec2(String n, float x, float y)              { glUniform2f(uniform(n), x, y); }
    public void setVec3(String n, float x, float y, float z)     { glUniform3f(uniform(n), x, y, z); }
    public void setVec4(String n, float x, float y, float z, float w) {
        glUniform4f(uniform(n), x, y, z, w);
    }
    /** Expects a column-major 3×3 matrix (ES requires transpose = false). */
    public void setMat3(String n, float[] columnMajor) {
        glUniformMatrix3fv(uniform(n), false, columnMajor);
    }
}
