package link.liaru.henyo;

import java.util.concurrent.TimeUnit;

/** Wait for actual native completion, checking cancellation between bounded waits. */
final class GestureWait {
    static void await(CaptureCompletion<Boolean> completion, int durationMs, Runnable checkCurrent) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, durationMs) + 1000L);
        try {
            while (true) {
                checkCurrent.run();
                Boolean result = completion.await(16L);
                checkCurrent.run();
                if (Boolean.TRUE.equals(result)) return;
                if (Boolean.FALSE.equals(result)) throw new OperationFailure("gesture_cancelled", 409, "unknown");
                if (System.nanoTime() - deadline >= 0L)
                    throw new OperationFailure("gesture_timeout", 504, "unknown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OperationFailure("gesture_interrupted", 409, "unknown");
        }
    }
}
