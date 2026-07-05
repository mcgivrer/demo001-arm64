import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.lwjgl.glfw.GLFW.*;

public class MapScene implements Scene {

    private static final int STAR_COUNT = 140;
    private static final double GRID_EXTENT = 1.8;
    private static final double GRID_MINOR_STEP = 0.2;
    private static final double GRID_MAJOR_STEP = 0.8;
    private static final double CAMERA_NEAR = 0.12;
    private static final double CAMERA_MIN_DIST = 1.4;
    private static final double CAMERA_MAX_DIST = 7.5;

    private static final double[] SPECTRAL_CUM = {0.760, 0.880, 0.956, 0.986, 0.992, 0.993, 1.000};
    private static final String[] SPECTRAL_LABEL = {"M", "K", "G", "F", "A", "B", "O"};
    private static final float[][] SPECTRAL_COLOR = {
        {255 / 255f, 140 / 255f,  80 / 255f},
        {255 / 255f, 185 / 255f, 110 / 255f},
        {255 / 255f, 235 / 255f, 195 / 255f},
        {245 / 255f, 245 / 255f, 255 / 255f},
        {200 / 255f, 215 / 255f, 255 / 255f},
        {170 / 255f, 191 / 255f, 255 / 255f},
        {155 / 255f, 176 / 255f, 255 / 255f},
    };

    private static final class MapStar {
        String name;
        int spectral;
        float brightness;
        double gx;
        double gy;
        double gz;

        float sx;
        float sy;
        float planeSx;
        float planeSy;
        float depth;
        boolean visible;
        boolean planeVisible;
    }

    private final InputState input;
    private final List<MapStar> stars = new ArrayList<>();
    private SceneTransition pendingTransition;

    private int width;
    private int height;
    private int hoverIndex = -1;

    private double focusX;
    private double focusZ;
    private double cameraYaw = -0.75;
    private double cameraPitch = 0.58;
    private double cameraDist = 3.2;

    private double prevPointerX;
    private double prevPointerY;
    private boolean prevDragging;
    private boolean prevOrbitDragging;

