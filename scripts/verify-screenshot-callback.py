#!/usr/bin/env python3
"""Failure-inject the production screenshot callback using synthetic resources."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
callback = source[source.index("    private static final class ScreenshotPixels"):source.rfind("\n}")]
harness = r'''
package link.liaru.henyo;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class ScreenshotCallbackHarness {
    static String failure;
    static int wrapped, copied, hardwareRecycled, cpuRecycled;

    public static void main(String[] args) {
        for (String stage : new String[]{"", "wrap", "copy-null", "copy-throw",
                "encode-false", "encode-throw", "recycle-throw", "close-throw"}) {
            reset(stage);
            CaptureCompletion<ScreenshotPixels> completion = new CaptureCompletion<>();
            AtomicInteger restored = new AtomicInteger();
            ScreenshotCallback callback = new ScreenshotCallback(completion, restoreOnce(restored));
            ScreenshotResult result = new ScreenshotResult();
            callback.onSuccess(result);
            check(completion.isDone(), "terminal result: " + stage);
            check(result.buffer.closed == 1, "buffer closed: " + stage);
            check(restored.get() == 1, "restored once: " + stage);
            check(hardwareRecycled == wrapped, "hardware bitmap recycled: " + stage);
            check(cpuRecycled == copied, "CPU bitmap recycled: " + stage);
            boolean success = stage.isEmpty() || stage.endsWith("-throw")
                    && (stage.startsWith("recycle") || stage.startsWith("close"));
            check((completion.value().bytes != null) == success, "correct pixel outcome: " + stage);
        }

        reset("");
        CaptureCompletion<ScreenshotPixels> timeout = new CaptureCompletion<>();
        timeout.complete(ScreenshotPixels.failure("timeout"));
        AtomicInteger restored = new AtomicInteger();
        ScreenshotCallback late = new ScreenshotCallback(timeout, restoreOnce(restored));
        ScreenshotResult lateResult = new ScreenshotResult();
        late.onSuccess(lateResult);
        late.onFailure(3);
        check(lateResult.buffer.closed == 1 && wrapped == 0, "late buffer discarded without conversion");
        check("timeout".equals(timeout.value().error) && timeout.value().bytes == null,
                "late callback cannot overwrite timeout");
        check(restored.get() == 1, "late callbacks restore once");

        reset("");
        CaptureCompletion<ScreenshotPixels> failed = new CaptureCompletion<>();
        ScreenshotCallback callback = new ScreenshotCallback(failed, () -> {});
        callback.onFailure(3);
        ScreenshotResult afterFailure = new ScreenshotResult();
        callback.onSuccess(afterFailure);
        check("take_screenshot_failed_3".equals(failed.value().error), "platform error remains terminal");
        check(afterFailure.buffer.closed == 1 && wrapped == 0, "success after failure cleans up");
        System.out.println("ScreenshotCallbackHarness passed");
    }

    static Runnable restoreOnce(AtomicInteger count) {
        AtomicBoolean done = new AtomicBoolean();
        return () -> { if (done.compareAndSet(false, true)) count.incrementAndGet(); };
    }
    static void reset(String stage) {
        failure = stage; wrapped = copied = hardwareRecycled = cpuRecycled = 0;
    }
    static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }

    interface TakeScreenshotCallback {
        void onSuccess(ScreenshotResult result);
        void onFailure(int code);
    }
    static final class ScreenshotResult {
        final HardwareBuffer buffer = new HardwareBuffer();
        HardwareBuffer getHardwareBuffer() { return buffer; }
        Object getColorSpace() { return null; }
        long getTimestamp() { return 123; }
    }
    static final class HardwareBuffer {
        int closed;
        void close() {
            closed++;
            if (failure.equals("close-throw")) throw new IllegalStateException("synthetic close failure");
        }
    }
    static final class Bitmap {
        enum Config { ARGB_8888 }
        enum CompressFormat { PNG }
        final boolean hardware;
        Bitmap(boolean hardware) { this.hardware = hardware; }
        static Bitmap wrapHardwareBuffer(HardwareBuffer buffer, Object colorSpace) {
            if (failure.equals("wrap")) return null;
            wrapped++;
            return new Bitmap(true);
        }
        Bitmap copy(Config config, boolean mutable) {
            if (failure.equals("copy-throw")) throw new IllegalStateException("synthetic copy failure");
            if (failure.equals("copy-null")) return null;
            copied++;
            return new Bitmap(false);
        }
        int getWidth() { return 2; }
        int getHeight() { return 3; }
        boolean compress(CompressFormat format, int quality, ByteArrayOutputStream out) {
            if (failure.equals("encode-throw")) throw new IllegalStateException("synthetic encoding failure");
            if (failure.equals("encode-false")) return false;
            out.write(1);
            return true;
        }
        void recycle() {
            if (hardware) hardwareRecycled++; else cpuRecycled++;
            if (failure.equals("recycle-throw")) throw new IllegalStateException("synthetic recycle failure");
        }
    }
'''

with tempfile.TemporaryDirectory(prefix="henyo-callback-") as directory:
    path = Path(directory) / "ScreenshotCallbackHarness.java"
    path.write_text(harness + callback + "\n}\n")
    subprocess.run(["javac", "--release", "11", "-d", directory,
                    str(root / "src/main/java/link/liaru/henyo/CaptureCompletion.java"), str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "link.liaru.henyo.ScreenshotCallbackHarness"], check=True)
