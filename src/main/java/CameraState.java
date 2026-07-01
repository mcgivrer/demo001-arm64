import java.util.Random;

/**
 * Shared camera state. Integrates the angular velocities (yaw/pitch/roll)
 * from user input — keyboard, mouse joystick, brake — or Brownian drift when
 * idle, exactly once per frame (called by ParticleSystem before behaviors
 * update). The resulting frame rotation is exposed as precomputed cos/sin
 * pairs so every layer (starfield, background clouds) rotates in sync.
 */
public class CameraState {

    private static final double MAX_VEL     = 0.35;  // rad/s
    private static final double DRIFT_ACC   = 0.06;  // rad/s² noise amplitude
    private static final double LERP_RATE   = 4.0;   // response speed for user control (1/s)
    private static final double BRAKE_DECAY = 8.0;   // deceleration rate when braking (1/s)
    private static final double MOUSE_DEAD  = 0.08;  // normalized dead zone for mouse joystick

    private final InputState input;
    private final Random     driftRng;    // Brownian drift noise only

    // Camera angular velocities (rad/s)
    private double velYaw = 0.05, velPitch = 0.02, velRoll = 0.01;

    // This frame's rotation, ready for consumption by behaviors
    public double cosYaw = 1, sinYaw, cosPitch = 1, sinPitch, cosRoll = 1, sinRoll;
    // Same rotation as raw angles (rad) — lets consumers track accumulated motion
    public double frameYaw, framePitch, frameRoll;

    public CameraState(InputState input, long seed) {
        this.input    = input;
        this.driftRng = new Random(seed);
    }

    public void update(double dt) {
        boolean anyKey = input.yawLeft || input.yawRight || input.pitchUp
                       || input.pitchDown || input.rollLeft || input.rollRight;
        boolean mouseActive = input.mouseDragging
                            && (Math.abs(input.mouseNormX) > MOUSE_DEAD
                                || Math.abs(input.mouseNormY) > MOUSE_DEAD);

        if (input.brake) {
            double decay = Math.max(0.0, 1.0 - BRAKE_DECAY * dt);
            velYaw *= decay; velPitch *= decay; velRoll *= decay;
        } else if (anyKey || mouseActive) {
            double tYaw = 0, tPitch = 0, tRoll = 0;
            if (input.yawLeft)   tYaw   = -MAX_VEL;
            if (input.yawRight)  tYaw   = +MAX_VEL;
            if (input.pitchUp)   tPitch = -MAX_VEL;
            if (input.pitchDown) tPitch = +MAX_VEL;
            if (input.rollLeft)  tRoll  = -MAX_VEL;
            if (input.rollRight) tRoll  = +MAX_VEL;
            if (mouseActive) {
                tYaw   = input.mouseNormX * MAX_VEL;
                tPitch = input.mouseNormY * MAX_VEL;
            }
            velYaw   += (tYaw   - velYaw)   * LERP_RATE * dt;
            velPitch += (tPitch - velPitch) * LERP_RATE * dt;
            velRoll  += (tRoll  - velRoll)  * LERP_RATE * dt;
        } else {
            // Brownian angular drift on 3 axes
            velYaw   += driftRng.nextGaussian() * DRIFT_ACC * dt;
            velPitch += driftRng.nextGaussian() * DRIFT_ACC * dt;
            velRoll  += driftRng.nextGaussian() * DRIFT_ACC * dt;
            velYaw   = Math.clamp(velYaw,   -MAX_VEL, MAX_VEL);
            velPitch = Math.clamp(velPitch, -MAX_VEL, MAX_VEL);
            velRoll  = Math.clamp(velRoll,  -MAX_VEL, MAX_VEL);
        }

        frameYaw   = velYaw   * dt;
        framePitch = velPitch * dt;
        frameRoll  = velRoll  * dt;

        cosYaw   = Math.cos(frameYaw);   sinYaw   = Math.sin(frameYaw);
        cosPitch = Math.cos(framePitch); sinPitch = Math.sin(framePitch);
        cosRoll  = Math.cos(frameRoll);  sinRoll  = Math.sin(frameRoll);
    }
}
