public final class SceneTransition {

    private final String targetSceneId;
    private final SceneTransitionEffect effect;
    private final double durationSeconds;

    private SceneTransition(String targetSceneId, SceneTransitionEffect effect, double durationSeconds) {
        this.targetSceneId = targetSceneId;
        this.effect = effect;
        this.durationSeconds = durationSeconds;
    }

    public static SceneTransition cutTo(String targetSceneId) {
        return new SceneTransition(targetSceneId, SceneTransitionEffect.CUT, 0.0);
    }

    public static SceneTransition fadeTo(String targetSceneId, double durationSeconds) {
        return new SceneTransition(targetSceneId, SceneTransitionEffect.FADE, durationSeconds);
    }

    public String targetSceneId() {
        return targetSceneId;
    }

    public SceneTransitionEffect effect() {
        return effect;
    }

    public double durationSeconds() {
        return durationSeconds;
    }
}
