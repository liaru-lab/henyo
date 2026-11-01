#!/usr/bin/env python3
"""Exercise actual gesture pacing, bounded metrics, and asynchronous cursor geometry."""
import subprocess
import tempfile
from pathlib import Path
root = Path(__file__).resolve().parents[1]
java = root / 'src/main/java/link/liaru/henyo'
overlay = (java / 'ConnectionStatusOverlay.java').read_text()
coordinates = overlay.split('    private static final class VisualCoordinates {', 1)[1].split(
    '    private static final class IndicatorView', 1)[0]
source = r'''
package link.liaru.henyo;
public final class ControlLatencyTest {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        PhaseTimings phases = new PhaseTimings();
        for (int i = 1; i <= 300; i++) phases.record(PhaseTimings.Phase.REQUEST_QUEUE, i * 1000L);
        check(phases.toJson().contains("\"count\":300,\"retained\":256,\"p50Us\":172,\"p95Us\":288"),
                "quantiles must use the bounded recent window after wraparound");
        phases.reset(); check(phases.toJson().contains("\"count\":0,\"retained\":0"), "reset clears samples");
        CaptureCompletion<Boolean> completed = new CaptureCompletion<>();
        completed.complete(true); GestureWait.await(completed, 50, () -> {});
        CaptureCompletion<Boolean> cancelled = new CaptureCompletion<>(); cancelled.complete(false);
        try { GestureWait.await(cancelled, 50, () -> {}); throw new AssertionError("cancelled gesture succeeded"); }
        catch (OperationFailure e) { check(e.code.equals("gesture_cancelled") && e.outcome.equals("unknown"), "cancel has unknown effects"); }
        CaptureCompletion<Boolean> pending = new CaptureCompletion<>();
        int[] checks = {0};
        try { GestureWait.await(pending, 5000, () -> {
            if (++checks[0] == 3) throw new OperationFailure("control_stopped",409,"unknown");
        }); throw new AssertionError("stop was ignored"); }
        catch (OperationFailure e) { check(e.code.equals("control_stopped"), "stop interrupts native wait"); }
        Thread worker = new Thread(() -> { try { Thread.sleep(25); } catch (InterruptedException e) { throw new AssertionError(e); } pending.complete(true); });
        worker.start(); GestureWait.await(pending, 50, () -> {}); worker.join();
        check(!pending.complete(false) && pending.value(), "late cancellation cannot change completion");
        CaptureCompletion<Boolean> timeout = new CaptureCompletion<>();
        try { GestureWait.await(timeout, 1, () -> {}); throw new AssertionError("missing callback succeeded"); }
        catch (OperationFailure e) { check(e.code.equals("gesture_timeout") && e.outcome.equals("unknown"), "missing callback is not safe to replay"); }
        AgentVisualModel model = new AgentVisualModel();
        VisualCoordinates position = new VisualCoordinates();
        position.prepareAction(model, AgentVisualModel.GLOVE_POSE_POINT, 100,100,100,100,0,1000,1600,1,0);
        float[] start = position.wristAt(0);
        position.beginPreparedAction(model,0);
        float[] committed = position.wristAt(0);
        check(start[0] == committed[0] && start[1] == committed[1], "early accepted action must not snap cursor");
        check(position.isAnimating(1), "travel continues after early native acceptance");
        float[] halfway = position.wristAt(position.travelDurationMs/2);
        float[] end = position.wristAt(position.travelDurationMs);
        check(Math.abs(halfway[0]-start[0]) < Math.abs(end[0]-start[0]), "cursor travels continuously toward accepted action");
        position.prepareAction(model, AgentVisualModel.GLOVE_POSE_OPEN_PALM, 200,200,400,400,300,1000,1600,1,1000);
        start=position.wristAt(1000);position.beginPreparedAction(model,1000);committed=position.wristAt(1000);
        check(start[0] == committed[0] && start[1] == committed[1], "early swipe commit preserves current wrist");
        position.clear();check(!position.isAnimating(1001), "stop clears pending travel");
        System.out.println("ControlLatencyTest passed");
    }
    private static final class VisualCoordinates {
'''
with tempfile.TemporaryDirectory(prefix='henyo-latency-') as directory:
    path = Path(directory) / 'ControlLatencyTest.java'
    path.write_text(source + coordinates + '\n}\n')
    subprocess.run(['javac','--release','11','-d',directory,*[str(java / (name+'.java')) for name in (
        'PhaseTimings','GestureWait','CaptureCompletion','OperationFailure','AgentVisualModel','ObservationEffectModel','MotionEasing')],str(path)],check=True)
    subprocess.run(['java','-cp',directory,'link.liaru.henyo.ControlLatencyTest'],check=True)
