package link.liaru.henyo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public final class CaptureCompletionTest {
    public static void main(String[] args) throws Exception {
        CaptureCompletion<String> timeout = new CaptureCompletion<>();
        if (timeout.await(0) != null) throw new AssertionError("premature completion");
        timeout.complete("timeout");
        if (timeout.complete("late pixels") || !"timeout".equals(timeout.value()))
            throw new AssertionError("late callback changed result");
        CaptureCompletion<String> success = new CaptureCompletion<>();
        success.complete("pixels");
        if (success.complete("timeout") || !"pixels".equals(success.await(0)))
            throw new AssertionError("timeout overwrote success");

        for (int i = 0; i < 100; i++) {
            CaptureCompletion<String> race = new CaptureCompletion<>();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger winners = new AtomicInteger();
            Thread callback = contender(race, start, winners, "pixels");
            Thread cancel = contender(race, start, winners, "cancel");
            start.countDown();
            callback.join(2000); cancel.join(2000);
            if (callback.isAlive() || cancel.isAlive() || winners.get() != 1 || !race.isDone())
                throw new AssertionError("completion race");
            String terminal = race.value();
            if (race.complete("duplicate") || !terminal.equals(race.value()))
                throw new AssertionError("duplicate callback changed result");
        }
        CaptureCompletion<String> interrupted = new CaptureCompletion<>();
        Thread.currentThread().interrupt();
        try {
            interrupted.await(1000);
            throw new AssertionError("interruption ignored");
        } catch (InterruptedException expected) {
            interrupted.complete("interrupted");
        }
        if (interrupted.complete("late pixels")) throw new AssertionError("interrupted capture overwritten");
        System.out.println("CaptureCompletionTest passed");
    }

    private static Thread contender(CaptureCompletion<String> race, CountDownLatch start,
            AtomicInteger winners, String value) {
        Thread worker = new Thread(() -> {
            try { start.await(); } catch (InterruptedException e) { throw new AssertionError(e); }
            if (race.complete(value)) winners.incrementAndGet();
        });
        worker.start();
        return worker;
    }
}
