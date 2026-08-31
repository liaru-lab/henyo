package link.liaru.henyo;

/** Resolves the public screenshot capture mode without depending on Android classes. */
public final class ScreenshotCaptureMode {
    public static final String AUTO = "auto";
    public static final String WINDOW = "window";
    public static final String DISPLAY = "display";

    private ScreenshotCaptureMode() {}

    public static Resolution resolve(String rawMode, int sdkInt, boolean includeIndicator) {
        String requested = rawMode == null || rawMode.trim().isEmpty()
                ? AUTO : rawMode.trim().toLowerCase(java.util.Locale.ROOT);
        if (!AUTO.equals(requested) && !WINDOW.equals(requested) && !DISPLAY.equals(requested)) {
            return Resolution.error("invalid_capture_mode");
        }
        if (WINDOW.equals(requested) && sdkInt < 34) {
            return Resolution.error("unsupported_window_capture");
        }
        String resolved = AUTO.equals(requested)
                ? (!includeIndicator && sdkInt >= 34 ? WINDOW : DISPLAY)
                : requested;
        return new Resolution(true, requested, resolved, "");
    }

    public static final class Resolution {
        public final boolean ok;
        public final String requestedMode;
        public final String resolvedMode;
        public final String error;

        private Resolution(boolean ok, String requestedMode, String resolvedMode, String error) {
            this.ok = ok;
            this.requestedMode = requestedMode;
            this.resolvedMode = resolvedMode;
            this.error = error;
        }

        static Resolution error(String error) {
            return new Resolution(false, "", "", error);
        }

        public boolean windowScoped() {
            return WINDOW.equals(resolvedMode);
        }
    }
}
