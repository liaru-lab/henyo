#!/usr/bin/env python3
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/java/link/liaru/henyo/AgentActivityModel.java"
TEST_SOURCE = r'''
package link.liaru.henyo;

public final class AgentActivityModelTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        check("one two three".equals(AgentActivityModel.sanitize("  one\n\ttwo\u0000three  ")),
                "whitespace and controls must collapse safely");
        String emoji = "\ud83d\ude80";
        String bounded = AgentActivityModel.sanitize(emoji.repeat(100));
        check(bounded.codePointCount(0, bounded.length()) == AgentActivityModel.MAX_SUMMARY_CODE_POINTS,
                "summary bound must count code points, not UTF-16 units");
        check(!Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)),
                "summary bound must not split a surrogate pair");

        AgentActivityModel model = new AgentActivityModel();
        check(model.add("first", 0), "first row should be added");
        check(!model.add("first", 100), "adjacent duplicate should coalesce");
        check(model.snapshot(100).size() == 1, "duplicate must not grow the stack");
        check(model.snapshot(100).get(0).expiresAtMs == 11_900L, "duplicate must extend hold and fade lifetime");
        model.add("second", 200);
        model.add("third", 300);
        model.add("fourth", 400);
        check(model.snapshot(400).size() == 3, "stack must retain at most three rows");
        check("second".equals(model.snapshot(400).get(0).summary), "oldest row must be evicted first");

        AgentActivityModel.Message typed = new AgentActivityModel.Message(emoji + " done", 1_000L);
        String prefix = AgentActivityModel.visibleText(typed, 1_001L);
        check(prefix.codePointCount(0, prefix.length()) == 1, "typewriter must reveal whole code points");
        check(emoji.equals(prefix), "typewriter must not split emoji");
        check("⠋".equals(AgentActivityModel.spinnerFrame(0L)), "spinner must start at the first frame");
        check("⠙".equals(AgentActivityModel.spinnerFrame(AgentActivityModel.SPINNER_FRAME_MS)),
                "spinner must advance deterministically");
        check(AgentActivityModel.spinnerFrame(AgentActivityModel.SPINNER_FRAME_MS * 10L).equals("⠋"),
                "spinner must wrap through all frames");
        check(AgentActivityModel.displayText(typed, 1_001L, true).startsWith("⠙ " + emoji),
                "newest row must prefix the spinner without splitting typed text");
        check(AgentActivityModel.displayText(typed, 1_001L, false).equals(prefix),
                "older rows must have no spinner or caret");
        long revealedAt = typed.revealStartedAtMs + typed.revealDurationMs;
        check(AgentActivityModel.caretAlpha(typed, revealedAt) == 1f,
                "caret must begin fully visible after reveal");
        check(AgentActivityModel.caretAlpha(typed, revealedAt + AgentActivityModel.CARET_HOLD_ON_MS)
                        == 1f,
                "caret must hold before fading out");
        float fadingOut = AgentActivityModel.caretAlpha(typed,
                revealedAt + AgentActivityModel.CARET_HOLD_ON_MS
                        + AgentActivityModel.CARET_FADE_OUT_MS / 2L);
        check(fadingOut > AgentActivityModel.CARET_DIM_ALPHA && fadingOut < 1f,
                "caret fade-out must interpolate between bright and dim");
        check(AgentActivityModel.caretAlpha(typed,
                revealedAt + AgentActivityModel.CARET_HOLD_ON_MS
                        + AgentActivityModel.CARET_FADE_OUT_MS) == AgentActivityModel.CARET_DIM_ALPHA,
                "caret must settle at its dim alpha");
        check(AgentActivityModel.caretAlpha(typed, revealedAt + AgentActivityModel.CARET_CYCLE_MS)
                        == 1f,
                "caret easing cycle must wrap to fully visible");
        check(AgentActivityModel.alpha(typed, 0, 1_000L) == 0f, "new row must fade in");
        check(AgentActivityModel.alpha(typed, 2, 2_000L) <= 0.48f, "older rows must be dimmer");
        check(AgentActivityModel.alpha(typed, 2, 2_000L) >= 0.45f,
                "oldest visible row must remain readable on the navy panel");
        model.prune(12_500L);
        check(model.snapshot(12_500L).isEmpty(), "expired rows must be removed");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="henyo-agent-activity-") as raw_dir:
    directory = Path(raw_dir)
    package_dir = directory / "link/liaru/henyo"
    package_dir.mkdir(parents=True)
    test_file = package_dir / "AgentActivityModelTest.java"
    test_file.write_text(TEST_SOURCE)
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(directory), str(MODEL), str(test_file)], check=True)
    subprocess.run(["java", "-cp", str(directory), "link.liaru.henyo.AgentActivityModelTest"], check=True)

print("agent activity model verifier passed")
