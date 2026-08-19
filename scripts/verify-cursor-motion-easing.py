#!/usr/bin/env python3
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
EASING = ROOT / "src/main/java/link/liaru/henyo/MotionEasing.java"
OVERLAY = ROOT / "src/main/java/link/liaru/henyo/ConnectionStatusOverlay.java"
TEST_SOURCE = r'''
package link.liaru.henyo;

public final class MotionEasingTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        near(MotionEasing.doubleSmoothstep(-1f), 0f, 0f, "values below zero must clamp");
        near(MotionEasing.doubleSmoothstep(0f), 0f, 0f, "curve must start exactly at zero");
        near(MotionEasing.doubleSmoothstep(0.5f), 0.5f, 0.000001f,
                "symmetric curve must cross the midpoint");
        near(MotionEasing.doubleSmoothstep(1f), 1f, 0f, "curve must end exactly at one");
        near(MotionEasing.doubleSmoothstep(2f), 1f, 0f, "values above one must clamp");

        float previous = MotionEasing.doubleSmoothstep(0f);
        for (int i = 1; i <= 1_000; i++) {
            float t = i / 1_000f;
            float value = MotionEasing.doubleSmoothstep(t);
            check(value >= previous, "curve must never reverse or bounce");
            check(value >= 0f && value <= 1f, "curve must never overshoot");
            near(value + MotionEasing.doubleSmoothstep(1f - t), 1f, 0.000002f,
                    "curve must be symmetric");
            previous = value;
        }
        for (int i = 1; i < 10; i++) {
            check(MotionEasing.doubleSmoothstep(i / 10f)
                            > MotionEasing.doubleSmoothstep((i - 1) / 10f),
                    "representative interior samples must be strictly increasing");
        }

        float startStep = MotionEasing.doubleSmoothstep(0.01f)
                - MotionEasing.doubleSmoothstep(0f);
        float middleStep = MotionEasing.doubleSmoothstep(0.505f)
                - MotionEasing.doubleSmoothstep(0.495f);
        float endStep = MotionEasing.doubleSmoothstep(1f)
                - MotionEasing.doubleSmoothstep(0.99f);
        check(startStep < 0.00001f && endStep < 0.00001f,
                "start and end movement increments must be nearly zero");
        check(middleStep > startStep * 1_000f && middleStep > endStep * 1_000f,
                "middle movement must be dramatically faster than either endpoint");
    }
}
'''


def require(text: str, needle: str, message: str) -> None:
    if needle not in text:
        raise AssertionError(message)


overlay = OVERLAY.read_text()
require(
    overlay,
    "MotionEasing.doubleSmoothstep((nowMs - travelStartedAtMs)",
    "pre-action pointer travel must use the sharp shared easing curve",
)
require(
    overlay,
    "MotionEasing.doubleSmoothstep(\n                    (nowMs - actionStartedAtMs)",
    "Back and swipe/scroll action paths must use the sharp shared easing curve",
)
for operation in ("showPoint", "showSwipe", "showBack"):
    require(
        overlay,
        f"long {operation}",
        f"{operation} must remain routed through the shared coordinate controller",
    )

with tempfile.TemporaryDirectory() as directory:
    temp = Path(directory)
    test_file = temp / "MotionEasingTest.java"
    test_file.write_text(TEST_SOURCE)
    subprocess.run(
        ["javac", "-d", str(temp), str(EASING), str(test_file)],
        check=True,
        cwd=ROOT,
    )
    subprocess.run(
        ["java", "-cp", str(temp), "link.liaru.henyo.MotionEasingTest"],
        check=True,
        cwd=ROOT,
    )

print("cursor motion easing verifier passed")
