package link.liaru.henyo;

import java.util.concurrent.atomic.AtomicLong;

/** Bounded, content-free timing counters for launch/overlay performance diagnosis. */
final class PerformanceMetrics {
    static final long LAUNCH_WINDOW_MS = 5_000L;
    static final long DRAW_GAP_THRESHOLD_MS = 40L;
    static final long HEARTBEAT_LATE_THRESHOLD_MS = 20L;

    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong launchWindowUntilMs = new AtomicLong();
    private final AtomicLong launchCount = new AtomicLong();
    private final AtomicLong launchFailureCount = new AtomicLong();
    private final AtomicLong launchDispatchTotalUs = new AtomicLong();
    private final AtomicLong launchDispatchMaxUs = new AtomicLong();
    private final AtomicLong heartbeatCount = new AtomicLong();
    private final AtomicLong heartbeatLateCount = new AtomicLong();
    private final AtomicLong heartbeatLateTotalMs = new AtomicLong();
    private final AtomicLong heartbeatLateMaxMs = new AtomicLong();
    private final AtomicLong launchHeartbeatLateCount = new AtomicLong();
    private final AtomicLong launchHeartbeatLateMaxMs = new AtomicLong();
    private final AtomicLong drawCount = new AtomicLong();
    private final AtomicLong drawGapCount = new AtomicLong();
    private final AtomicLong drawGapMaxMs = new AtomicLong();
    private final AtomicLong drawCostTotalUs = new AtomicLong();
    private final AtomicLong drawCostMaxUs = new AtomicLong();
    private final AtomicLong launchDrawGapCount = new AtomicLong();
    private final AtomicLong launchDrawGapMaxMs = new AtomicLong();
    private final AtomicLong lastDrawAtMs = new AtomicLong();
    private final AtomicLong accessibilityNoiseCount = new AtomicLong();
    private final AtomicLong accessibilityRelevantCount = new AtomicLong();
    private final AtomicLong accessibilityMajorCount = new AtomicLong();
    private final AtomicLong accessibilityCostTotalUs = new AtomicLong();
    private final AtomicLong accessibilityCostMaxUs = new AtomicLong();
    private final AtomicLong launchAccessibilityCount = new AtomicLong();
    private final AtomicLong sessionListLockWaitTotalUs = new AtomicLong();
    private final AtomicLong sessionListLockWaitMaxUs = new AtomicLong();
    private final AtomicLong sessionWriteLockWaitTotalUs = new AtomicLong();
    private final AtomicLong sessionWriteLockWaitMaxUs = new AtomicLong();
    private final AtomicLong staticLayoutCount = new AtomicLong();
    private final AtomicLong staticLayoutCostTotalUs = new AtomicLong();
    private final AtomicLong staticLayoutCostMaxUs = new AtomicLong();
    private final AtomicLong launchStaticLayoutCount = new AtomicLong();

    PerformanceMetrics() {
        reset();
    }

    void reset() {
        epoch.incrementAndGet();
        launchWindowUntilMs.set(0L);
        for (AtomicLong counter : new AtomicLong[]{
                launchCount, launchFailureCount, launchDispatchTotalUs, launchDispatchMaxUs,
                heartbeatCount, heartbeatLateCount, heartbeatLateTotalMs, heartbeatLateMaxMs,
                launchHeartbeatLateCount, launchHeartbeatLateMaxMs,
                drawCount, drawGapCount, drawGapMaxMs, drawCostTotalUs, drawCostMaxUs,
                launchDrawGapCount, launchDrawGapMaxMs, lastDrawAtMs,
                accessibilityNoiseCount, accessibilityRelevantCount, accessibilityMajorCount,
                accessibilityCostTotalUs, accessibilityCostMaxUs, launchAccessibilityCount,
                sessionListLockWaitTotalUs, sessionListLockWaitMaxUs,
                sessionWriteLockWaitTotalUs, sessionWriteLockWaitMaxUs,
                staticLayoutCount, staticLayoutCostTotalUs, staticLayoutCostMaxUs,
                launchStaticLayoutCount}) {
            counter.set(0L);
        }
    }

    void beginLaunch(long nowMs) {
        launchCount.incrementAndGet();
        launchWindowUntilMs.set(nowMs + LAUNCH_WINDOW_MS);
    }

    void recordLaunchDispatch(long elapsedNanos, boolean success) {
        long us = nanosToMicros(elapsedNanos);
        launchDispatchTotalUs.addAndGet(us);
        updateMax(launchDispatchMaxUs, us);
        if (!success) launchFailureCount.incrementAndGet();
    }

    void recordMainHeartbeat(long nowMs, long lateMs) {
        heartbeatCount.incrementAndGet();
        if (lateMs <= HEARTBEAT_LATE_THRESHOLD_MS) return;
        heartbeatLateCount.incrementAndGet();
        heartbeatLateTotalMs.addAndGet(lateMs);
        updateMax(heartbeatLateMaxMs, lateMs);
        if (duringLaunch(nowMs)) {
            launchHeartbeatLateCount.incrementAndGet();
            updateMax(launchHeartbeatLateMaxMs, lateMs);
        }
    }

