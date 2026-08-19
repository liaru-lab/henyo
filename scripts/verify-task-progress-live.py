#!/usr/bin/env python3
"""Privacy-safe live verification for separated plan and caption presentation."""

import json
import os
import re
import subprocess
import sys
import tempfile
import time
from pathlib import Path
from urllib.request import Request, build_opener, ProxyHandler


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))

from henyo import helper  # noqa: E402


def ipc(payload):
    deadline = time.monotonic() + 6.0
    last_error = None
    while True:
        try:
            return helper.request_helper(payload, helper.default_socket_path())
        except OSError as exc:
            last_error = exc
        if time.monotonic() >= deadline:
            raise AssertionError("helper IPC socket did not become ready") from last_error
        started = helper.start_background(helper.default_socket_path())
        if not started.get("ok"):
            raise AssertionError("helper did not start")
        time.sleep(0.05)


def restart_helper_disconnected() -> dict:
    socket_path = helper.default_socket_path()
    try:
        stopped = helper.stop_helper(socket_path)
    except OSError:
        stopped = {"ok": True}
    if stopped.get("ok") is not True:
        raise AssertionError("helper stop failed")
    started = helper.start_background(socket_path)
    if started.get("ok") is not True:
        raise AssertionError("helper restart failed")
    status = ipc({"cmd": "status"})
    if status.get("wsConnected") is not False:
        raise AssertionError("fresh helper unexpectedly connected to Android WS")
    return status


def adb_screenshot() -> bytes:
    serial = os.environ.get("ADB_SERIAL", "").strip()
    command = ["adb"]
    if serial:
        command.extend(["-s", serial])
    data = subprocess.check_output([*command, "exec-out", "screencap", "-p"])
    if not data.startswith(b"\x89PNG\r\n\x1a\n"):
        raise AssertionError("ADB screenshot was not PNG")
    return data


def clean_screenshot() -> bytes:
    last_error = None
    for _attempt in range(3):
        request = Request(
            "http://127.0.0.1:8765/v1/screen/screenshot?includeIndicator=false",
            method="GET",
        )
        try:
            with build_opener(ProxyHandler({})).open(request, timeout=10) as response:
                data = response.read()
                if response.headers.get_content_type() != "image/png":
                    raise AssertionError("screenshot content type changed")
                return data
        except OSError as exc:
            last_error = exc
            time.sleep(0.15)
    raise AssertionError("clean screenshot failed after bounded retries") from last_error


def dimensions(path: Path) -> tuple[int, int]:
    output = subprocess.check_output([
        "ffprobe", "-v", "error", "-select_streams", "v:0",
        "-show_entries", "stream=width,height", "-of", "json", str(path),
    ], text=True)
    stream = json.loads(output)["streams"][0]
    return int(stream["width"]), int(stream["height"])


def decode_rgb(path: Path) -> bytes:
    return subprocess.check_output([
        "ffmpeg", "-hide_banner", "-loglevel", "error", "-i", str(path),
        "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
    ])


def capture_pair(directory: Path, label: str) -> dict:
    indicated_path = directory / f"{label}-indicator.png"
    clean_path = directory / f"{label}-clean.png"
    indicated_path.write_bytes(adb_screenshot())
    clean_path.write_bytes(clean_screenshot())
    os.chmod(indicated_path, 0o600)
    os.chmod(clean_path, 0o600)
    indicated_dimensions = dimensions(indicated_path)
    clean_dimensions = dimensions(clean_path)
    if indicated_dimensions != clean_dimensions:
        raise AssertionError("coordinate dimensions changed with overlay suppression")
    return {
        "indicator": decode_rgb(indicated_path),
        "clean": decode_rgb(clean_path),
        "width": indicated_dimensions[0],
        "height": indicated_dimensions[1],
    }


