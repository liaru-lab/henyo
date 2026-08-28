package link.liaru.henyo;

final class SensitiveUiAccessPolicy {
    private SensitiveUiAccessPolicy() {
    }

    static boolean protectsOperation(String op) {
        return op != null && (op.startsWith("ui.") || WsOperation.OP_SCREEN_SCREENSHOT.equals(op));
    }

    static boolean protectsHttpPath(String path) {
        return path != null && (path.startsWith("/v1/ui/")
                || path.startsWith("/v1/screen/")
                || path.startsWith("/ui/")
                || path.startsWith("/screen/"));
    }

    static boolean allows(boolean sensitiveUiPresent, boolean pairedClient, boolean hasSensitiveScope) {
        return !sensitiveUiPresent || !pairedClient || hasSensitiveScope;
    }
}
