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

    // Cumulative orientation matrix M (row-major): after n frames a static
    // direction d0 appears at M·d0. Consumed as a mat3 uniform by the cloud
    // shader, so the puff VBO never needs re-uploading.
    private final double[] m = {1, 0, 0,  0, 1, 0,  0, 0, 1};
    private final float[]  mColumnMajor = new float[9];
    private long framesSinceOrtho;

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

        accumulateOrientation();
    }

    /**
     * M ← R_roll · R_pitch · R_yaw · M — the same composition the per-point
     * CPU rotation applies incrementally (yaw, then pitch, then roll).
     */
    private void accumulateOrientation() {
        // F = Rz(roll) · Rx(pitch) · Ry(yaw), row-major
        double f00 = cosRoll * cosYaw - sinRoll * sinPitch * sinYaw;
        double f01 = -sinRoll * cosPitch;
        double f02 = cosRoll * sinYaw + sinRoll * sinPitch * cosYaw;
        double f10 = sinRoll * cosYaw + cosRoll * sinPitch * sinYaw;
        double f11 = cosRoll * cosPitch;
        double f12 = sinRoll * sinYaw - cosRoll * sinPitch * cosYaw;
        double f20 = -cosPitch * sinYaw;
        double f21 = sinPitch;
        double f22 = cosPitch * cosYaw;

        double m00 = m[0], m01 = m[1], m02 = m[2];
        double m10 = m[3], m11 = m[4], m12 = m[5];
        double m20 = m[6], m21 = m[7], m22 = m[8];

        m[0] = f00 * m00 + f01 * m10 + f02 * m20;
        m[1] = f00 * m01 + f01 * m11 + f02 * m21;
        m[2] = f00 * m02 + f01 * m12 + f02 * m22;
        m[3] = f10 * m00 + f11 * m10 + f12 * m20;
        m[4] = f10 * m01 + f11 * m11 + f12 * m21;
        m[5] = f10 * m02 + f11 * m12 + f12 * m22;
        m[6] = f20 * m00 + f21 * m10 + f22 * m20;
        m[7] = f20 * m01 + f21 * m11 + f22 * m21;
        m[8] = f20 * m02 + f21 * m12 + f22 * m22;

        // Re-orthonormalise periodically (Gram-Schmidt) against fp drift
        if (++framesSinceOrtho >= 600) {
            orthonormalise();
            framesSinceOrtho = 0;
        }
    }

    private void orthonormalise() {
        double n0 = Math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2]);
        m[0] /= n0; m[1] /= n0; m[2] /= n0;
        double d = m[3] * m[0] + m[4] * m[1] + m[5] * m[2];
        m[3] -= d * m[0]; m[4] -= d * m[1]; m[5] -= d * m[2];
        double n1 = Math.sqrt(m[3] * m[3] + m[4] * m[4] + m[5] * m[5]);
        m[3] /= n1; m[4] /= n1; m[5] /= n1;
        // Third row = cross product of the first two → orthogonal by construction
        m[6] = m[1] * m[5] - m[2] * m[4];
        m[7] = m[2] * m[3] - m[0] * m[5];
        m[8] = m[0] * m[4] - m[1] * m[3];
    }

    /** Applies the cumulative orientation to a direction vector (CPU side). */
    public void applyOrientation(double[] dir, double[] out) {
        out[0] = m[0] * dir[0] + m[1] * dir[1] + m[2] * dir[2];
        out[1] = m[3] * dir[0] + m[4] * dir[1] + m[5] * dir[2];
        out[2] = m[6] * dir[0] + m[7] * dir[1] + m[8] * dir[2];
    }

    /** Cumulative orientation as a column-major mat3 for glUniformMatrix3fv. */
    public float[] orientationColumnMajor() {
        mColumnMajor[0] = (float) m[0]; mColumnMajor[3] = (float) m[1]; mColumnMajor[6] = (float) m[2];
        mColumnMajor[1] = (float) m[3]; mColumnMajor[4] = (float) m[4]; mColumnMajor[7] = (float) m[5];
        mColumnMajor[2] = (float) m[6]; mColumnMajor[5] = (float) m[7]; mColumnMajor[8] = (float) m[8];
        return mColumnMajor;
    }
}
