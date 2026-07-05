import java.util.ArrayList;
import java.util.List;

public class TitleScene implements Scene {

    private final InputState input;
    private final String title;
    private final String subtitle;
    private final String startHint;
    private final String quitLabel;

    private final List<Entity> entities = new ArrayList<>();
    private final List<ControlUI> controls = new ArrayList<>();
    private TextObject titleText;
    private TextObject subtitleText;

    private int width;
    private int height;
    private int focusedControl = 0;
    private SceneTransition pendingTransition;

    public TitleScene(int width, int height, InputState input,
                      String title, String subtitle, String startHint, String quitLabel) {
        this.width = width;
        this.height = height;
        this.input = input;
        this.title = title;
        this.subtitle = subtitle;
        this.startHint = startHint;
        this.quitLabel = quitLabel;

        rebuildEntities();
    }

    @Override
    public void init(RenderContext ctx) {
        for (Entity entity : entities) entity.init(ctx);
    }

    @Override
    public void resize(int width, int height) {
        this.width = width;
        this.height = height;
        layoutEntities();
    }

    @Override
    public void update(double dt) {
        input.consumeEscapeRequested();

        int navStep = input.consumeUiTabStep() + input.consumeUiFocusStep();
        if (navStep != 0 && !controls.isEmpty()) {
            while (navStep != 0) {
                int direction = navStep > 0 ? 1 : -1;
                focusedControl = (focusedControl + direction + controls.size()) % controls.size();
                navStep -= direction;
            }
            updateControlFocus();
        }

        for (ControlUI control : controls) {
            control.setHovered(control.contains(input.pointerX, input.pointerY));
        }

        if (input.consumeUiClickRequested()) {
            for (int i = 0; i < controls.size(); i++) {
                ControlUI control = controls.get(i);
                if (control.contains(input.uiClickX, input.uiClickY)) {
                    focusedControl = i;
                    updateControlFocus();
                    control.activate();
                    break;
                }
            }
        }

        if (input.consumeUiActivateRequested() || input.consumeStartRequested()) {
            if (!controls.isEmpty()) controls.get(focusedControl).activate();
        }

        for (Entity entity : entities) {
            entity.update(dt);
        }
    }

    @Override
    public void draw(RenderContext ctx) {
        ctx.quads.fill(0, 0, width, height, 2 / 255f, 5 / 255f, 13 / 255f, 1f);

        float panelW = Math.min(width - 80f, 680f);
        float panelH = Math.min(height - 80f, 360f);
        float panelX = (width - panelW) * 0.5f;
        float panelY = (height - panelH) * 0.5f;

        ctx.quads.fillRounded(panelX, panelY, panelW, panelH, 14,
            8 / 255f, 14 / 255f, 30 / 255f, 0.82f,
            140 / 255f, 190 / 255f, 1f, 0.45f);

        for (Entity entity : entities) {
            entity.draw(ctx);
        }

        String navHint = "TAB / SHIFT+TAB / FLECHES : navigation  |  ENTREE : valider";
        drawCentered(ctx, navHint, (int) (panelY + panelH - 18), 10f, false,
            145 / 255f, 180 / 255f, 1f, 0.85f);
    }

    @Override
    public SceneTransition pollTransition() {
        SceneTransition out = pendingTransition;
        pendingTransition = null;
        return out;
    }

    private void rebuildEntities() {
        entities.clear();
        controls.clear();

        titleText = new TextObject(title, 0f, 30f, true,
            235 / 255f, 245 / 255f, 1f, 1f);
        subtitleText = new TextObject(subtitle, 0f, 12f, false,
            170 / 255f, 200 / 255f, 1f, 0.95f);

        ButtonObject startButton = new ButtonObject(0, 0, 0, 0, startHint,
            b -> pendingTransition = SceneTransition.fadeTo("travel", 0.45));
        ButtonObject quitButton = new ButtonObject(0, 0, 0, 0, quitLabel,
            b -> pendingTransition = SceneTransition.cutTo("quit"));

        entities.add(titleText);
        entities.add(subtitleText);
        entities.add(startButton);
        entities.add(quitButton);
        controls.add(startButton);
        controls.add(quitButton);

        focusedControl = 0;
        updateControlFocus();
        layoutEntities();
    }

    private void layoutEntities() {
        float panelW = Math.min(width - 80f, 680f);
        float panelH = Math.min(height - 80f, 360f);
        float panelX = (width - panelW) * 0.5f;
        float panelY = (height - panelH) * 0.5f;

        titleText.y = panelY + 96;
        subtitleText.y = panelY + 144;

        float buttonW = Math.min(panelW - 120f, 420f);
        float buttonH = 42f;
        float gap = 12f;
        float buttonX = (width - buttonW) * 0.5f;
        float totalButtonsH = controls.size() * buttonH + (controls.size() - 1) * gap;
        // Reserve a footer area for the navigation hint and keep buttons below subtitle.
        float footerReserve = 56f;
        float buttonY = panelY + panelH - footerReserve - totalButtonsH;
        for (int i = 0; i < controls.size(); i++) {
            ControlUI control = controls.get(i);
            control.x = buttonX;
            control.y = buttonY + i * (buttonH + gap);
            control.width = buttonW;
            control.height = buttonH;
        }
    }

    private void updateControlFocus() {
        for (int i = 0; i < controls.size(); i++) {
            controls.get(i).setFocused(i == focusedControl);
        }
    }

    private void drawCentered(RenderContext ctx, String text, int baselineY,
                              float sizePt, boolean bold,
                              float r, float g, float b, float a) {
        int w = ctx.text.stringWidth(text, sizePt, bold);
        ctx.text.draw(text, (width - w) / 2f, baselineY, sizePt, bold, r, g, b, a);
    }
}
