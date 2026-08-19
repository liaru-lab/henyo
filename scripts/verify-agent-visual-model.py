#!/usr/bin/env python3
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/java/link/liaru/henyo/AgentVisualModel.java"
TEST_SOURCE = r'''
package link.liaru.henyo;

public final class AgentVisualModelTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        AgentVisualModel model = new AgentVisualModel();
        check(!model.isActivityVisible(0L), "activity must begin hidden");
        model.noteActivity(1_000L);
        near(model.activityEnvelope(1_000L), 0f, 0.0001f,
                "first activity must start a fade-in rather than flash on");
        float activationMiddle = model.activityEnvelope(1_400L);
        check(activationMiddle > 0f && activationMiddle < 1f,
                "800ms activation must interpolate");
        check(model.backdropEnvelope(1_060L) > model.activityEnvelope(1_060L),
                "navy backdrop must lead the slower perimeter activation");
        near(model.backdropEnvelope(1_120L), 1f, 0.0001f,
                "navy backdrop must be fully established before caption reveal completes");
        near(model.activityEnvelope(1_800L), 1f, 0.0001f,
                "activation must reach full envelope after 800ms");
        near(model.activityEnvelope(21_000L), 1f, 0.0001f,
                "20 second activity lease must remain fully active through its boundary");
        float fadeMiddle = model.activityEnvelope(21_900L);
        check(fadeMiddle > 0f && fadeMiddle < 1f, "1800ms fade must interpolate");
        near(model.activityEnvelope(22_800L), 0f, 0.0001f,
                "activity must be hidden after lease and fade");

        AgentVisualModel extended = new AgentVisualModel();
        extended.noteActivity(0L);
        float beforeExtension = extended.activityEnvelope(400L);
        extended.noteActivity(400L);
        near(extended.activityEnvelope(400L), beforeExtension, 0.0001f,
                "activity during activation must not jump or restart the envelope");
        near(extended.activityEnvelope(800L), 1f, 0.0001f,
                "an added control event must not delay an in-flight activation");
        near(extended.activityEnvelope(20_300L), 1f, 0.0001f,
                "new controls must extend the lease to 20 seconds from the latest event");

        AgentVisualModel recovering = new AgentVisualModel();
        recovering.noteActivity(0L);
        float faded = recovering.activityEnvelope(20_900L);
        float backdropFaded = recovering.backdropEnvelope(20_900L);
        float intensityBefore = recovering.activityIntensity(20_900L);
        recovering.noteActivity(20_900L);
        near(recovering.activityEnvelope(20_900L), faded, 0.0001f,
                "reactivation during fade must be envelope-continuous");
        near(recovering.activityIntensity(20_900L), intensityBefore, 0.0001f,
                "reactivation during fade must be visually continuous");
        near(recovering.backdropEnvelope(20_900L), backdropFaded, 0.0001f,
                "navy backdrop reversal must also be continuous");
        check(recovering.activityEnvelope(21_300L) > faded,
                "reactivation must ease back toward full strength");
        near(recovering.activityEnvelope(21_700L), 1f, 0.0001f,
                "fade recovery must complete using the 800ms activation envelope");

        float pulseAt = AgentVisualModel.pulse(1_234L);
        AgentVisualModel pulseStable = new AgentVisualModel();
        pulseStable.noteActivity(100L);
        pulseStable.noteActivity(1_234L);
        near(AgentVisualModel.pulse(1_234L), pulseAt, 0.0001f,
                "control activity must never reset the global pulse phase");
        near(AgentVisualModel.pulse(1_234L + AgentVisualModel.PULSE_CYCLE_MS), pulseAt, 0.0001f,
                "pulse must repeat deterministically on its global cycle");
        near(AgentVisualModel.pulse(-1L), AgentVisualModel.pulse(
                AgentVisualModel.PULSE_CYCLE_MS - 1L), 0.0001f,
                "global pulse must use a stable floor-mod phase");

        AgentVisualModel scan = new AgentVisualModel();
        check(scan.beginScan("observe-1", 5_000L), "first logical observation must start a scan");
        near(scan.scanPosition(5_000L), 0f, 0.0001f, "scan must begin at the top");
        near(scan.scanPosition(5_700L), 0.5f, 0.0001f,
                "scan ease-in-out must cross the midpoint halfway through");
        check(!scan.beginScan("observe-2", 5_700L),
                "an in-progress scan request must not restart the animation");
        near(scan.scanPosition(5_700L), 0.5f, 0.0001f,
                "re-request must preserve current scan progress");
        check(!scan.scanActive(6_400L), "scan must finish after 1.4 seconds");
        check(scan.scanPosition(6_400L) < 0f, "finished scan must not expose a drawable position");
        check(!scan.beginScan("observe-1", 6_500L),
                "one logical observation id must animate at most once");
        check(scan.beginScan("observe-2", 6_500L),
                "a later logical observation must start its own scan");

        AgentVisualModel cursor = new AgentVisualModel();
        cursor.noteActivity(40_000L);
        cursor.beginCursorSession(40_000L);
        check(cursor.cursorNeedsCenterPlacement(),
                "a new persistent cursor session must ask the controller to start centered");
        check(cursor.cursorFrame(40_000L) == null,
                "cursor may share the activity entrance and begin at zero alpha");
        AgentVisualModel.CursorFrame initialIdle = cursor.cursorFrame(40_120L);
        check(initialIdle != null && initialIdle.phase == AgentVisualModel.CURSOR_PHASE_IDLE,
                "new centered cursor must persist in idle state during the active lease");
        check(initialIdle.alpha > AgentVisualModel.CURSOR_IDLE_ALPHA && initialIdle.alpha < 1f,
                "centered cursor must ease toward the dim idle alpha");

        near(AgentVisualModel.cursorMoveDuration(0f), AgentVisualModel.CURSOR_MOVE_MIN_MS, 0f,
                "zero-distance cursor movement must use the 180ms floor");
        near(AgentVisualModel.cursorMoveDuration(1f), AgentVisualModel.CURSOR_MOVE_MAX_MS, 0f,
                "full-diagonal cursor movement must use the 450ms cap");
        long mediumMove = AgentVisualModel.cursorMoveDuration(0.5f);
        check(mediumMove > AgentVisualModel.CURSOR_MOVE_MIN_MS
                        && mediumMove < AgentVisualModel.CURSOR_MOVE_MAX_MS,
                "cursor movement duration must scale with distance");
        check(AgentVisualModel.cursorMoveDuration(2f) == AgentVisualModel.CURSOR_MOVE_MAX_MS,
                "cursor movement duration must clamp distances beyond the display diagonal");

        long travelDuration = cursor.moveCursor(AgentVisualModel.GLOVE_POSE_POINT, 0.5f, 40_200L);
        check(!cursor.cursorNeedsCenterPlacement(),
                "first movement must consume the controller's centered placement request");
        AgentVisualModel.CursorFrame traveling = cursor.cursorFrame(40_200L + travelDuration / 2L);
        check(traveling != null && traveling.phase == AgentVisualModel.CURSOR_PHASE_TRAVELING,
                "cursor must expose an eased travel phase");
        check(traveling.phaseProgress > 0.45f && traveling.phaseProgress < 0.55f,
                "travel progress must pass smoothly through its midpoint");
        AgentVisualModel.CursorFrame arrived = cursor.cursorFrame(40_200L + travelDuration);
        check(arrived.phase == AgentVisualModel.CURSOR_PHASE_TRAVELING
                        && arrived.phaseProgress == 1f,
                "cursor must wait at its target until the real action is committed");
        cursor.commitCursorAction(AgentVisualModel.GLOVE_POSE_POINT,
                AgentVisualModel.CURSOR_ACTION_MS, 40_200L + travelDuration);
        AgentVisualModel.CursorFrame cursorActing = cursor.cursorFrame(
                40_200L + travelDuration + AgentVisualModel.CURSOR_ACTION_MS / 2L);
        check(cursorActing.phase == AgentVisualModel.CURSOR_PHASE_ACTING
                        && cursorActing.actionAmount > 0.9f,
                "cursor arrival must include a compact action beat");
        AgentVisualModel.CursorFrame cursorIdle = cursor.cursorFrame(
                40_200L + travelDuration + AgentVisualModel.CURSOR_ACTION_MS
                        + AgentVisualModel.CURSOR_AFTERGLOW_MS
                        + AgentVisualModel.CURSOR_POINTER_HOLD_MS
                        + AgentVisualModel.CURSOR_IDLE_DIM_MS + 1L);
        check(cursorIdle.phase == AgentVisualModel.CURSOR_PHASE_IDLE,
                "cursor must persist after acting instead of fading away");
        near(cursorIdle.alpha, AgentVisualModel.CURSOR_IDLE_ALPHA, 0.0001f,
                "persistent cursor must dim while idle");

        long pixelMove = cursor.moveCursorPixels(AgentVisualModel.GLOVE_POSE_BACK_LEFT,
                500f, 1_000f, 41_100L);
        check(pixelMove == mediumMove,
                "pixel-based movement must normalize distance against the display diagonal");
        AgentVisualModel.CursorFrame pointerTravel = cursor.cursorFrame(
                41_100L + pixelMove / 2L);
        check(pointerTravel.pose == AgentVisualModel.GLOVE_POSE_POINT
                        && pointerTravel.phase == AgentVisualModel.CURSOR_PHASE_TRAVELING
                        && pointerTravel.alpha > AgentVisualModel.CURSOR_IDLE_ALPHA,
                "travel toward every action must use and restore the default pointer");
        cursor.commitCursorAction(AgentVisualModel.GLOVE_POSE_BACK_LEFT,
                500L, 41_100L + pixelMove);
        AgentVisualModel.CursorFrame longAction = cursor.cursorFrame(
                41_100L + pixelMove + 350L);
        check(longAction.phase == AgentVisualModel.CURSOR_PHASE_ACTING
                        && longAction.pose == AgentVisualModel.GLOVE_POSE_BACK_LEFT
                        && longAction.alpha == 1f,
                "long swipe and Back paths must show only their action pose for the real duration");
        long actionEnd = 41_100L + pixelMove + 500L;
        AgentVisualModel.CursorFrame actionAfterglow = cursor.cursorFrame(
                actionEnd + AgentVisualModel.CURSOR_AFTERGLOW_MS / 2L);
        check(actionAfterglow.phase == AgentVisualModel.CURSOR_PHASE_AFTERGLOW
                        && actionAfterglow.pose == AgentVisualModel.GLOVE_POSE_BACK_LEFT,
                "the completed action pose must retain a short readable afterglow");
        AgentVisualModel.CursorFrame returnedPointer = cursor.cursorFrame(
                actionEnd + AgentVisualModel.CURSOR_AFTERGLOW_MS
                        + AgentVisualModel.CURSOR_POINTER_HOLD_MS / 2L);
        check(returnedPointer.phase == AgentVisualModel.CURSOR_PHASE_POINTER_HOLD
                        && returnedPointer.pose == AgentVisualModel.GLOVE_POSE_POINT
                        && returnedPointer.alpha == 1f,
                "the special pose must return to one fully visible default pointer");
        AgentVisualModel.CursorFrame dimPointer = cursor.cursorFrame(
                actionEnd + AgentVisualModel.CURSOR_AFTERGLOW_MS
                        + AgentVisualModel.CURSOR_POINTER_HOLD_MS
                        + AgentVisualModel.CURSOR_IDLE_DIM_MS + 1L);
        check(dimPointer.phase == AgentVisualModel.CURSOR_PHASE_IDLE
                        && dimPointer.pose == AgentVisualModel.GLOVE_POSE_POINT,
                "only the default pointer may remain after the action settles");
        near(dimPointer.alpha, AgentVisualModel.CURSOR_IDLE_ALPHA, 0.0001f,
                "settled pointer must use the subdued idle opacity");

        check(cursor.cursorFrame(60_000L) != null,
                "persistent cursor must remain through the 20 second activity lease");
        AgentVisualModel.CursorFrame cursorDuringFade = cursor.cursorFrame(60_900L);
        check(cursorDuringFade != null && cursorDuringFade.alpha > 0f
                        && cursorDuringFade.alpha < AgentVisualModel.CURSOR_IDLE_ALPHA,
                "idle cursor must fade with the shared activity envelope");
        check(cursor.cursorFrame(61_800L) == null,
                "cursor must clear visually when the activity fade completes");
        check(!cursor.hasCursorSession(),
                "cursor state must be discarded after the activity fade completes");
        cursor.noteActivity(62_000L);
        check(cursor.cursorFrame(62_120L) == null,
                "an expired cursor must not resurrect in a later activity session");

        AgentVisualModel scanCursor = new AgentVisualModel();
        scanCursor.noteActivity(70_000L);
        scanCursor.beginCursorSession(70_000L);
        check(scanCursor.beginScan("cursor-observe", 70_200L),
                "scan must be able to temporarily supersede the persistent cursor");
        check(scanCursor.cursorFrame(70_300L) == null,
                "cursor must hide while scanning without competing for attention");
        check(scanCursor.cursorFrame(71_600L) != null,
                "cursor must return at its retained position after scanning");

        boolean rejectedNullPose = false;
        try {
            cursor.beginCursorSession(0, 0L);
        } catch (IllegalArgumentException expected) {
            rejectedNullPose = true;
        }
        check(rejectedNullPose, "null cursor poses must be rejected explicitly");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="henyo-agent-visual-") as raw_dir:
    directory = Path(raw_dir)
    package_dir = directory / "link/liaru/henyo"
    package_dir.mkdir(parents=True)
    test_file = package_dir / "AgentVisualModelTest.java"
    test_file.write_text(TEST_SOURCE)
    subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(directory), str(MODEL), str(test_file)],
        check=True,
    )
    subprocess.run(
        ["java", "-cp", str(directory), "link.liaru.henyo.AgentVisualModelTest"],
        check=True,
    )

print("agent visual model verifier passed")
