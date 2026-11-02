package link.liaru.henyo;

/** Process-local ownership; tickets invalidate work accepted before a user stop. */
final class ControlOwnership {
    private volatile boolean stopped;
    private long generation;
    private volatile String owner = "";

    ControlOwnership(boolean stopped) { this.stopped = stopped; }

    synchronized long ticket() { return generation; }
    // Presentation reads must not wait for a Binder action dispatched to this
    // app's main thread. Authorization still uses the synchronized methods below.
    boolean stopped() { return stopped; }
    String owner() { return owner; }
    String state() { return stopped ? "stopped" : owner.isEmpty() ? "available" : "active"; }

    synchronized boolean isCurrent(long ticket, String candidate) {
        return !stopped && ticket == generation && (owner.isEmpty() || owner.equals(candidate));
    }

    synchronized void stop() {
        stopped = true;
        owner = "";
        generation++;
    }

    synchronized void resume() {
        stopped = false;
        owner = "";
        generation++;
    }

    synchronized void release(String candidate) {
        if (!owner.isEmpty() && owner.equals(candidate)) {
            owner = "";
            generation++;
        }
    }

    synchronized void completeTask(String candidate) {
        if (!owner.isEmpty() && !owner.equals(candidate)) throw new Unavailable("control_owned");
        owner = "";
        generation++;
    }

    synchronized void check(long ticket, String candidate) {
        if (stopped || ticket != generation) throw new Unavailable("control_stopped");
        if (!owner.isEmpty() && !owner.equals(candidate)) throw new Unavailable("control_owned");
        owner = candidate;
    }

    synchronized <T> T dispatch(long ticket, String candidate, java.util.function.Supplier<T> action) {
        check(ticket, candidate);
        return action.get();
    }

    static final class Unavailable extends RuntimeException {
        final String code;
        Unavailable(String code) { super(code); this.code = code; }
    }
}