def display_density() -> float:
    configured = os.environ.get("HENYO_TEST_DISPLAY_DENSITY", "").strip()
    if configured:
        density = float(configured)
        if density <= 0:
            raise AssertionError("configured display density must be positive")
        return density
    serial = os.environ.get("ADB_SERIAL", "").strip()
    command = ["adb"]
    if serial:
        command.extend(["-s", serial])
    output = subprocess.check_output([*command, "shell", "wm", "density"], text=True)
    matches = re.findall(r"(?:Override|Physical) density:\s*(\d+)", output)
    if not matches:
        raise AssertionError("could not determine Android display density")
    return int(matches[-1]) / 160.0


def changed_ratio(first: bytes, second: bytes, width: int, region, threshold: int) -> float:
    x0, y0, x1, y1 = region
    changed = 0
    pixels = max(1, (x1 - x0) * (y1 - y0))
    for y in range(y0, y1):
        offset = (y * width + x0) * 3
        for _x in range(x0, x1):
            delta = max(
                abs(first[offset] - second[offset]),
                abs(first[offset + 1] - second[offset + 1]),
                abs(first[offset + 2] - second[offset + 2]),
            )
            if delta > threshold:
                changed += 1
            offset += 3
    return changed / pixels


def estimated_navy_opacity(indicated: bytes, clean: bytes, width: int, region) -> float:
    x0, y0, x1, y1 = region
    navy = (0, 17, 40)
    total = 0.0
    samples = 0
    for y in range(y0, y1):
        offset = (y * width + x0) * 3
        for _x in range(x0, x1):
            for channel in range(3):
                base = clean[offset + channel]
                denominator = navy[channel] - base
                if abs(denominator) >= 24:
                    estimate = (indicated[offset + channel] - base) / denominator
                    if 0.0 <= estimate <= 1.0:
                        total += estimate
                        samples += 1
            offset += 3
    if samples == 0:
        raise AssertionError("background opacity sample had no usable channels")
    return total / samples


def absolute_pixel_metrics(first: bytes, second: bytes, width: int, region) -> dict:
    x0, y0, x1, y1 = region
    channel_totals = [0, 0, 0]
    luminance_total = 0.0
    pixels = max(1, (x1 - x0) * (y1 - y0))
    for y in range(y0, y1):
        offset = (y * width + x0) * 3
        for _x in range(x0, x1):
            differences = [abs(first[offset + channel] - second[offset + channel])
                           for channel in range(3)]
            for channel in range(3):
                channel_totals[channel] += differences[channel]
            luminance_total += (differences[0] * 0.2126
                                + differences[1] * 0.7152
                                + differences[2] * 0.0722)
            offset += 3
    return {
        "rgb": [value / pixels for value in channel_totals],
        "luminanceFraction": luminance_total / pixels / 255.0,
    }


def vertical_edge_profile(image: bytes, width: int, region) -> list[float]:
    x0, y0, x1, y1 = region
    profile = []
    for y in range(y0, y1):
        total = 0
        offset = (y * width + x0) * 3
        previous = (image[offset] * 3 + image[offset + 1] * 6 + image[offset + 2]) // 10
        offset += 3
        for _x in range(x0 + 1, x1):
            current = (image[offset] * 3 + image[offset + 1] * 6 + image[offset + 2]) // 10
            difference = abs(current - previous)
            if difference > 14:
                total += difference
            previous = current
            offset += 3
        profile.append(float(total))
    scale = max(1.0, sum(profile))
    return [value / scale for value in profile]


def best_vertical_shift(first: bytes, second: bytes, width: int, region,
                        maximum_shift: int) -> int:
    first_profile = vertical_edge_profile(first, width, region)
    second_profile = vertical_edge_profile(second, width, region)
    best_shift = 0
    best_error = float("inf")
    for shift in range(-maximum_shift, maximum_shift + 1):
        start = max(0, -shift)
        end = min(len(first_profile), len(second_profile) - shift)
        if end <= start:
            continue
        error = sum(abs(first_profile[index] - second_profile[index + shift])
                    for index in range(start, end))
        if error < best_error:
            best_error = error
            best_shift = shift
    return best_shift