    public MapScene(int width, int height, InputState input, long seed) {
        this.width = width;
        this.height = height;
        this.input = input;

        Random rng = new Random(seed ^ 0x4D41505F53434E45L);
        for (int i = 0; i < STAR_COUNT; i++) {
            MapStar s = new MapStar();
            s.name = StarNameGenerator.generate(rng);
            s.spectral = pickSpectral(rng.nextDouble());
            s.brightness = (float) (0.35 + rng.nextDouble() * 0.65);

            // Galaxy map coordinates: x/z on plane, y as elevation above plane.
            s.gx = (rng.nextDouble() * 2.0 - 1.0);
            s.gz = (rng.nextDouble() * 2.0 - 1.0);
            s.gy = Math.max(-1.0, Math.min(1.0, rng.nextGaussian() * 0.32));
            stars.add(s);
        }
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void update(double dt) {
        double panSpeed = 1.2 * dt * (cameraDist / 3.2);
        if (input.yawLeft) focusX -= panSpeed;
        if (input.yawRight) focusX += panSpeed;
        if (input.pitchUp) focusZ -= panSpeed;
        if (input.pitchDown) focusZ += panSpeed;

        if (input.mapZoomIn) cameraDist -= 2.5 * dt;
        if (input.mapZoomOut) cameraDist += 2.5 * dt;

        double orbitSpeed = 1.45 * dt;
        if (input.mapOrbitLeft) cameraYaw -= orbitSpeed;
        if (input.mapOrbitRight) cameraYaw += orbitSpeed;

        double wheel = input.consumeScrollDeltaY();
        if (wheel != 0.0) cameraDist *= Math.exp(-wheel * 0.12);

        if (input.mouseDragging) {
            if (prevDragging) {
                double dx = input.pointerX - prevPointerX;
                double dy = input.pointerY - prevPointerY;
                double dragScale = 0.0045 * (cameraDist / 3.2);
                focusX -= dx * dragScale;
                focusZ += dy * dragScale;
            }
        }
        if (input.mouseOrbitDragging) {
            if (prevOrbitDragging) {
                double dx = input.pointerX - prevPointerX;
                double dy = input.pointerY - prevPointerY;
                cameraYaw += dx * 0.0052;
                cameraPitch += dy * 0.0042;
            }
        }
        prevPointerX = input.pointerX;
        prevPointerY = input.pointerY;
        prevDragging = input.mouseDragging;
        prevOrbitDragging = input.mouseOrbitDragging;

        cameraDist = Math.max(CAMERA_MIN_DIST, Math.min(CAMERA_MAX_DIST, cameraDist));
        cameraYaw = normalizeAngle(cameraYaw);
        cameraPitch = normalizeAngle(cameraPitch);

        hoverIndex = -1;
        float bestD2 = Float.MAX_VALUE;
        for (int i = 0; i < stars.size(); i++) {
            MapStar s = stars.get(i);
            if (!s.visible) continue;
            float dx = (float) input.pointerX - s.sx;
            float dy = (float) input.pointerY - s.sy;
            float d2 = dx * dx + dy * dy;
            if (d2 < 100f && d2 < bestD2) {
                bestD2 = d2;
                hoverIndex = i;
            }
        }
    }

    @Override
    public boolean onKeyPressed(int key, int mods) {
        return handleKey(key, true);
    }

    @Override
    public boolean onKeyReleased(int key, int mods) {
        return handleKey(key, false);
    }

    @Override
    public boolean onMouseButtonPressed(int button, double x, double y, int mods) {
        input.pointerX = x;
        input.pointerY = y;
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            input.mouseDragging = true;
            return true;
        }
        if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            input.mouseOrbitDragging = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseButtonReleased(int button, double x, double y, int mods) {
        input.pointerX = x;
        input.pointerY = y;
        if (button == GLFW_MOUSE_BUTTON_LEFT) {
            input.mouseDragging = false;
            return true;
        }
        if (button == GLFW_MOUSE_BUTTON_RIGHT) {
            input.mouseOrbitDragging = false;
            return true;
        }
        return false;
    }

    @Override
    public boolean onMouseMoved(double x, double y) {
        input.pointerX = x;
        input.pointerY = y;
        return input.mouseDragging || input.mouseOrbitDragging;
    }

    @Override
    public boolean onMouseScrolled(double xoffset, double yoffset) {
        input.scrollDeltaY += yoffset;
        return true;
    }

    @Override
    public void draw(RenderContext ctx) {
        ctx.quads.fill(0, 0, width, height, 1 / 255f, 4 / 255f, 10 / 255f, 1f);

        float mapX = 54;
        float mapY = 56;
        float mapW = width - 108;
        float mapH = height - 112;

        ctx.quads.fillRounded(mapX, mapY, mapW, mapH, 14,
            10 / 255f, 17 / 255f, 32 / 255f, 0.88f,
            120 / 255f, 162 / 255f, 1f, 0.38f);

        drawGrid3D(ctx, mapX, mapY, mapW, mapH);

        for (MapStar s : stars) {
            projectStar(s, mapX, mapY, mapW, mapH);
        }

        // Draw depth connectors first, then stars on top.
        for (MapStar s : stars) {
            if (!s.visible || !s.planeVisible) continue;
            float[] c = SPECTRAL_COLOR[s.spectral];

            drawDottedLine(ctx, s.sx, s.sy, s.planeSx, s.planeSy,
                3f, 3f, 1.8f,
                c[0], c[1], c[2], 0.55f + s.brightness * 0.25f);
        }

        for (MapStar s : stars) {
            if (!s.visible) continue;
            float[] c = SPECTRAL_COLOR[s.spectral];
            float r = 2.1f + s.brightness * 2.2f;
            ctx.quads.fillRounded(s.sx - r, s.sy - r, r * 2f, r * 2f, r,
                c[0], c[1], c[2], 0.96f,
                c[0], c[1], c[2], 0.96f);
        }

        if (hoverIndex >= 0) {
            drawStarInfoPanel(ctx, stars.get(hoverIndex));
        }

        ctx.text.draw("Carte Stellaire", mapX + 14, mapY + 22, 13f, true,
            230 / 255f, 242 / 255f, 1f, 1f);
        ctx.text.draw("Fleches pan | Q/E orbite | clic droit orbite | PgUp/PgDn+molette zoom | ESC", mapX + mapW - 610, mapY + 22, 10f, false,
            170 / 255f, 200 / 255f, 1f, 0.95f);
    }

    @Override
    public SceneTransition pollTransition() {
        SceneTransition out = pendingTransition;
        pendingTransition = null;
        return out;
    }

    private boolean handleKey(int key, boolean down) {
        switch (key) {
            case GLFW_KEY_LEFT, GLFW_KEY_A -> {
                input.yawLeft = down;
                return true;
            }
            case GLFW_KEY_RIGHT, GLFW_KEY_D -> {
                input.yawRight = down;
                return true;
            }
            case GLFW_KEY_UP, GLFW_KEY_W -> {
                input.pitchUp = down;
                return true;
            }
            case GLFW_KEY_DOWN, GLFW_KEY_S -> {
                input.pitchDown = down;
                return true;
            }
            case GLFW_KEY_Q -> {
                input.mapOrbitLeft = down;
                return true;
            }
            case GLFW_KEY_E -> {
                input.mapOrbitRight = down;
                return true;
            }
            case GLFW_KEY_PAGE_UP -> {
                input.mapZoomIn = down;
                return true;
            }
            case GLFW_KEY_PAGE_DOWN -> {
                input.mapZoomOut = down;
                return true;
            }
            case GLFW_KEY_ESCAPE -> {
                if (down && pendingTransition == null) {
                    pendingTransition = SceneTransition.zoomTo("travel", 0.45);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static double normalizeAngle(double angle) {
        double twoPi = Math.PI * 2.0;
        while (angle > Math.PI) angle -= twoPi;
        while (angle < -Math.PI) angle += twoPi;
        return angle;
    }

    private static int pickSpectral(double r) {
        for (int i = 0; i < SPECTRAL_CUM.length; i++) {
            if (r < SPECTRAL_CUM[i]) return i;
        }
        return SPECTRAL_CUM.length - 1;
    }

    private void drawGrid3D(RenderContext ctx, float mapX, float mapY, float mapW, float mapH) {
        for (double gx = -GRID_EXTENT; gx <= GRID_EXTENT + 1e-6; gx += GRID_MINOR_STEP) {
            boolean major = Math.abs(gx / GRID_MAJOR_STEP - Math.round(gx / GRID_MAJOR_STEP)) < 1e-5;
            float alpha = major ? 0.28f : 0.10f;
            drawProjectedSegment(ctx,
                gx, 0, -GRID_EXTENT,
                gx, 0,  GRID_EXTENT,
                mapX, mapY, mapW, mapH,
                130 / 255f, 170 / 255f, 1f, alpha,
                major ? 1.6f : 1.0f);
        }

        for (double gz = -GRID_EXTENT; gz <= GRID_EXTENT + 1e-6; gz += GRID_MINOR_STEP) {
            boolean major = Math.abs(gz / GRID_MAJOR_STEP - Math.round(gz / GRID_MAJOR_STEP)) < 1e-5;
            float alpha = major ? 0.26f : 0.08f;
            drawProjectedSegment(ctx,
                -GRID_EXTENT, 0, gz,
                 GRID_EXTENT, 0, gz,
                mapX, mapY, mapW, mapH,
                130 / 255f, 170 / 255f, 1f, alpha,
                major ? 1.5f : 1.0f);
        }

        // Highlight central galactic axis on plane.
        drawProjectedSegment(ctx,
            -GRID_EXTENT, 0, 0,
             GRID_EXTENT, 0, 0,
            mapX, mapY, mapW, mapH,
            190 / 255f, 215 / 255f, 1f, 0.46f,
            1.8f);
    }

    private void projectStar(MapStar s, float mapX, float mapY, float mapW, float mapH) {
        s.visible = projectPoint(s.gx, s.gy, s.gz, mapX, mapY, mapW, mapH, s);

        MapStar planePoint = new MapStar();
        s.planeVisible = projectPoint(s.gx, 0.0, s.gz, mapX, mapY, mapW, mapH, planePoint);
        s.planeSx = planePoint.sx;
        s.planeSy = planePoint.sy;
    }

    private boolean projectPoint(double wx, double wy, double wz,
                                 float mapX, float mapY, float mapW, float mapH,
                                 MapStar out) {
        double x = wx - focusX;
        double y = wy;
        double z = wz - focusZ;

        double cy = Math.cos(cameraYaw);
        double sy = Math.sin(cameraYaw);
        double x1 = x * cy - z * sy;
        double z1 = x * sy + z * cy;

        double cp = Math.cos(cameraPitch);
        double sp = Math.sin(cameraPitch);
        double y2 = y * cp - z1 * sp;
        double z2 = y * sp + z1 * cp;

        double zCam = z2 + cameraDist;
        if (zCam <= CAMERA_NEAR) return false;

        float focal = Math.min(mapW, mapH) * 0.65f;
        float sx = (float) (mapX + mapW * 0.5 + (x1 / zCam) * focal);
        float syScreen = (float) (mapY + mapH * 0.5 - (y2 / zCam) * focal);

        float inset = 10f;
        if (sx < mapX + inset || sx > mapX + mapW - inset
            || syScreen < mapY + inset || syScreen > mapY + mapH - inset) {
            return false;
        }

        out.sx = sx;
        out.sy = syScreen;
        out.depth = (float) zCam;
        return true;
    }

    private void drawProjectedSegment(RenderContext ctx,
                                      double x1, double y1, double z1,
                                      double x2, double y2, double z2,
                                      float mapX, float mapY, float mapW, float mapH,
                                      float r, float g, float b, float a,
                                      float thickness) {
        MapStar p1 = new MapStar();
        MapStar p2 = new MapStar();
        if (!projectPoint(x1, y1, z1, mapX, mapY, mapW, mapH, p1)) return;
        if (!projectPoint(x2, y2, z2, mapX, mapY, mapW, mapH, p2)) return;
        drawDottedLine(ctx, p1.sx, p1.sy, p2.sx, p2.sy,
            1000f, 0f, thickness, r, g, b, a);
    }

    private static void drawDottedLine(RenderContext ctx,
                                       float x1, float y1, float x2, float y2,
                                       float dash, float gap, float thickness,
                                       float r, float g, float b, float a) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.001f) return;

        float ux = dx / len;
        float uy = dy / len;
        float t = 0f;
        while (t < len) {
            float seg = Math.min(dash, len - t);
            float p = t;
            while (p <= t + seg) {
                float px = x1 + ux * p;
                float py = y1 + uy * p;
                ctx.quads.fill(px - thickness * 0.5f, py - thickness * 0.5f,
                    thickness, thickness, r, g, b, a);
                p += Math.max(1f, thickness * 0.9f);
            }
            t += dash + gap;
        }
    }

    private void drawStarInfoPanel(RenderContext ctx, MapStar s) {
        String line1 = "Nom: " + s.name;
        String line2 = "Type spectral: " + SPECTRAL_LABEL[s.spectral];
        String line3 = String.format("Brillance: %.2f", s.brightness);
        String line4 = String.format("Elevation: %.2f kpc", s.gy * 8.0);

        float panelW = Math.max(
            Math.max(ctx.text.stringWidth(line1, 10f, true), ctx.text.stringWidth(line2, 10f, false)),
            Math.max(ctx.text.stringWidth(line3, 10f, false), ctx.text.stringWidth(line4, 10f, false))
        ) + 24;
        float panelH = 82;

        float px = s.sx + 28;
        float py = s.sy - panelH * 0.5f;
        if (px + panelW > width - 12) px = s.sx - panelW - 28;
        if (py < 12) py = 12;
        if (py + panelH > height - 12) py = height - panelH - 12;

        float anchorX = px > s.sx ? px : px + panelW;
        float anchorY = py + panelH * 0.5f;
        drawDottedLine(ctx, s.sx, s.sy, anchorX, anchorY,
            3f, 4f, 1.5f,
            1f, 1f, 1f, 0.80f);

        ctx.quads.fillRounded(px, py, panelW, panelH, 10,
            12 / 255f, 16 / 255f, 24 / 255f, 0.78f,
            1f, 1f, 1f, 0.35f);

        ctx.text.draw(line1, px + 10, py + 18, 10f, true,
            235 / 255f, 242 / 255f, 1f, 1f);
        ctx.text.draw(line2, px + 10, py + 36, 10f, false,
            190 / 255f, 218 / 255f, 1f, 0.95f);
        ctx.text.draw(line3, px + 10, py + 54, 10f, false,
            180 / 255f, 206 / 255f, 1f, 0.95f);
        ctx.text.draw(line4, px + 10, py + 72, 10f, false,
            180 / 255f, 206 / 255f, 1f, 0.95f);
    }
}
