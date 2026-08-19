#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/.." && pwd)"
test_dir="$(mktemp -d)"
trap 'rm -rf "$test_dir"' EXIT

mkdir -p "$test_dir/link/liaru/henyo"
cp "$repo_root/src/main/java/link/liaru/henyo/ScreenshotCoordinateMapper.java" "$test_dir/link/liaru/henyo/"
cp "$repo_root/src/test/java/link/liaru/henyo/ScreenshotCoordinateMapperTest.java" "$test_dir/link/liaru/henyo/"

javac -d "$test_dir/classes" "$test_dir/link/liaru/henyo/"*.java
java -cp "$test_dir/classes" link.liaru.henyo.ScreenshotCoordinateMapperTest
