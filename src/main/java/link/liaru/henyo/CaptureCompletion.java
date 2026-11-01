package link.liaru.henyo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Exactly one terminal result wins, including timeout and cancellation. */
final class CaptureCompletion<T> {
    private final CountDownLatch ready = new CountDownLatch(1);
    private T value;

    synchronized boolean complete(T result) {
        if (result == null) throw new IllegalArgumentException("result required");
        if (value != null) return false;
        value = result;
        ready.countDown();
        return true;
    }

    synchronized boolean isDone() { return value != null; }
    synchronized T value() { return value; }

    T await(long timeoutMs) throws InterruptedException {
        ready.await(Math.max(0L, timeoutMs), TimeUnit.MILLISECONDS);
        return value();
    }
}
