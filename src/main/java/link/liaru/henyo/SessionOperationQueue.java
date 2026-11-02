package link.liaru.henyo;

import java.util.ArrayDeque;

/** A bounded FIFO for one connection. Closing never waits for a platform call. */
final class SessionOperationQueue implements AutoCloseable {
    interface Work { void run() throws Exception; }
    interface FailureHandler { void failed(Exception failure); }
    private final int capacity;
    private final FailureHandler failureHandler;
    private final ArrayDeque<Work> pending = new ArrayDeque<>();
    private Thread worker;
    private boolean closed, running;

    SessionOperationQueue(int capacity, FailureHandler failureHandler) {
        if (capacity < 1 || failureHandler == null) throw new IllegalArgumentException();
        this.capacity = capacity;
        this.failureHandler = failureHandler;
    }

    synchronized boolean submit(Work work) {
        if (work == null) throw new IllegalArgumentException();
        if (closed || pending.size() >= capacity) return false;
        pending.addLast(work);
        if (worker == null) {
            worker = new Thread(this::drain, "henyo-ws-operations");
            worker.setDaemon(true);
            worker.start();
        }
        notifyAll();
        return true;
    }

    synchronized boolean hasWork() { return running || !pending.isEmpty(); }
    synchronized boolean isClosed() { return closed; }

    @Override public void close() {
        Thread thread;
        synchronized (this) {
            if (closed) return;
            closed = true;
            pending.clear();
            thread = worker;
            notifyAll();
        }
        if (thread != null) thread.interrupt();
    }

    private void drain() {
        while (true) {
            Work work;
            synchronized (this) {
                while (!closed && pending.isEmpty()) {
                    try { wait(); }
                    catch (InterruptedException interrupted) {
                        if (closed) return;
                    }
                }
                if (closed) return;
                work = pending.removeFirst();
                running = true;
            }
            try {
                work.run();
            } catch (Exception failure) {
                try { failureHandler.failed(failure); }
                catch (RuntimeException ignored) { /* Never escape into the app's uncaught handler. */ }
            } finally {
                synchronized (this) { running = false; }
                Thread.interrupted();
            }
        }
    }
}
