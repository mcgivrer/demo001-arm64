public class ParticleSystem extends Entity {
    public ParticleSystem(int width, int height, InputState input, long seed) {
        super(0, 0, width, height);
        addBehavior(new StarfieldBehavior(width, height, input, seed));
    }
}
