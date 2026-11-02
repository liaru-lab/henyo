package link.liaru.henyo;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class SessionOperationQueueTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch, String name) throws InterruptedException {
        check(latch.await(3, TimeUnit.SECONDS), name);
    }
    private static void serialBoundAndFailures() throws Exception {
        List<Integer> order = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger active = new AtomicInteger(), maximum = new AtomicInteger(), failures = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(4);
        SessionOperationQueue queue = new SessionOperationQueue(3, failure -> {
            failures.incrementAndGet();
            throw new IllegalStateException("failure callback must also be contained");
        });
        check(queue.submit(() -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            started.countDown(); release.await(); order.add(0); active.decrementAndGet(); done.countDown();
        }), "first accepted");
        await(started, "first started");
        check(queue.hasWork(), "running work blocks authentication changes");
        for (int i = 1; i <= 3; i++) {
            final int id = i;
            check(queue.submit(() -> {
                maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                order.add(id); active.decrementAndGet(); done.countDown();
                if (id == 1) throw new IOException("checked worker failure");
                if (id == 2) throw new IllegalStateException("runtime worker failure");
            }), "pending accepted");
        }
        check(!queue.submit(() -> { throw new AssertionError("overflow executed"); }), "bounded pending queue");
        release.countDown(); await(done, "FIFO jobs finished");
        queue.close();
        check(order.equals(java.util.Arrays.asList(0,1,2,3)), "FIFO survives failed jobs");
        check(maximum.get() == 1, "no concurrent execution");
        check(failures.get() == 2, "checked/runtime failures contained");
        check(!queue.submit(() -> {}), "closed admission rejected");
    }
    private static void disconnectInterruptsAndDrops() throws Exception {
        CountDownLatch started = new CountDownLatch(1), interrupted = new CountDownLatch(1), finished = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        SessionOperationQueue queue = new SessionOperationQueue(2, failure -> {});
        queue.submit(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancelled) { interrupted.countDown(); }
            // The caller's original connection guard must also prevent late dispatch,
            // even if another connection retains the same control owner.
            if (!queue.isClosed()) dispatches.incrementAndGet();
            finished.countDown();
        });
        await(started, "running call started");
        queue.submit(dispatches::incrementAndGet);
        queue.close(); queue.close();
        await(interrupted, "running call interrupted"); await(finished, "running call exited");
        check(dispatches.get() == 0, "disconnect blocks running and pending dispatch");
    }
    private static void admissionKeepsTicketAndDeadline() throws Exception {
        ControlOwnership control = new ControlOwnership(false);
        long admittedTicket = control.ticket();
        OperationDeadline admittedDeadline = new OperationDeadline(100, 1);
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1), done = new CountDownLatch(1);
        AtomicInteger dispatches = new AtomicInteger();
        SessionOperationQueue queue = new SessionOperationQueue(2, failure -> {});
        queue.submit(() -> { started.countDown(); release.await(); });
        await(started, "queue blocked");
        queue.submit(() -> {
            if (control.isCurrent(admittedTicket,"same-client") && !admittedDeadline.expired(2_000_100))
                dispatches.incrementAndGet();
            done.countDown();
        });
        control.stop(); control.resume(); release.countDown(); await(done,"old admission handled"); queue.close();
        check(dispatches.get() == 0, "queueing does not refresh ticket/deadline");
    }
    public static void main(String[] args) throws Exception {
        serialBoundAndFailures(); disconnectInterruptsAndDrops(); admissionKeepsTicketAndDeadline();
        System.out.println("Session operation queue checks passed");
    }
}
