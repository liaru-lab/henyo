#!/usr/bin/env python3
"""Deterministic contract checks for session-ephemeral progress presentation."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
OPS = (ROOT / "src/main/java/link/liaru/henyo/WsOperation.java").read_text()
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
OVERLAY = (ROOT / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java").read_text()
HELPER = (ROOT / "python/henyo/helper.py").read_text()
CLI = (ROOT / "python/henyo/cli.py").read_text()
PROTOCOL = (ROOT / "docs/ws-control-protocol.md").read_text()
IPC = (ROOT / "docs/helper-ipc.md").read_text()
SKILL = (ROOT / "skills/henyo-android-control/SKILL.md").read_text()
LIVE = (ROOT / "scripts/verify-task-progress-live.py").read_text()


def require(source: str, marker: str, message: str) -> None:
    if marker not in source:
        raise AssertionError(message)


for op in ("task.progress.set", "task.progress.finish"):
    require(OPS, op, f"{op} must be a supported WS operation")
    require(PROTOCOL, op, f"{op} must be documented")

require(SERVICE, "setTaskProgress(session", "progress must be owned by its WS session")
require(SERVICE, "clearTaskProgress(session, false)",
        "finish must clear only session-owned progress")
require(SERVICE, "releaseTaskProgress(session)",
        "disconnect must release session-owned progress without replay")
require(SERVICE, "Progress set requires goal, completed, or current",
        "empty progress snapshots must fail closed")
require(SERVICE, "readTaskProgressSteps", "structured steps need strict Android parsing")
require(SERVICE, "Progress plan differs; set replan true to replace it",
        "structural updates must require explicit replan")
require(SERVICE, "Structured progress cannot include completed or current",
        "structured and legacy payloads must not be ambiguous")
require(SERVICE, "Replan requires structured progress steps",
        "direct WS legacy payloads must not silently ignore replan")
require(OVERLAY, "progressOwner != owner", "a different disconnect must not clear active progress")
require(OVERLAY, "progressModel.clear();", "disconnect must remove stale progress")
require(OVERLAY, "float planTop = height - backdropHeightPx + 32f * density;",
        "plan must use a fixed anchor independent of caption rows")
require(OVERLAY, "drawTaskProgress(canvas, progressRows, left, textWidth, planTop, now)",
        "plan drawing must not consume the caption layout cursor")
require(OVERLAY, "drawActivityBackground(canvas, backgroundAlpha, progressRows.size(), dynamicShadeTop)",
        "one background pass must select progress shading only for active rows")
require(OVERLAY, "progressRowCount > 0 ? progressActivityShadeShader : activityShadeShader",
        "finish must restore the independent caption background shader")
require(HELPER, "def set_progress", "Python WS client needs an explicit set method")
require(HELPER, "def finish_progress", "Python WS client needs an explicit finish method")
require(HELPER, 'if cmd == "progress.set"', "helper IPC needs explicit progress.set")
require(HELPER, 'if cmd == "progress.finish"', "helper IPC needs explicit progress.finish")
require(CLI, 'if cmd == "progress"', "CLI needs explicit progress commands")
require(CLI, 'option == "--step"', "CLI must accept ordered structured steps")
require(CLI, 'option == "--replan"', "CLI must expose intentional replanning")
require(IPC, '"cmd":"progress.set"', "helper IPC schema must be documented exactly")
require(IPC, '"cmd":"progress.finish"', "helper finish schema must be documented exactly")
require(SKILL, "bin/henyo progress finish", "canonical Skill must require explicit finish")
for source, label in ((PROTOCOL, "protocol"), (IPC, "helper IPC"), (SKILL, "canonical Skill")):
    require(source, "in_progress", f"{label} must document structured statuses")
    require(source, "replan", f"{label} must document explicit replanning")
if '"progress"' in HELPER.split("self.cache: Dict[str, Any] =", 1)[1].split("}", 1)[0]:
    raise AssertionError("helper cache must not retain presentation progress")

for marker in (
    "plan_region", "caption_region", "planRemovedImmediatelyRatio",
    "captionRemainedAfterFinish", "captionRetainedFromBaselineRatio",
    "captionAnchorShiftPx", "statusAnchorShiftPx", "time.sleep(0.15)",
    "planBackgroundModeledNavy", "captionBackgroundModeledNavy",
    "backgroundModeledNavyRatio", "planBackgroundRegion",
    "captionBackgroundRegion", "sameConditionAdbComparison",
    "fadeLeadDp", "fadeSpanDp", "fadeUpperModeledNavy", "fadeMidModeledNavy",
    "captionParityRgbMeanAbsolute", "captionParityLuminanceFraction",
    "controlledPlanModeledNavy", "controlledCaptionModeledNavy",
    "controlledReferenceRatio", "planAbsentChangedRatio",
    "helper.stop_helper", "restart_helper_disconnected",
    "temporaryArtifactsCleaned",
):
    require(LIVE, marker, "live verifier must measure separated plan/caption regions")
if "finishedLowerChangedRatio" in LIVE or "sleep(23" in LIVE:
    raise AssertionError("live verifier must not rely on whole-overlay lease expiry")
if "time.sleep(0.5)" in LIVE:
    raise AssertionError("live verifier must wait for helper shutdown instead of sleeping")

print("task progress contract verifier passed")
