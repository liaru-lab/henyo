package link.liaru.henyo;

public final class TaskLifecycleTest {
    public static void main(String[] args) {
        TaskLifecycle tasks = new TaskLifecycle();
        ControlOwnership control = new ControlOwnership(false);
        long admission = control.ticket();
        control.check(admission, "client-a");
        TaskLifecycle.Token first = tasks.start("client-a", "task-1");
        check(tasks.start("client-a", "task-1") == first, "progress preserves task");
        check(tasks.finish("client-b", "task-1") == null, "other client cannot finish");
        check(tasks.finish("client-a", "old-task") == null, "wrong task cannot finish");
        TaskLifecycle.Token completed = tasks.finish("client-a", "task-1");
        control.completeTask("client-a");
        check(!tasks.isCurrent(first) && tasks.isCurrent(completed), "completion invalidates pending activity");
        check(tasks.finish("client-a", "task-1") == completed, "duplicate completion is idempotent");
        check(control.state().equals("available") && !control.isCurrent(admission, "client-a"), "completion returns control and invalidates queued actions");
        control.check(control.ticket(), "client-b");
        TaskLifecycle.Token next = tasks.start("client-b", "task-2");
        check(tasks.finish("client-a", "task-1") == null && !tasks.isCurrent(completed), "old completion cannot dismiss new client");
        tasks.start("client-b", "task-3");
        check(tasks.finish("client-b", "task-2") == null && !tasks.isCurrent(next), "old completion cannot finish newer task");
        tasks.invalidate("client-a");
        check(tasks.active("client-b") != null, "other disconnect cannot clear task");
        tasks.invalidate("client-b");
        check(tasks.active("client-b") == null, "owner disconnect clears task");
        TaskLifecycle.Token stopped = tasks.start("client-a", "task-4");
        tasks.invalidate(null); control.stop(); control.resume();
        check(!tasks.isCurrent(stopped), "resume cannot revive stopped presentation");
        check(TaskLifecycle.validId("synthetic-task.1") && !TaskLifecycle.validId("")
                && !TaskLifecycle.validId("bad id") && !TaskLifecycle.validId("a".repeat(129)), "bounded task ID");
        ControlOwnership readOnly = new ControlOwnership(false);
        long beforeFinish = readOnly.ticket();
        readOnly.completeTask("client-a");
        check(!readOnly.isCurrent(beforeFinish, "client-a"), "read-only completion also invalidates old queue tickets");
        System.out.println("TaskLifecycleTest passed");
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
