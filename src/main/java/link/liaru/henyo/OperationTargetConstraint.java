package link.liaru.henyo;

import java.util.Map;

/** Optional, operation-scoped constraints used to resolve an Android window. */
public final class OperationTargetConstraint {
    public static final int UNSPECIFIED = -1;

    public final String packageName;
    public final int windowId;
    public final int displayId;
    public final boolean valid;
    public final String error;

    private OperationTargetConstraint(String packageName, int windowId, int displayId,
                                      boolean valid, String error) {
        this.packageName = packageName == null ? "" : packageName.trim();
        this.windowId = windowId;
        this.displayId = displayId;
        this.valid = valid;
        this.error = error == null ? "" : error;
    }

    public static OperationTargetConstraint none() {
        return new OperationTargetConstraint("", UNSPECIFIED, UNSPECIFIED, true, "");
    }

    public static OperationTargetConstraint exact(String packageName, int windowId, int displayId) {
        return new OperationTargetConstraint(packageName, windowId, displayId, true, "");
    }

    public static OperationTargetConstraint from(Map<String, String> params) {
        if (params == null) return none();
        String packageName = params.getOrDefault("package", "").trim();
        ParsedInt window = parseOptionalNonNegative(params.get("windowId"));
        if (!window.valid) return invalid("invalid_window_id");
        ParsedInt display = parseOptionalNonNegative(params.get("displayId"));
        if (!display.valid) return invalid("invalid_display_id");
        return new OperationTargetConstraint(packageName, window.value, display.value, true, "");
    }

    private static OperationTargetConstraint invalid(String error) {
        return new OperationTargetConstraint("", UNSPECIFIED, UNSPECIFIED, false, error);
    }

    public boolean hasPackage() {
        return !packageName.isEmpty();
    }

    public boolean hasWindow() {
        return windowId >= 0;
    }

    public boolean hasDisplay() {
        return displayId >= 0;
    }

    public boolean specified() {
        return hasPackage() || hasWindow() || hasDisplay();
    }

    public boolean accepts(WindowTargetResolver.Candidate candidate) {
        if (candidate == null) return false;
        if (hasPackage() && !packageName.equals(candidate.packageName)) return false;
        return !hasDisplay() || displayId == candidate.displayId;
    }

    private static ParsedInt parseOptionalNonNegative(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ParsedInt(true, UNSPECIFIED);
        try {
            int value = Integer.parseInt(raw.trim());
            return new ParsedInt(value >= 0, value);
        } catch (NumberFormatException ignored) {
            return new ParsedInt(false, UNSPECIFIED);
        }
    }

    private static final class ParsedInt {
        final boolean valid;
        final int value;

        ParsedInt(boolean valid, int value) {
            this.valid = valid;
            this.value = value;
        }
    }
}
