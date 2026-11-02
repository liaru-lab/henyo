package link.liaru.henyo;

/** Intent for asynchronous preparation. Mutations are under the runtime monitor. */
final class D1WorkerPolicy {
    private volatile long epoch;
    private volatile boolean requested;
    long epoch() { return epoch; }
    boolean requested() { return requested; }
    void request() { requested = true; }
    void cancel() { requested = false; epoch++; }
    boolean restartAfter(long obsoleteEpoch) { return requested && epoch != obsoleteEpoch; }
    static boolean releaseIdle(String state, boolean preparing, boolean busy, long lastUse, long now) {
        return "ready".equals(state) && !preparing && !busy && now-lastUse > 300000L;
    }
}
