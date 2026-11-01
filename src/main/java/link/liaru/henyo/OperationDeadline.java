package link.liaru.henyo;

import java.util.concurrent.TimeUnit;

/** Monotonic lifetime, beginning when a complete request is admitted. */
final class OperationDeadline {
    private final long endNanos;

    OperationDeadline(long nowNanos, long timeoutMs) {
        if (timeoutMs < 1 || timeoutMs > 300_000L) throw new IllegalArgumentException("invalid timeout");
        endNanos = nowNanos + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    }

    private OperationDeadline(long endNanos) { this.endNanos = endNanos; }

    boolean expired(long nowNanos) { return nowNanos - endNanos >= 0L; }

    OperationDeadline limitedFrom(long nowNanos, long timeoutMs) {
        OperationDeadline limit = new OperationDeadline(nowNanos, timeoutMs);
        return endNanos - nowNanos <= limit.endNanos - nowNanos ? this : limit;
    }
}
