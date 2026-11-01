#!/usr/bin/env python3
"""Exercise production completion/dismissal methods with a queued clock."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java").read_text()
methods = source[source.index("    private boolean completeTaskOnMain("):source.index("    void showObservation(")]
harness = r'''
package link.liaru.henyo;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
public final class TaskPresentationHarness {
    boolean destroyed, controlStopped, connected = true, completedPresentation, presentationSuppressed;
    boolean active = true, fadingOut, attached = true, plan = true;
    long presentationGeneration;
    Object lastCompletedTask;
    String lastCompletionMessage = "";
    final CompletionMessageModel completionModel = new CompletionMessageModel();
    final Clearable activityModel = new Clearable(), visualCoordinates = new Clearable();
    final Visual visualModel = new Visual();
    final ClockHandler mainHandler = new ClockHandler();
    final Runnable returnToIdle = () -> {}, finishFadeOut = () -> {};
    static final class Clearable { boolean cleared; void clear() { cleared = true; } }
    static final class Visual {
        boolean cursor, scan, activity;
        void clearActivity() { activity = true; }
        void clearScan() { scan = true; }
        void clearCursor() { cursor = true; }
    }
    static final class ClockHandler {
        final List<Runnable> delayed = new ArrayList<>();
        void removeCallbacks(Runnable value) {}
        void postDelayed(Runnable value, long delay) {
            check(delay == 3000, "bounded completion lifetime"); delayed.add(value);
        }
    }
    static final class SystemClock { static long uptimeMillis() { return 1000; } }
    void clearTaskProgressOnMain(Object owner, boolean force) { check(force, "clear validated plan"); plan = false; }
    void removeView() { attached = false; }
    void ensureActivityView() { attached = true; }
    void refreshControlButton() {}
    static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
    public static void main(String[] args) {
        TaskPresentationHarness ui = new TaskPresentationHarness();
        Object first = new Object();
        AtomicBoolean current = new AtomicBoolean(true);
        check(ui.completeTaskOnMain("Completed", first, current::get), "completion accepted");
        check(!ui.plan && ui.activityModel.cleared && ui.visualModel.cursor && ui.visualModel.scan
                && ui.visualModel.activity && ui.visualCoordinates.cleared && !ui.active, "completion clears running presentation");
        Runnable oldTimer = ui.mainHandler.delayed.get(0);
        ui.beginPresentation(); ui.plan = true; ui.active = true;
        oldTimer.run();
        check(ui.plan && ui.active && !ui.presentationSuppressed, "old timer cannot dismiss newer work");
        Object second = new Object();
        ui.completeTaskOnMain("Completed", second, current::get);
        ui.dismissCompletedPresentation();
        check(ui.presentationSuppressed && !ui.attached && ui.completionModel.isEmpty(1000), "Dismiss removes presentation");
        ui.completeTaskOnMain("Late duplicate", second, current::get);
        check(!ui.attached && ui.presentationSuppressed, "duplicate completion cannot revive dismissed content");
        ui.beginPresentation();
        ui.completeTaskOnMain("Completed", new Object(), current::get);
        int timers = ui.mainHandler.delayed.size();
        ui.completeTaskOnMain("Completed", ui.lastCompletedTask, current::get);
        check(ui.mainHandler.delayed.size() == timers, "duplicate finish does not extend lifetime");
        current.set(false);
        ui.mainHandler.delayed.get(timers - 1).run();
        check(ui.completedPresentation, "stale lifecycle guard prevents timer mutation");
        current.set(true);
        ui.mainHandler.delayed.get(timers - 1).run();
        check(!ui.attached && ui.presentationSuppressed, "normal completion dismisses automatically");
        System.out.println("TaskPresentationHarness passed");
    }
'''
with tempfile.TemporaryDirectory(prefix="henyo-task-presentation-") as classes:
    path = Path(classes) / "TaskPresentationHarness.java"
    path.write_text(harness + methods + "\n}\n")
    subprocess.run(["javac", "--release", "11", "-d", classes,
                    str(root / "src/main/java/link/liaru/henyo/CompletionMessageModel.java"), str(path)], check=True)
    subprocess.run(["java", "-cp", classes, "link.liaru.henyo.TaskPresentationHarness"], check=True)
