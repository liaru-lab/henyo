package link.liaru.henyo;

/** Pure timing policy shared by the WebSocket loop and its deterministic verifier. */
final class WebSocketSessionPolicy {
    static final long CONTROL_IDLE_TIMEOUT_MS = 60_000L;

    private WebSocketSessionPolicy() {
    }

    static long remainingControlIdleMs(long lastControlActivityMs, long nowMs) {
        long elapsed = Math.max(0L, nowMs - lastControlActivityMs);
        return Math.max(0L, CONTROL_IDLE_TIMEOUT_MS - elapsed);
    }

    static boolean isControlIdle(long lastControlActivityMs, long nowMs) {
        return remainingControlIdleMs(lastControlActivityMs, nowMs) == 0L;
    }

    static long controlIdleDeadlineMs(long lastControlActivityMs) {
        if (lastControlActivityMs > Long.MAX_VALUE - CONTROL_IDLE_TIMEOUT_MS) {
            return Long.MAX_VALUE;
        }
        return lastControlActivityMs + CONTROL_IDLE_TIMEOUT_MS;
    }

    static boolean deadlineReached(long deadlineMs, long nowMs) {
        return nowMs >= deadlineMs;
    }

    static int readTimeoutUntilDeadlineMs(long deadlineMs, long nowMs) {
        long remaining = Math.max(0L, deadlineMs - nowMs);
        return (int) Math.max(1L, Math.min((long) Integer.MAX_VALUE, remaining));
    }
}
