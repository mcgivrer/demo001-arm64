public class InputState {
    public boolean yawLeft, yawRight;
    public boolean pitchUp, pitchDown;
    public boolean rollLeft, rollRight;
    public boolean brake;
    public boolean thrustUp, thrustDown;
    public boolean showHelp = true;
    public boolean startRequested;
    public boolean escapeRequested;
    public boolean uiActivateRequested;
    public int     uiTabStep;
    public int     uiFocusStep;
    public boolean uiClickRequested;
    public double  uiClickX, uiClickY;
    public double  pointerX, pointerY;
    public boolean mouseDragging;
    public double  mouseNormX, mouseNormY;

    public boolean consumeStartRequested() {
        boolean requested = startRequested;
        startRequested = false;
        return requested;
    }

    public boolean consumeEscapeRequested() {
        boolean requested = escapeRequested;
        escapeRequested = false;
        return requested;
    }

    public boolean consumeUiActivateRequested() {
        boolean requested = uiActivateRequested;
        uiActivateRequested = false;
        return requested;
    }

    public int consumeUiTabStep() {
        int step = uiTabStep;
        uiTabStep = 0;
        return step;
    }

    public int consumeUiFocusStep() {
        int step = uiFocusStep;
        uiFocusStep = 0;
        return step;
    }

    public boolean consumeUiClickRequested() {
        boolean requested = uiClickRequested;
        uiClickRequested = false;
        return requested;
    }
}
