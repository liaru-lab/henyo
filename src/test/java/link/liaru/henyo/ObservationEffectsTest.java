package link.liaru.henyo;

public final class ObservationEffectsTest {
    public static void main(String[] args) {
        check(ObservationEffectModel.Mode.fromValue(null) == ObservationEffectModel.Mode.FRAME_GLOW,
                "default is frame glow");
        for (ObservationEffectModel.Mode mode : ObservationEffectModel.Mode.values())
            check(ObservationEffectModel.Mode.fromValue(mode.value) == mode, "stored mode round trips");
        check(ObservationEffectModel.Mode.fromValue("unknown") == ObservationEffectModel.Mode.FRAME_GLOW,
                "unknown stored values have a safe default");
        ObservationEffectModel effect = new ObservationEffectModel();
        check(effect.beginFrame("one", 1000), "new observation starts one pulse");
        check(effect.frameActive(1000) && effect.frameAlpha(1000) == 0f, "pulse starts softly but schedules frames");
        check(effect.frameAlpha(1140) == 1f && effect.frameAlpha(1230) == 1f, "brief peak hold");
        check(effect.frameAlpha(1465) > .49f && effect.frameAlpha(1465) < .51f, "gentle fade");
        check(!effect.beginFrame("two", 1200), "a burst cannot restart the pulse");
        check(!effect.frameActive(1700) && effect.frameAlpha(1700) == 0f, "pulse ends after 700 ms");
        check(!effect.beginFrame("one", 1800), "same observation is not replayed");
        check(effect.beginFrame("two", 1800), "new observation may start after completion");
        effect.setMode(ObservationEffectModel.Mode.OFF);
        check(!effect.frameActive(1801) && !effect.beginFrame("three", 1801), "Off cancels and suppresses effects");

        AgentVisualModel visual = new AgentVisualModel();
        visual.noteActivity(0);
        visual.beginCursorSession(AgentVisualModel.GLOVE_POSE_POINT, 0);
        check(visual.beginObservation("frame", 0) && visual.observationActive(100), "frame mode is active");
        check(!visual.scanActive(100) && visual.cursorFrame(100) != null, "frame glow does not hide cursor feedback");
        visual.setObservationMode(ObservationEffectModel.Mode.SCAN_LINE);
        check(visual.observationFrameAlpha(100) == 0f, "switching clears old frame glow");
        check(visual.beginObservation("scan", 100), "scan mode starts the legacy scan");
        check(visual.scanPosition(800) > .49f && visual.scanPosition(800) < .51f, "scan retains its 1400-ms travel");
        check(visual.cursorFrame(800) == null, "scan retains its cursor suppression contract");
        visual.setObservationMode(ObservationEffectModel.Mode.OFF);
        check(!visual.observationActive(800) && !visual.beginObservation("off", 800), "Off starts no animation");
        check(visual.cursorFrame(800) != null, "mode changes do not clear control feedback");
        visual.setObservationMode(ObservationEffectModel.Mode.FRAME_GLOW);
        visual.beginObservation("cancel", 900); visual.clearScan();
        check(!visual.observationActive(1000), "stop/completion clears either observation mode");

        TargetFrameGeometry full = new TargetFrameGeometry(0, 0, 1000, 1800, 1_800_000, 2f);
        check(full.valid() && !full.cornersOnly && full.left == 12 && full.top == 12
                && full.right == 988 && full.bottom == 1788, "continuous frame stays inset from selected extent");
        TargetFrameGeometry occluded = new TargetFrameGeometry(0, 0, 1000, 1800, 900_000, 2f);
        check(occluded.cornersOnly && occluded.left == full.left && occluded.right == full.right,
                "substantial occlusion selects intentional corners without arbitrary cutouts");
        check(!new TargetFrameGeometry(0, 0, 1000, 1800, 1_170_000, 2f).cornersOnly, "65 percent visible retains a full frame");
        TargetFrameGeometry moved = new TargetFrameGeometry(100, 200, 800, 1200, 700_000, 1.5f);
        check(moved.left == 109 && moved.top == 209 && moved.right == 791 && moved.bottom == 1191,
                "frame follows resize/offset and display density");
        TargetFrameGeometry small = new TargetFrameGeometry(0, 0, 20, 40, 800, 3f);
        check(small.valid() && small.radius <= small.cornerLength && small.left < small.right,
                "small windows retain valid rounded corners");
        check(!new TargetFrameGeometry(0, 0, 0, 40, 0, 1).valid(), "empty windows have no frame");
        check(!new TargetFrameGeometry(0, 0, 100, 100, 0, 1).valid(), "fully hidden windows have no frame");
        System.out.println("ObservationEffectsTest passed");
    }
    private static void check(boolean value, String reason) { if (!value) throw new AssertionError(reason); }
}
