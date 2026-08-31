#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf -- "$TEST_DIR"' EXIT

javac -encoding UTF-8 -source 11 -target 11 -d "$TEST_DIR" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/WindowTargetResolver.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/WindowProbePlanner.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/WindowRootFailureCache.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/WindowRootProbeBudget.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/OperationTargetConstraint.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/TargetHistoryStore.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/WindowTargetResolverTest.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/WindowProbePlannerTest.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/WindowRootFailureCacheTest.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/WindowRootProbeBudgetTest.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/TargetHistoryStoreTest.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/TargetGlowModel.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/TargetGlowModelTest.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/ScreenshotCaptureMode.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/ScreenshotCaptureModeTest.java" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/TargetPresentationPolicy.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/TargetPresentationPolicyTest.java"
java -cp "$TEST_DIR" link.liaru.henyo.WindowTargetResolverTest
java -cp "$TEST_DIR" link.liaru.henyo.WindowProbePlannerTest
java -cp "$TEST_DIR" link.liaru.henyo.WindowRootFailureCacheTest
java -cp "$TEST_DIR" link.liaru.henyo.WindowRootProbeBudgetTest
java -cp "$TEST_DIR" link.liaru.henyo.TargetHistoryStoreTest
java -cp "$TEST_DIR" link.liaru.henyo.TargetGlowModelTest
java -cp "$TEST_DIR" link.liaru.henyo.ScreenshotCaptureModeTest
java -cp "$TEST_DIR" link.liaru.henyo.TargetPresentationPolicyTest
