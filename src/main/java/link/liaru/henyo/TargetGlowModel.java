package link.liaru.henyo;

final class TargetGlowModel {
    private static final int[] GRADIENT_COLORS = {
            0xff43d2c4,
            0xff5c9ade,
            0xff9d7ecc,
            0xff43d2c4
    };
    private static final float[] GRADIENT_STOPS = {0f, 0.38f, 0.72f, 1f};

    private TargetGlowModel() {}

    static float depthPx(int widthPx, int heightPx, float density) {
        float safeDensity = Math.max(0.5f, density);
        float shortSideDp = Math.min(widthPx, heightPx) / safeDensity;
        return Math.max(4f, Math.min(11f, shortSideDp * 0.024f)) * safeDensity;
    }

    static float breath(long nowMs) {
        float t = Math.floorMod(nowMs, 4_800L) / 4_800f;
        if (t < 0.31f) return smooth(t / 0.31f);
        if (t < 0.36f) return 1f;
        if (t < 0.88f) return 1f - smooth((t - 0.36f) / 0.52f);
        return 0f;
    }

    static float intensity(float activityEnvelope, float breath) {
        float activity = 0.82f + 0.18f * clamp(activityEnvelope);
        return activity * (0.82f + 0.18f * clamp(breath));
    }

    static float innerGlowAlpha(float distancePx, float depthPx) {
        if (depthPx <= 0f || distancePx >= depthPx) return 0f;
        float x = clamp(distancePx / depthPx);
        return 1f - x * x * (3f - 2f * x);
    }

    static int gradientColor(float position) {
        float value = clamp(position);
        int index = 0;
        while (index < GRADIENT_STOPS.length - 2 && value > GRADIENT_STOPS[index + 1]) {
            index++;
        }
        float start = GRADIENT_STOPS[index];
        float end = GRADIENT_STOPS[index + 1];
        float amount = end <= start ? 0f : (value - start) / (end - start);
        int left = GRADIENT_COLORS[index];
        int right = GRADIENT_COLORS[index + 1];
        return 0xff000000
                | (Math.round(mix((left >> 16) & 0xff, (right >> 16) & 0xff, amount)) << 16)
                | (Math.round(mix((left >> 8) & 0xff, (right >> 8) & 0xff, amount)) << 8)
                | Math.round(mix(left & 0xff, right & 0xff, amount));
    }

    private static float smooth(float value) {
        float x = clamp(value);
        float first = x * x * (3f - 2f * x);
        return first * first * (3f - 2f * first);
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float mix(float from, float to, float amount) {
        return from + (to - from) * clamp(amount);
    }
}
