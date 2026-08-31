package link.liaru.henyo;

public final class TargetPresentationPolicyTest {
    public static void main(String[] args) {
        retainsPersistentDisplayChrome();
        retainsAttachedWindowChrome();
        clipsRealOccluders();
        rejectsInvalidGeometry();
        System.out.println("TargetPresentationPolicyTest passed");
    }

    private static void retainsPersistentDisplayChrome() {
        expect(retained(0, 0, 1080, 2400, 0, 0, 1080, 96, true),
                "top system strip should remain in the presentation contour");
        expect(retained(0, 0, 1080, 2400, 0, 2280, 1080, 2400, true),
                "bottom system strip should remain in the presentation contour");
        expect(retained(0, 0, 2400, 1080, 2280, 0, 2400, 1080, true),
                "side system strip should remain in the presentation contour");
    }

    private static void retainsAttachedWindowChrome() {
        expect(retained(180, 200, 900, 1400, 180, 200, 900, 248, true),
                "thin attached title chrome should remain visible");
    }

    private static void clipsRealOccluders() {
        expect(!retained(0, 0, 1080, 2400, 0, 1400, 1080, 2400, true),
                "large input surface should still clip presentation");
        expect(!retained(0, 0, 1080, 2400, 0, 600, 1080, 700, true),
                "an internal system surface is not persistent edge chrome");
        expect(!retained(0, 0, 1080, 2400, 0, 0, 1080, 96, false),
                "application windows should clip even when strip-shaped");
        expect(!retained(0, 0, 1080, 2400, 300, 0, 700, 100, true),
                "a short system popup is not full-width chrome");
    }

    private static void rejectsInvalidGeometry() {
        expect(!retained(0, 0, 0, 2400, 0, 0, 100, 100, true),
                "empty target bounds should be rejected");
        expect(!retained(0, 0, 1080, 2400, 1200, 0, 1300, 100, true),
                "non-overlapping chrome should be rejected");
    }

    private static boolean retained(
            int tl, int tt, int tr, int tb, int bl, int bt, int br, int bb,
            boolean systemWindow) {
        return TargetPresentationPolicy.retainsSystemChrome(
                tl, tt, tr, tb, bl, bt, br, bb, systemWindow);
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
