#!/usr/bin/env python3
"""Focused model, protocol, helper/CLI, rendering, privacy, and regression checks."""

import contextlib
import io
import json
import subprocess
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/java/link/liaru/henyo/CompletionMessageModel.java"
ACTIVITY = ROOT / "src/main/java/link/liaru/henyo/AgentActivityModel.java"
OVERLAY = (ROOT / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java").read_text()
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
OPERATIONS = (ROOT / "src/main/java/link/liaru/henyo/WsOperation.java").read_text()
HELPER_SOURCE = (ROOT / "python/henyo/helper.py").read_text()
CLI_SOURCE = (ROOT / "python/henyo/cli.py").read_text()

sys.path.insert(0, str(ROOT / "python"))
import henyo.cli as cli  # noqa: E402
import henyo.helper as helper  # noqa: E402


ACCEPTANCE_MESSAGE = (
    "訪問情報が揃いました。王立美術館は評価4.5で現在は18時まで営業、公式チケット表示は"
    "13ユーロです。中央駅から徒歩約8分。展示は好評で待ち時間なしの声が多い一方、"
    "受付対応には厳しい意見もありました。"
)


def require(source: str, needle: str, message: str) -> None:
    if needle not in source:
        raise AssertionError(message)


def forbid(source: str, needle: str, message: str) -> None:
    if needle in source:
        raise AssertionError(message)


def verify_model() -> None:
    test_source = r'''
package link.liaru.henyo;

public final class CompletionMessageModelTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        String acceptance = "訪問情報が揃いました。王立美術館は評価4.5で現在は18時まで営業、公式チケット表示は13ユーロです。中央駅から徒歩約8分。展示は好評で待ち時間なしの声が多い一方、受付対応には厳しい意見もありました。";
        check(acceptance.codePointCount(0, acceptance.length()) == 100,
                "acceptance sample must remain exactly 100 code points");
        CompletionMessageModel model = new CompletionMessageModel();
        check(model.show(acceptance, 1_000L) == CompletionMessageModel.SHOW_ACCEPTED,
                "100-code-point Japanese completion must be accepted");
        check(model.current(1_000L).text.equals(acceptance),
                "accepted completion must be preserved without truncation or sanitation");

        String rocket = "\ud83d\ude80";
        String exact = rocket.repeat(125) + "界".repeat(125);
        check(exact.codePointCount(0, exact.length()) == 250,
                "supplementary characters must count as one code point");
        check(model.show(exact, 2_000L) == CompletionMessageModel.SHOW_ACCEPTED,
                "exactly 250 code points must be accepted");
        check(model.current(2_000L).text.equals(exact),
                "250-code-point payload must remain intact, including surrogate pairs");
        String tooLong = exact + rocket;
        check(tooLong.codePointCount(0, tooLong.length()) == 251,
                "over-limit fixture must be 251 code points");
        check(model.show(tooLong, 3_000L) == CompletionMessageModel.SHOW_TOO_LONG,
                "251 code points must fail explicitly");
        check(model.current(3_000L).text.equals(exact),
                "a rejected replacement must not alter the visible completion");

        check(model.current(2_000L + CompletionMessageModel.MESSAGE_HOLD_MS) != null,
                "completion must remain for the full 30-second hold");
        check(model.current(2_000L + CompletionMessageModel.MESSAGE_HOLD_MS
                + CompletionMessageModel.MESSAGE_FADE_OUT_MS) == null,
                "completion must clear after the following fade");
        check(CompletionMessageModel.MESSAGE_HOLD_MS == 30_000L,
                "completion hold contract must remain 30 seconds");
        check(CompletionMessageModel.MESSAGE_FADE_OUT_MS == 1_800L,
                "completion must use the existing gentle fade duration");
    }
}
'''
    with tempfile.TemporaryDirectory(prefix="henyo-completion-model-") as directory:
        directory_path = Path(directory)
        source_path = directory_path / "CompletionMessageModelTest.java"
        source_path.write_text(test_source, encoding="utf-8")
        subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", directory,
             str(MODEL), str(source_path)], check=True, cwd=ROOT
        )
        subprocess.run(
            ["java", "-cp", directory, "link.liaru.henyo.CompletionMessageModelTest"],
            check=True, cwd=ROOT,
        )


class RecorderWs:
    def __init__(self) -> None:
        self.completions = []

    def show_completion(self, message):
        self.completions.append(message)
        return {"type": "result", "ok": True, "result": {"ok": True, "applied": True}}


