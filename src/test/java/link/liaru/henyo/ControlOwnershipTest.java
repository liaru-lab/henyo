package link.liaru.henyo;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class ControlOwnershipTest {
    public static void main(String[] args) throws Exception {
        ControlOwnership gate = new ControlOwnership(false);
        if (!"available".equals(gate.state())) throw new AssertionError("available state");
        long initial = gate.ticket();
        gate.check(initial, "client-a");
        if (!"active".equals(gate.state())) throw new AssertionError("active state");
        denied(() -> gate.check(initial, "client-b"), "control_owned");
        gate.release("client-b");
        denied(() -> gate.check(initial, "client-b"), "control_owned");

        // A queued worker holds its ingress ticket, not a ticket obtained after
        // acquiring the busy execution queue. Stop must not wait on that queue.
        CountDownLatch queued = new CountDownLatch(1);
        CountDownLatch continueWork = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            queued.countDown();
            try {
                continueWork.await();
                denied(() -> gate.check(initial, "client-a"), "control_stopped");
            } catch (Throwable e) { failure.set(e); }
        });
        worker.start();
        if (!queued.await(2, TimeUnit.SECONDS)) throw new AssertionError("worker did not queue");
        gate.stop();
        if (!"stopped".equals(gate.state())) throw new AssertionError("stopped state");
        if (!gate.stopped() || !gate.owner().isEmpty()) throw new AssertionError("stop state");
        denied(() -> gate.check(gate.ticket(), "client-a"), "control_stopped");
        gate.resume();
        continueWork.countDown();
        worker.join(2000);
        if (worker.isAlive() || failure.get() != null) throw new AssertionError("queued request", failure.get());
        long resumed = gate.ticket();
        gate.check(resumed, "client-b");
        gate.release("client-b");
        denied(() -> gate.check(resumed, "client-b"), "control_stopped");
        gate.check(gate.ticket(), "client-a");
        final int[] dispatched = {0};
        long active = gate.ticket();
        gate.dispatch(active, "client-a", () -> ++dispatched[0]);
        gate.stop();
        denied(() -> gate.dispatch(active, "client-a", () -> ++dispatched[0]), "control_stopped");
        if (dispatched[0] != 1) throw new AssertionError("dispatch after stop");
        ControlOwnership persisted = new ControlOwnership(true);
        denied(() -> persisted.check(persisted.ticket(), "reconnected"), "control_stopped");
        System.out.println("ControlOwnershipTest passed");
    }

    private static void denied(Runnable action, String code) {
        try { action.run(); } catch (ControlOwnership.Unavailable e) {
            if (!code.equals(e.code)) throw new AssertionError(e.code);
            return;
        }
        throw new AssertionError("expected " + code);
    }
}
