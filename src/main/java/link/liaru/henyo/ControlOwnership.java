package link.liaru.henyo;

/** Process-local ownership; tickets invalidate work accepted before a user stop. */
final class ControlOwnership {
    private boolean stopped;
    private long generation;
    private String owner = "";

    ControlOwnership(boolean stopped) { this.stopped = stopped; }

    synchronized long ticket() { return generation; }
    synchronized boolean stopped() { return stopped; }
    synchronized String owner() { return owner; }

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