def result_payload(response):
    if response.get("type") != "result" or response.get("ok") is not True:
        raise AssertionError("WS call failed")
    result = response.get("result")
    if not isinstance(result, dict) or result.get("ok") is not True:
        raise AssertionError("progress application failed")
    return result


def progress_steps(first: str, second: str, third: str):
    return [
        {"text": "SYNTHETIC ITEM ONE", "status": first},
        {"text": "SYNTHETIC ITEM TWO", "status": second},
        {"text": "SYNTHETIC ITEM THREE", "status": third},
    ]


def main() -> None:
    runtime_root = Path(os.environ.get("TMPDIR", tempfile.gettempdir()))
    directory = None
    status = None
    try:
        with tempfile.TemporaryDirectory(prefix="henyo-progress-live-", dir=runtime_root) as raw_dir:
            directory = Path(raw_dir)
            density = display_density()
            ipc({"cmd": "progress.finish"})
            quiet_seconds = float(os.environ.get("HENYO_PROGRESS_LIVE_QUIET_SECONDS", "21.9"))
            if quiet_seconds < 20.0:
                raise AssertionError("quiet precondition must exceed the caption activity lease")
            time.sleep(quiet_seconds)
            result_payload(ipc({
                "cmd": "call", "op": "app.current", "params": {},
                "display": {"summary": "SYNTHETIC FIXED CAPTION"},
            }))
            time.sleep(1.0)
            controlled_caption_without_plan = capture_pair(directory, "controlled-caption-no-plan")
            restart_helper_disconnected()
            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC GOAL THREE ITEMS",
                "steps": progress_steps("completed", "pending", "pending"),
                "replan": False,
            }))
            result_payload(ipc({
                "cmd": "call", "op": "app.current", "params": {},
                "display": {"summary": "SYNTHETIC FIXED CAPTION"},
            }))
            time.sleep(1.0)
            controlled_caption_with_plan = capture_pair(directory, "controlled-caption-with-plan")
            restart_helper_disconnected()
            explicit_absence = result_payload(ipc({"cmd": "progress.finish"}))
            if explicit_absence.get("cleared") is not False:
                raise AssertionError("disconnected progress unexpectedly survived explicit finish")
            time.sleep(0.2)
            no_plan = capture_pair(directory, "plan-absent")
            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC GOAL THREE ITEMS",
                "steps": progress_steps("completed", "pending", "pending"),
                "replan": False,
            }))
            time.sleep(1.0)
            without_caption = capture_pair(directory, "plan-without-caption")
            width = without_caption["width"]
            height = without_caption["height"]
            if (no_plan["width"], no_plan["height"]) != (width, height):
                raise AssertionError("same-condition ADB screenshot dimensions changed")
            plan_top = round(height - 368 * density)
            plan_bottom = round(plan_top + 4 * 28 * density)
            shade_top = round(height - 400 * density)
            fade_lead_dp = 140
            fade_span_dp = fade_lead_dp + 32
            fade_start = round(shade_top - fade_lead_dp * density)
            plan_region = (
                max(0, round(18 * density)),
                max(0, round(plan_top - 5 * density)),
                min(width, round(width * 0.92)),
                min(height, round(plan_bottom + 5 * density)),
            )
            plan_text_region = (
                max(0, round(50 * density)),
                plan_region[1],
                plan_region[2],
                plan_region[3],
            )
            caption_region = (
                max(0, round(18 * density)),
                max(plan_region[3], round(height - 150 * density)),
                min(width, round(width * 0.92)),
                min(height, round(height - 24 * density)),
            )
            if caption_region[1] >= caption_region[3]:
                raise AssertionError("plan and caption test regions overlap")
            background_sample_left = round(width * 0.68)
            background_sample_right = round(width * 0.82)
            fade_region = (
                background_sample_left,
                max(0, fade_start),
                background_sample_right,
                min(height, plan_top),
            )
            fade_upper_region = (
                background_sample_left,
                max(0, round(fade_start + 12 * density)),
                background_sample_right,
                min(height, round(fade_start + 28 * density)),
            )
            fade_mid_region = (
                background_sample_left,
                max(0, round(fade_start + 80 * density)),
                background_sample_right,
                min(height, round(fade_start + 96 * density)),
            )
            plan_background_region = (
                background_sample_left,
                max(plan_region[1], round(plan_top)),
                background_sample_right,
                min(plan_region[3], round(plan_bottom)),
            )
            caption_background_region = (
                background_sample_left,
                max(caption_region[1], round(height - 110 * density)),
                background_sample_right,
                min(caption_region[3], round(height - 60 * density)),
            )
            reference_x = round(width * 700 / 1080)
            caption_reference_y = round(height * 1850 / 2412)
            reference_half_width = max(8, round(12 * density))
            reference_half_height = max(6, round(7 * density))
            caption_reference_region = (
                max(0, reference_x - reference_half_width),
                max(0, caption_reference_y - reference_half_height),
                min(width, reference_x + reference_half_width),
                min(height, caption_reference_y + reference_half_height),
            )
            plan_reference_y = round(plan_top + 70 * density)
            plan_reference_region = (
                max(0, reference_x - reference_half_width),
                max(0, plan_reference_y - reference_half_height),
                min(width, reference_x + reference_half_width),
                min(height, plan_reference_y + reference_half_height),
            )
            caption_parity_metrics = absolute_pixel_metrics(
                controlled_caption_without_plan["indicator"],
                controlled_caption_with_plan["indicator"],
                width, caption_reference_region,
            )
            if (max(caption_parity_metrics["rgb"]) > 8
                    and caption_parity_metrics["luminanceFraction"] > 0.05):
                raise AssertionError("progress changed the normal caption background")
            plan_absent_changed_ratio = changed_ratio(
                no_plan["indicator"], no_plan["clean"], width, plan_region, 8,
            )
            if plan_absent_changed_ratio >= 0.002:
                raise AssertionError("plan-absent capture retained progress presentation")
            plan_active_ratio = changed_ratio(
                without_caption["indicator"], without_caption["clean"],
                width, plan_region, 8,
            )
            if plan_active_ratio < 0.03:
                raise AssertionError("structured plan did not change its fixed region")
            fade_transition_ratio = changed_ratio(
                no_plan["indicator"], without_caption["indicator"],
                width, fade_region, 8,
            )
            if fade_transition_ratio < 0.05:
                raise AssertionError("same-condition ADB comparison did not detect the progress fade")
            fade_upper_modeled_navy = estimated_navy_opacity(
                without_caption["indicator"], without_caption["clean"],
                width, fade_upper_region,
            )
            fade_mid_modeled_navy = estimated_navy_opacity(
                without_caption["indicator"], without_caption["clean"],
                width, fade_mid_region,
            )
            if fade_upper_modeled_navy >= 0.1:
                raise AssertionError("progress fade becomes too strong near its transparent start")
            if not 0.12 <= fade_mid_modeled_navy <= 0.25:
                raise AssertionError("progress fade midpoint is not gradual")

            result_payload(ipc({
                "cmd": "call", "op": "app.current", "params": {},
                "display": {"summary": "SYNTHETIC FIXED CAPTION"},
            }))
            time.sleep(1.0)
            with_caption = capture_pair(directory, "plan-with-caption")
            plan_background_modeled_navy = estimated_navy_opacity(
                without_caption["indicator"], without_caption["clean"],
                width, plan_background_region,
            )
            caption_background_modeled_navy = estimated_navy_opacity(
                with_caption["indicator"], with_caption["clean"],
                width, caption_background_region,
            )
            background_modeled_navy_ratio = plan_background_modeled_navy / max(
                0.001, caption_background_modeled_navy,
            )
            controlled_plan_modeled_navy = estimated_navy_opacity(
                controlled_caption_with_plan["indicator"],
                controlled_caption_with_plan["clean"],
                width, plan_reference_region,
            )
            controlled_caption_modeled_navy = estimated_navy_opacity(
                controlled_caption_without_plan["indicator"],
                controlled_caption_without_plan["clean"],
                width, caption_reference_region,
            )
            controlled_reference_ratio = controlled_plan_modeled_navy / max(
                0.001, controlled_caption_modeled_navy,
            )
            if not 0.35 <= controlled_plan_modeled_navy <= 0.40:
                raise AssertionError("controlled plan background missed the 35-40% navy target")
            if not 0.85 <= controlled_reference_ratio <= 1.15:
                raise AssertionError("plan background did not match the controlled caption reference")
            if fade_mid_modeled_navy + 0.08 >= plan_background_modeled_navy:
                raise AssertionError("progress fade does not rise clearly into the plan plateau")
            caption_added_ratio = changed_ratio(
                without_caption["indicator"], with_caption["indicator"],
                width, caption_region, 8,
            )
            if caption_added_ratio < 0.001:
                raise AssertionError("controlled caption did not change the caption region")
            caption_anchor_shift = best_vertical_shift(
                without_caption["indicator"], with_caption["indicator"],
                width, plan_text_region, max(2, round(4 * density)),
            )
            if abs(caption_anchor_shift) > 1:
                raise AssertionError("caption presence moved the fixed plan Y anchor")

            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC GOAL THREE ITEMS",
                "steps": progress_steps("completed", "completed", "in_progress"),
                "replan": False,
            }))
            mismatch = ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC GOAL THREE ITEMS",
                "steps": [{"text": "SYNTHETIC CHANGED ITEM", "status": "in_progress"}],
                "replan": False,
            })
            if mismatch.get("type") != "error" or mismatch.get("code") != "op_invalid":
                raise AssertionError("structural mismatch did not fail closed")
            time.sleep(0.2)
            status_updated = capture_pair(directory, "status-updated")
            status_anchor_shift = best_vertical_shift(
                with_caption["indicator"], status_updated["indicator"],
                width, plan_text_region, max(2, round(4 * density)),
            )
            if abs(status_anchor_shift) > 1:
                raise AssertionError("status-only update moved the fixed plan Y anchor")

            finish = result_payload(ipc({"cmd": "progress.finish"}))
            if finish.get("cleared") is not True:
                raise AssertionError("progress finish did not clear active state")
            time.sleep(0.15)
            finished_with_caption = capture_pair(directory, "finished-with-caption")
            plan_removed_ratio = changed_ratio(
                status_updated["indicator"], finished_with_caption["indicator"],
                width, plan_region, 8,
            )
            if plan_removed_ratio < 0.002:
                raise AssertionError("finish did not remove rows from the fixed plan region")
            caption_remaining_ratio = changed_ratio(
                finished_with_caption["indicator"], finished_with_caption["clean"],
                width, caption_region, 8,
            )
            if caption_remaining_ratio < 0.01:
                raise AssertionError("controlled caption did not remain immediately after finish")
            caption_finish_change_ratio = changed_ratio(
                status_updated["indicator"], finished_with_caption["indicator"],
                width, caption_region, 8,
            )
            caption_retained_from_baseline_ratio = changed_ratio(
                without_caption["indicator"], finished_with_caption["indicator"],
                width, caption_region, 8,
            )
            if caption_retained_from_baseline_ratio < 0.001:
                raise AssertionError("caption region returned to its no-caption baseline after finish")
            if caption_finish_change_ratio >= plan_removed_ratio:
                raise AssertionError("finishing the plan unexpectedly removed the caption region")

            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC REPLANNED GOAL",
                "steps": [{"text": "SYNTHETIC REPLANNED ITEM", "status": "in_progress"}],
                "replan": True,
            }))
            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC LEGACY GOAL",
                "completed": ["SYNTHETIC LEGACY COMPLETE"],
                "current": "SYNTHETIC LEGACY CURRENT",
            }))

            result_payload(ipc({"cmd": "progress.finish"}))

            result_payload(ipc({
                "cmd": "progress.set",
                "goal": "SYNTHETIC RECONNECT GOAL",
                "steps": [{"text": "SYNTHETIC RECONNECT ITEM", "status": "in_progress"}],
                "replan": False,
            }))
            restart_helper_disconnected()
            reconnect_finish = result_payload(ipc({"cmd": "progress.finish"}))
            if reconnect_finish.get("cleared") is not False:
                raise AssertionError("task progress was replayed after reconnect")
    finally:
        status = restart_helper_disconnected()

    if directory is None or directory.exists():
        raise AssertionError("temporary screenshot artifacts were not cleaned")
    if status.get("wsConnected") is not False:
        raise AssertionError("live verifier left Android WS connected")
    print(json.dumps({
        "ok": True,
        "dimensions": f"{width}x{height}",
        "density": round(density, 4),
        "quietPreconditionSeconds": quiet_seconds,
        "planRegion": list(plan_region),
        "captionRegion": list(caption_region),
        "planBackgroundRegion": list(plan_background_region),
        "captionBackgroundRegion": list(caption_background_region),
        "captionReferenceRegion": list(caption_reference_region),
        "planReferenceRegion": list(plan_reference_region),
        "fadeRegion": list(fade_region),
        "fadeLeadDp": fade_lead_dp,
        "fadeSpanDp": fade_span_dp,
        "fadeTransitionChangedRatio": round(fade_transition_ratio, 6),
        "fadeUpperModeledNavy": round(fade_upper_modeled_navy, 6),
        "fadeMidModeledNavy": round(fade_mid_modeled_navy, 6),
        "captionParityRgbMeanAbsolute": [
            round(value, 6) for value in caption_parity_metrics["rgb"]
        ],
        "captionParityLuminanceFraction": round(
            caption_parity_metrics["luminanceFraction"], 6
        ),
        "planActiveChangedRatio": round(plan_active_ratio, 6),
        "planAbsentChangedRatio": round(plan_absent_changed_ratio, 6),
        "planBackgroundModeledNavy": round(plan_background_modeled_navy, 6),
        "captionBackgroundModeledNavy": round(caption_background_modeled_navy, 6),
        "backgroundModeledNavyRatio": round(background_modeled_navy_ratio, 6),
        "controlledPlanModeledNavy": round(controlled_plan_modeled_navy, 6),
        "controlledCaptionModeledNavy": round(controlled_caption_modeled_navy, 6),
        "controlledReferenceRatio": round(controlled_reference_ratio, 6),
        "planRemovedImmediatelyRatio": round(plan_removed_ratio, 6),
        "captionAddedChangedRatio": round(caption_added_ratio, 6),
        "captionRemainingChangedRatio": round(caption_remaining_ratio, 6),
        "captionFinishChangedRatio": round(caption_finish_change_ratio, 6),
        "captionRetainedFromBaselineRatio": round(caption_retained_from_baseline_ratio, 6),
        "captionAnchorShiftPx": caption_anchor_shift,
        "statusAnchorShiftPx": status_anchor_shift,
        "finishCleared": True,
        "captionRemainedAfterFinish": True,
        "statusUpdateApplied": True,
        "mismatchRejected": True,
        "explicitReplanApplied": True,
        "legacySnapshotApplied": True,
        "replayedAfterReconnect": False,
        "temporaryArtifactsCleaned": True,
        "sameConditionAdbComparison": True,
        "wsConnectedAfterVerification": False,
    }, separators=(",", ":")))


if __name__ == "__main__":
    main()
