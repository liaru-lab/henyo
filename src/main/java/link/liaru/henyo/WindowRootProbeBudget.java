package link.liaru.henyo;

/** Bounds lazy accessibility-root probing below the normal client timeout. */
public final class WindowRootProbeBudget {
    public static final long PER_WINDOW_TIMEOUT_MS = 750L;
    public static final long RESOLUTION_TIMEOUT_MS = 3_000L;

    private WindowRootProbeBudget() {}

    public static long nextTimeoutMs(long startedMs, long nowMs) {
        long remaining = RESOLUTION_TIMEOUT_MS - Math.max(0L, nowMs - startedMs);
        if (remaining <= 0L) return 0L;
        return Math.min(PER_WINDOW_TIMEOUT_MS, remaining);
    }
}