def verify_helper_and_cli() -> None:
    assert len(ACCEPTANCE_MESSAGE) == 100
    exact = "🚀" * 125 + "界" * 125
    explicit_pair = "\ud83d\ude80"
    assert helper.unicode_code_point_count(exact) == 250
    assert helper.unicode_code_point_count(explicit_pair) == 1

    daemon = helper.HelperDaemon(ROOT / "build" / "verify-completion-helper.sock")
    daemon.ws = RecorderWs()
    accepted = daemon.dispatch({"cmd": "completion.show", "message": ACCEPTANCE_MESSAGE})
    assert accepted["ok"] is True and ACCEPTANCE_MESSAGE not in json.dumps(accepted, ensure_ascii=False)
    assert daemon.ws.completions[-1] == ACCEPTANCE_MESSAGE
    assert daemon.dispatch({"cmd": "completion.show", "message": exact})["ok"] is True
    call_count = len(daemon.ws.completions)
    rejected = daemon.dispatch({"cmd": "completion.show", "message": exact + "x"})
    assert rejected["error"] == "completion_too_long"
    assert len(daemon.ws.completions) == call_count

    requests = []
    original_request = cli.helper_request
    cli.helper_request = lambda payload: requests.append(payload) or {
        "type": "result", "ok": True, "result": {"ok": True, "applied": True}
    }
    try:
        with contextlib.redirect_stdout(io.StringIO()):
            assert cli.main(["completion", "show", ACCEPTANCE_MESSAGE]) == 0
        assert requests == [{"cmd": "completion.show", "message": ACCEPTANCE_MESSAGE}]
    finally:
        cli.helper_request = original_request


def verify_contract_and_rendering() -> None:
    require(OPERATIONS, 'OP_TASK_COMPLETION_SHOW = "task.completion.show"',
            "dedicated completion WS operation is required")
    require(SERVICE, '"completion_too_long"', "WS over-limit error must be stable")
    require(SERVICE, '"completion_progress_active"',
            "completion must reject stale active progress")
    require(SERVICE, 'overlay.showTaskCompletion(message)',
            "completion text must reach only the completion presentation path")
    require(OVERLAY, "if (!progressModel.isEmpty()) return CompletionMessageModel.SHOW_PROGRESS_ACTIVE;",
            "completion and progress must never double-stack")
    require(OVERLAY, "activityModel.clear();",
            "completion must not inherit stale operation captions")
    require(OVERLAY, ".setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)",
            "completion must wrap using the full layout")
    completion_layout = OVERLAY[OVERLAY.index("private StaticLayout completionLayout("):
                                OVERLAY.index("private void drawCaption(")]
    forbid(completion_layout, "setEllipsize", "completion layout must never ellipsize")
    forbid(completion_layout, "setMaxLines", "completion layout must never cap lines")
    forbid(completion_layout, "spinner", "completion layout must not add a spinner")
    forbid(completion_layout, "caret", "completion layout must not add a caret")
    require(OVERLAY, "COMPLETION_BACKDROP_TOP_FRACTION = 0.42f",
            "completion backdrop must be bounded to the lower 58% of the display")
    require(OVERLAY, "Math.min(height - backdropHeightPx, completionY - 32f * density)",
            "completion backdrop must expand upward with wrapped layout height")

    # Conservative supported-target geometry: 1080x2412 at density/scaledDensity
    # 2.55. Assume a wide glyph is 1.2em and each line consumes 1.18em.
    width_px, height_px, density = 1080, 2412, 2.55
    text_width = min(width_px - round(24 * density) * 2, round(width_px * 0.88))
    glyph_px = 20 * density * 1.2
    chars_per_line = max(1, int(text_width // glyph_px))
    lines = (250 + chars_per_line - 1) // chars_per_line
    layout_height = lines * 20 * density * 1.18
    available_height = height_px * (1 - 0.42) - 56 * density
    assert layout_height <= available_height, (layout_height, available_height)

    # Operation captions retain their original independent bound and layout.
    activity_source = ACTIVITY.read_text()
    require(activity_source, "MAX_SUMMARY_CODE_POINTS = 72",
            "operation caption bound must remain unchanged")
    require(OVERLAY, ".setEllipsize(TextUtils.TruncateAt.END)",
            "operation captions must keep end ellipsis")
    require(OVERLAY, ".setMaxLines(2)", "operation captions must remain two lines")
    require(OVERLAY, "drawSpinner(", "operation captions must retain their spinner")
    require(OVERLAY, "drawCaret(", "operation captions must retain their caret")


def verify_privacy() -> None:
    require(SERVICE, '"{\\"ok\\":true,\\"applied\\":true}"',
            "completion acknowledgement must contain metadata only")
    forbid(SERVICE, "Log.d(\"completion", "completion text must not be logged")
    forbid(SERVICE, "Log.i(\"completion", "completion text must not be logged")
    forbid(HELPER_SOURCE, '"completionMessage"',
           "helper status/discovery must not retain completion text")
    forbid(CLI_SOURCE, 'print(message)', "CLI must not echo completion text directly")


def main() -> None:
    verify_model()
    verify_helper_and_cli()
    verify_contract_and_rendering()
    verify_privacy()
    print("completion summary verifier passed")


if __name__ == "__main__":
    main()
