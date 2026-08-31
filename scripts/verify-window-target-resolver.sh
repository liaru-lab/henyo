#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

PROJECT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
TEST_DIR=$(mktemp -d)
trap 'rm -rf -- "$TEST_DIR"' EXIT

javac -encoding UTF-8 -source 11 -target 11 -d "$TEST_DIR" \
    "$PROJECT_DIR/src/main/java/link/liaru/henyo/WindowTargetResolver.java" \
    "$PROJECT_DIR/src/test/java/link/liaru/henyo/WindowTargetResolverTest.java"
java -cp "$TEST_DIR" link.liaru.henyo.WindowTargetResolverTest
