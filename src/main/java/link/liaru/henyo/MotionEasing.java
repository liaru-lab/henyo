package link.liaru.henyo;

final class MotionEasing {
    private MotionEasing() {}

    /**
     * A pronounced symmetric ease-in/ease-out with zero endpoint velocity.
     * The curve is monotonic and bounded, so cursor movement cannot overshoot.
     */
    static float doubleSmoothstep(float value) {
        float t = Math.max(0f, Math.min(1f, value));
        float eased = smoothstep(t);
        return smoothstep(eased);
    }

    private static float smoothstep(float value) {
        return value * value * (3f - 2f * value);
    }
}
