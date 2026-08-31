package link.liaru.henyo;

public final class TargetGlowModelTest {
    public static void main(String[] args) {
        expect(close(TargetGlowModel.depthPx(100, 140, 1f), 4f), "small windows use minimum depth");
        expect(close(TargetGlowModel.depthPx(2000, 1200, 1f), 11f), "large windows use maximum depth");
        expect(TargetGlowModel.breath(1_488L) > 0.99f, "inhale reaches its peak");
        expect(TargetGlowModel.breath(4_500L) < 0.01f, "cycle includes a quiet pause");
        expect(TargetGlowModel.breath(-1L) >= 0f, "negative clocks remain bounded");
        expect(close(TargetGlowModel.intensity(0f, 0f), 0.6724f),
                "quiet glow remains visible");
        expect(close(TargetGlowModel.intensity(1f, 1f), 1f),
                "active glow reaches full intensity");
        expect(TargetGlowModel.innerGlowAlpha(0f, 30f) > 0.99f,
                "inner glow is strongest at the contour");
        expect(close(TargetGlowModel.innerGlowAlpha(30f, 30f), 0f),
                "inner glow reaches zero at its depth");
        expect(TargetGlowModel.innerGlowAlpha(15f, 30f) > 0f
                        && TargetGlowModel.innerGlowAlpha(15f, 30f) < 1f,
                "inner glow fades continuously");
        expect(TargetGlowModel.gradientColor(0f) == 0xff43d2c4,
                "gradient begins with cyan");
        expect(TargetGlowModel.gradientColor(1f) == 0xff43d2c4,
                "gradient closes with cyan");
        System.out.println("TargetGlowModelTest passed");
    }

    private static boolean close(float left, float right) {
        return Math.abs(left - right) < 0.01f;
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
