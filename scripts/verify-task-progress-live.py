#!/usr/bin/env python3
"""Verify one shared activity surface using synthetic text and local captures."""
import json
import os
import re
import statistics
import struct
import subprocess
import sys
import tempfile
import time
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "python"))
from henyo.helper import WsClient  # noqa: E402


def adb(*args):
    command = ["adb"]
    serial = os.environ.get("ADB_SERIAL", "").strip()
    if serial:
        command.extend(["-s", serial])
    return subprocess.check_output([*command, *args])


def png_rgb(data):
    """Decode the non-interlaced eight-bit RGB/RGBA PNGs produced by Android."""
    assert data.startswith(b"\x89PNG\r\n\x1a\n"), "capture is not PNG"
    position, compressed = 8, bytearray()
    width = height = channels = 0
    while position < len(data):
        size = struct.unpack_from(">I", data, position)[0]
        kind = data[position + 4:position + 8]
        chunk = data[position + 8:position + 8 + size]
        position += size + 12
        if kind == b"IHDR":
            width, height, depth, color, _, _, interlace = struct.unpack(">IIBBBBB", chunk)
            assert depth == 8 and color in (2, 6) and interlace == 0, "unsupported capture format"
            channels = 3 if color == 2 else 4
        elif kind == b"IDAT":
            compressed.extend(chunk)
        elif kind == b"IEND":
            break
    raw = zlib.decompress(compressed)
    stride = width * channels
    assert len(raw) == (stride + 1) * height
    pixels = bytearray(width * height * channels)
    previous = bytearray(stride)
    for y in range(height):
        start = y * (stride + 1)
        method = raw[start]
        row = bytearray(raw[start + 1:start + 1 + stride])
        assert method in range(5), "unsupported PNG filter"
        if method == 1:
            for i in range(channels, stride):
                row[i] = (row[i] + row[i - channels]) & 255
        elif method == 2:
            for i in range(stride):
                row[i] = (row[i] + previous[i]) & 255
        elif method == 3:
            for i in range(stride):
                row[i] = (row[i] + ((row[i - channels] if i >= channels else 0) + previous[i]) // 2) & 255
        elif method == 4:
            for i in range(stride):
                a = row[i - channels] if i >= channels else 0
                b = previous[i]
                c = previous[i - channels] if i >= channels else 0
                p = a + b - c
                pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                predictor = a if pa <= pb and pa <= pc else b if pb <= pc else c
                row[i] = (row[i] + predictor) & 255
        pixels[y * stride:(y + 1) * stride] = row
        previous = row
    if channels == 3:
        return width, height, bytes(pixels)
    rgb = bytearray(width * height * 3)
    for channel in range(3):
        rgb[channel::3] = pixels[channel::channels]
    return width, height, bytes(rgb)


def health():
    with urllib.request.urlopen("http://127.0.0.1:8765/v1/health", timeout=3) as response:
        value = json.load(response)
        assert value.get("ok"), "service unavailable"
        return value.get("controlState")


def capture_pair(directory, label):
    indicated = adb("exec-out", "screencap", "-p")
    request = urllib.request.Request(
        "http://127.0.0.1:8765/v1/screen/screenshot?includeIndicator=false&captureMode=display")
    with urllib.request.urlopen(request, timeout=12) as response:
        assert response.headers.get_content_type() == "image/png"
        clean = response.read()
    paths = []
    for suffix, data in (("indicator", indicated), ("clean", clean)):
        path = directory / (label + "-" + suffix + ".png")
        path.write_bytes(data)
        path.chmod(0o600)
        paths.append(path)
    return paths


def shared_surface(pair, density):
    width, height, indicated = png_rgb(pair[0].read_bytes())
    clean_width, clean_height, clean = png_rgb(pair[1].read_bytes())
    assert (width, height) == (clean_width, clean_height), "capture geometry changed"
    right = 12 * density + min(width - 24 * density, 560 * density)
    # Sample the gap between plan icons and text, outside the target's edge glow.
    x = round(min(right - 6 * density, 41 * density))
    panel = (27, 30, 35)
    alphas, eligible = [], 0
    for y in range(round(height * .25), max(0, round(height - 48 * density))):
        offset = (y * width + x) * 3
        difference = [panel[c] - clean[offset + c] for c in range(3)]
        norm = sum(value * value for value in difference)
        # Near-identical foreground/background colors cannot identify opacity.
        if norm < 4096:
            continue
        alpha = sum((indicated[offset + c] - clean[offset + c]) * difference[c]
                    for c in range(3)) / norm
        if .65 <= alpha <= 1.05:
            eligible += 1
            expected = [clean[offset + c] + difference[c] * (240 / 255) for c in range(3)]
            # Exclude glyph edges; they are not background-compositing samples.
            if max(abs(indicated[offset + c] - expected[c]) for c in range(3)) <= 5:
                alphas.append(alpha)
    if eligible < 40 * density:
        return {"sharedSurfaceOpacity": None, "gradientSeamDelta": None,
                "manualReviewRequired": True, "reason": "insufficient_background_contrast"}
    assert len(alphas) >= 20 * density and len(alphas) >= eligible / 2, "shared panel surface was not detected"
    alphas.sort()
    opacity = statistics.median(alphas)
    seam_delta = alphas[round(len(alphas) * .9)] - alphas[round(len(alphas) * .1)]
    assert .76 <= opacity <= 1.0, "panel opacity changed"
    assert seam_delta < .08, "plan/comment background has a gradient seam"
    return {"sharedSurfaceOpacity": round(opacity, 4), "gradientSeamDelta": round(seam_delta, 4),
            "manualReviewRequired": False}


def assert_dismissed(pair):
    width, height, indicated = png_rgb(pair[0].read_bytes())
    w, h, clean = png_rgb(pair[1].read_bytes())
    assert (width, height) == (w, h)
    changed = total = 0
    for y in range(round(height * .4), round(height * .94), 4):
        for x in range(round(width * .1), round(width * .9), 4):
            offset = (y * width + x) * 3
            changed += max(abs(indicated[offset + c] - clean[offset + c]) for c in range(3)) > 12
            total += 1
    assert changed / max(1, total) < .01, "completion left persistent presentation"


def main():
    assert health() == "available", "verification requires idle control"
    label = os.environ.get("HENYO_PANEL_LIVE_LABEL", "synthetic")
    assert re.fullmatch(r"[a-z0-9-]{1,40}", label), "invalid synthetic fixture label"
    artifact_root = ROOT / "artifacts"
    artifact_root.mkdir(exist_ok=True)
    directory = Path(tempfile.mkdtemp(prefix="panel-live-" + label + "-", dir=artifact_root))
    output = adb("shell", "wm", "density").decode()
    matches = re.findall(r"(?:Override|Physical) density:\s*(\d+)", output)
    assert matches, "display density unavailable"
    density = int(matches[-1]) / 160
    client = WsClient("ws://127.0.0.1:8765/v1/ws/control", "", lambda _event: None)
    try:
        client.connect()
        assert client.call("app.current", {}).get("ok")
        steps = [
            {"text": "Read the first synthetic item and confirm the expected information", "status": "completed"},
            {"text": "Check the next synthetic item with a deliberately long step description", "status": "in_progress"},
            {"text": "Compare the remaining synthetic items", "status": "pending"},
            {"text": "Return the screen and control to the user", "status": "pending"},
        ]
        assert client.set_progress("Complete the synthetic verification plan", steps=steps).get("ok")
        time.sleep(.9)
        plan = capture_pair(directory, "plan")
        assert client.call("app.current", {}, display={
            "summary": "Checking the current item with a longer comment that wraps cleanly."
        }).get("ok")
        time.sleep(.9)
        combined = capture_pair(directory, "plan-comment")
        steps[1]["status"], steps[2]["status"] = "completed", "in_progress"
        assert client.set_progress("Complete the synthetic verification plan", steps=steps).get("ok")
        message = ("Synthetic verification completed. " * 8)[:250]
        assert len(message) == 250
        assert client.show_completion(message).get("ok")
        assert health() == "available"
        time.sleep(.25)
        long_completion = directory / "completion-long-indicator.png"
        long_completion.write_bytes(adb("exec-out", "screencap", "-p"))
        long_completion.chmod(0o600)
        time.sleep(3.4)
        finished = capture_pair(directory, "completed")
    finally:
        client.close()
    result = {"ok": True, "plan": shared_surface(plan, density),
              "combined": shared_surface(combined, density), "completionDismissed": True}
    assert_dismissed(finished)
    print(json.dumps(result, separators=(",", ":")))


if __name__ == "__main__":
    main()
