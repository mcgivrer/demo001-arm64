public class ParticleSystem extends Entity {

    private final CameraState camera;

    public ParticleSystem(int width, int height, InputState input, long seed) {
        super(0, 0, width, height);
        camera = new CameraState(input, seed);
        // Insertion order = draw order: nebulae behind, starfield in front
        addBehavior(new NebulaFieldBehavior(width, height, camera, seed));
        addBehavior(new StarfieldBehavior(width, height, input, camera, seed));
    }

    @Override
    public void update(double dt) {
        camera.update(dt);   // one camera integration per frame, before all behaviors
        super.update(dt);
    }
}
