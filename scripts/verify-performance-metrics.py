#!/usr/bin/env python3
"""Android-free checks for bounded, content-free performance counters."""

import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/java/link/liaru/henyo/PerformanceMetrics.java"
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
OVERLAY = (ROOT / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java").read_text()

TEST_SOURCE = r'''
package link.liaru.henyo;

public final class PerformanceMetricsTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) {
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.beginLaunch(1_000L);
        metrics.recordLaunchDispatch(2_000_000L, true);
        metrics.recordMainHeartbeat(1_100L, 35L);
        metrics.recordActivityDraw(1_120L, 3_000_000L);
        metrics.recordActivityDraw(1_200L, 4_000_000L);
        metrics.recordAccessibility(2, 1_210L, 500_000L);
        metrics.recordSessionListLockWait(700_000L);
        metrics.recordSessionWriteLockWait(800_000L);
        metrics.recordStaticLayout(1_220L, 900_000L);
        String json = metrics.toJson();
        check(json.contains("\"launch\":{\"count\":1"), "launch count missing");
        check(json.contains("\"launchGapCount\":1"), "launch draw gap missing");
        check(json.contains("\"majorCount\":1"), "coarse event count missing");
        check(json.contains("\"launchCount\":1"), "launch layout count missing");
        check(!json.contains("message") && !json.contains("package") && !json.contains("text"),
                "metrics must contain no content fields");
        metrics.reset();
        check(metrics.toJson().contains("\"launch\":{\"count\":0"), "reset failed");
    }
}
'''


def require(source: str, marker: str, message: str) -> None:
    if marker not in source:
        raise AssertionError(message)


require(SERVICE, '"/v1/debug/performance"', "local metrics snapshot endpoint missing")
require(SERVICE, '"/v1/debug/performance/reset"', "local metrics reset endpoint missing")
require(SERVICE, "recordSessionWriteLockWait", "session write-lock wait must be measured")
require(SERVICE, "recordAccessibility", "coarse accessibility cost must be measured")
endpoint_policy = SERVICE.split("private static int endpointClass", 1)[1].split(
    "private static Response sourceDenied", 1
)[0]
if '"/v1/debug/' in endpoint_policy:
    raise AssertionError("debug metrics must not be classified as a remote endpoint")
require(endpoint_policy, 'if (path.startsWith("/v1/")) return ENDPOINT_UNKNOWN;',
        "unknown v1 debug endpoints must remain denied to remote sources")
require(OVERLAY, "MAIN_HEARTBEAT_MS = 100L", "main heartbeat must remain bounded at 10Hz")
require(OVERLAY, "recordActivityDraw", "activity draw timing must be measured")
require(OVERLAY, "recordStaticLayout", "layout build timing must be measured")
require(OVERLAY, "CAPTION_FRAME_MS = 32L", "caption-only animation must use the bounded cadence")
require(OVERLAY, "postInvalidateDelayed(CAPTION_FRAME_MS, 0, dirtyTop, width, height)",
        "caption-only animation must invalidate only the lower dirty region")
require(OVERLAY, "if (fullScreenMotion) {\n                postInvalidateOnAnimation();",
        "scan and cursor motion must retain full-view vsync animation")
if "if (running) postInvalidateOnAnimation();" in OVERLAY:
    raise AssertionError("caption presence must not force unconditional full-view vsync invalidation")

with tempfile.TemporaryDirectory(prefix="henyo-performance-metrics-") as raw_dir:
    directory = Path(raw_dir)
    package_dir = directory / "link/liaru/henyo"
    package_dir.mkdir(parents=True)
    test_file = package_dir / "PerformanceMetricsTest.java"
    test_file.write_text(TEST_SOURCE)
    subprocess.run(
        ["javac", "-encoding", "UTF-8", "-d", str(directory), str(MODEL), str(test_file)],
        check=True,
    )
    subprocess.run(
        ["java", "-cp", str(directory), "link.liaru.henyo.PerformanceMetricsTest"], check=True
    )

print("performance metrics verifier passed")
