package link.liaru.henyo;

public final class ScreenshotCaptureModeTest {
    public static void main(String[] args) {
        autoPreservesLegacySelection();
        explicitModesAreStable();
        unsupportedWindowCaptureFailsClosed();
        invalidModeFailsClosed();
        System.out.println("ScreenshotCaptureModeTest passed");
    }

    private static void autoPreservesLegacySelection() {
        expect(ScreenshotCaptureMode.resolve(null, 34, false).windowScoped(),
                "API 34 indicator-free auto capture should remain window scoped");
        expect(!ScreenshotCaptureMode.resolve("auto", 34, true).windowScoped(),
                "indicator-inclusive auto capture should remain display scoped");
        expect(!ScreenshotCaptureMode.resolve("auto", 33, false).windowScoped(),
                "pre-34 auto capture should remain display scoped");
    }

    private static void explicitModesAreStable() {
        ScreenshotCaptureMode.Resolution window = ScreenshotCaptureMode.resolve("window", 34, true);
        ScreenshotCaptureMode.Resolution display = ScreenshotCaptureMode.resolve("display", 34, false);
        expect(window.ok && window.windowScoped(), "explicit window mode should be window scoped");
        expect(display.ok && !display.windowScoped(), "explicit display mode should be display scoped");
    }

    private static void unsupportedWindowCaptureFailsClosed() {
        ScreenshotCaptureMode.Resolution result = ScreenshotCaptureMode.resolve("window", 33, false);
        expect(!result.ok && "unsupported_window_capture".equals(result.error),
                "window capture must not silently fall back on unsupported Android versions");
    }

    private static void invalidModeFailsClosed() {
        ScreenshotCaptureMode.Resolution result = ScreenshotCaptureMode.resolve("workspace", 34, false);
        expect(!result.ok && "invalid_capture_mode".equals(result.error),
                "unknown capture modes must be rejected");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
