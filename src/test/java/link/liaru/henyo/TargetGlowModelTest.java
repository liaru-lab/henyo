package link.liaru.henyo;

public final class TargetGlowModelTest {
    public static void main(String[] args) {
        expect(TargetGlowModel.breath(1_488L) > 0.99f, "inhale reaches its peak");
        expect(TargetGlowModel.breath(4_500L) < 0.01f, "cycle includes a quiet pause");
        expect(TargetGlowModel.breath(-1L) >= 0f, "negative clocks remain bounded");
        expect(close(TargetGlowModel.intensity(0f, 0f), 0.6724f),
                "quiet glow remains visible");
        expect(close(TargetGlowModel.intensity(1f, 1f), 1f),
                "active glow reaches full intensity");
        expect(TargetGlowModel.scanFadeAlpha(0.5f) > 0.99f,
                "scan glow peaks at its core");
        expect(TargetGlowModel.scanFadeAlpha(0f) < 0.01f
                        && TargetGlowModel.scanFadeAlpha(1f) < 0.01f,
                "scan glow fades out symmetrically at both edges");
        expect(close(TargetGlowModel.scanFadeAlpha(0.25f),
                        TargetGlowModel.scanFadeAlpha(0.75f)),
                "scan glow is vertically symmetric");
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