    void recordActivityDraw(long nowMs, long elapsedNanos) {
        drawCount.incrementAndGet();
        long us = nanosToMicros(elapsedNanos);
        drawCostTotalUs.addAndGet(us);
        updateMax(drawCostMaxUs, us);
        long prior = lastDrawAtMs.getAndSet(nowMs);
        if (prior <= 0L) return;
        long gapMs = Math.max(0L, nowMs - prior);
        if (gapMs <= DRAW_GAP_THRESHOLD_MS) return;
        drawGapCount.incrementAndGet();
        updateMax(drawGapMaxMs, gapMs);
        if (duringLaunch(nowMs)) {
            launchDrawGapCount.incrementAndGet();
            updateMax(launchDrawGapMaxMs, gapMs);
        }
    }

    void recordAccessibility(int coarseKind, long nowMs, long elapsedNanos) {
        if (coarseKind <= 0) accessibilityNoiseCount.incrementAndGet();
        else if (coarseKind == 1) accessibilityRelevantCount.incrementAndGet();
        else accessibilityMajorCount.incrementAndGet();
        long us = nanosToMicros(elapsedNanos);
        accessibilityCostTotalUs.addAndGet(us);
        updateMax(accessibilityCostMaxUs, us);
        if (duringLaunch(nowMs)) launchAccessibilityCount.incrementAndGet();
    }

    void recordSessionListLockWait(long elapsedNanos) {
        recordCost(elapsedNanos, sessionListLockWaitTotalUs, sessionListLockWaitMaxUs);
    }

    void recordSessionWriteLockWait(long elapsedNanos) {
        recordCost(elapsedNanos, sessionWriteLockWaitTotalUs, sessionWriteLockWaitMaxUs);
    }

    void recordStaticLayout(long nowMs, long elapsedNanos) {
        staticLayoutCount.incrementAndGet();
        recordCost(elapsedNanos, staticLayoutCostTotalUs, staticLayoutCostMaxUs);
        if (duringLaunch(nowMs)) launchStaticLayoutCount.incrementAndGet();
    }

    String toJson() {
        return "{\"epoch\":" + epoch.get() +
                ",\"launch\":{\"count\":" + launchCount.get() +
                ",\"failureCount\":" + launchFailureCount.get() +
                ",\"dispatchTotalUs\":" + launchDispatchTotalUs.get() +
                ",\"dispatchMaxUs\":" + launchDispatchMaxUs.get() + "}" +
                ",\"mainHeartbeat\":{\"count\":" + heartbeatCount.get() +
                ",\"lateCount\":" + heartbeatLateCount.get() +
                ",\"lateTotalMs\":" + heartbeatLateTotalMs.get() +
                ",\"lateMaxMs\":" + heartbeatLateMaxMs.get() +
                ",\"launchLateCount\":" + launchHeartbeatLateCount.get() +
                ",\"launchLateMaxMs\":" + launchHeartbeatLateMaxMs.get() + "}" +
                ",\"activityDraw\":{\"count\":" + drawCount.get() +
                ",\"gapCount\":" + drawGapCount.get() +
                ",\"gapMaxMs\":" + drawGapMaxMs.get() +
                ",\"costTotalUs\":" + drawCostTotalUs.get() +
                ",\"costMaxUs\":" + drawCostMaxUs.get() +
                ",\"launchGapCount\":" + launchDrawGapCount.get() +
                ",\"launchGapMaxMs\":" + launchDrawGapMaxMs.get() + "}" +
                ",\"accessibility\":{\"noiseCount\":" + accessibilityNoiseCount.get() +
                ",\"relevantCount\":" + accessibilityRelevantCount.get() +
                ",\"majorCount\":" + accessibilityMajorCount.get() +
                ",\"costTotalUs\":" + accessibilityCostTotalUs.get() +
                ",\"costMaxUs\":" + accessibilityCostMaxUs.get() +
                ",\"launchCount\":" + launchAccessibilityCount.get() + "}" +
                ",\"sessionLocks\":{\"listWaitTotalUs\":" + sessionListLockWaitTotalUs.get() +
                ",\"listWaitMaxUs\":" + sessionListLockWaitMaxUs.get() +
                ",\"writeWaitTotalUs\":" + sessionWriteLockWaitTotalUs.get() +
                ",\"writeWaitMaxUs\":" + sessionWriteLockWaitMaxUs.get() + "}" +
                ",\"staticLayout\":{\"count\":" + staticLayoutCount.get() +
                ",\"costTotalUs\":" + staticLayoutCostTotalUs.get() +
                ",\"costMaxUs\":" + staticLayoutCostMaxUs.get() +
                ",\"launchCount\":" + launchStaticLayoutCount.get() + "}}";
    }

    private boolean duringLaunch(long nowMs) {
        return nowMs <= launchWindowUntilMs.get();
    }

    private static void recordCost(long elapsedNanos, AtomicLong total, AtomicLong max) {
        long us = nanosToMicros(elapsedNanos);
        total.addAndGet(us);
        updateMax(max, us);
    }

    private static long nanosToMicros(long nanos) {
        return Math.max(0L, nanos / 1_000L);
    }

    private static void updateMax(AtomicLong target, long value) {
        long current = target.get();
        while (value > current && !target.compareAndSet(current, value)) current = target.get();
    }
}
