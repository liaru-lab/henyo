#!/usr/bin/env python3
"""Deterministic contract and wiring checks for app.openUri."""

import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONTRACT = ROOT / "src/main/java/link/liaru/henyo/OpenUriContract.java"
OPERATIONS = ROOT / "src/main/java/link/liaru/henyo/WsOperation.java"
SERVICE = ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java"

TEST_SOURCE = r'''
package link.liaru.henyo;

public final class OpenUriContractTest {
    private static void expect(String actual, String expected, String label) {
        if (!expected.equals(actual)) {
            throw new AssertionError(label + ": expected " + expected + " but got " + actual);
        }
    }

    public static void main(String[] args) {
        expect(OpenUriContract.validateUri("https://example.com/resource?q=one%20two"), "", "https");
        expect(OpenUriContract.validateUri("example-app://resource/123"), "", "custom scheme");
        expect(OpenUriContract.validateUri("custom:value"), "", "opaque URI");
        expect(OpenUriContract.validateUri(null), "missing_uri", "missing URI");
        expect(OpenUriContract.validateUri(""), "missing_uri", "empty URI");
        expect(OpenUriContract.validateUri("relative/path"), "invalid_uri", "relative URI");
        expect(OpenUriContract.validateUri("1bad:value"), "invalid_uri", "invalid scheme");
        expect(OpenUriContract.validateUri("custom:bad value"), "invalid_uri", "unencoded space");
        expect(OpenUriContract.validateUri("custom:%zz"), "invalid_uri", "invalid escape");
        for (String scheme : new String[] {"file", "content", "javascript", "data", "intent"}) {
            expect(OpenUriContract.validateUri(scheme + ":value"), "disallowed_uri_scheme", scheme);
            expect(OpenUriContract.validateUri(scheme.toUpperCase() + ":value"),
                    "disallowed_uri_scheme", scheme + " uppercase");
        }
        expect(OpenUriContract.validateUri("x:" + "a".repeat(2046)), "", "2048 code points");
        expect(OpenUriContract.validateUri("x:" + "a".repeat(2047)),
                "uri_too_long", "2049 code points");
        expect(OpenUriContract.validateUri("x:" + "😀".repeat(2046)), "", "supplementary code points");

        expect(OpenUriContract.validatePackage(null, false), "", "omitted package");
        expect(OpenUriContract.validatePackage("com.example.app", true), "", "package");
        expect(OpenUriContract.validatePackage("com.example_app.viewer2", true), "", "package characters");
        expect(OpenUriContract.validatePackage(null, true), "invalid_package", "null package");
        expect(OpenUriContract.validatePackage("", true), "invalid_package", "empty package");
        expect(OpenUriContract.validatePackage("single", true), "invalid_package", "single segment");
        expect(OpenUriContract.validatePackage("1bad.example", true), "invalid_package", "leading digit");
        expect(OpenUriContract.validatePackage("com.example-app", true), "invalid_package", "hyphen");

        System.out.println("open URI contract verifier passed");
    }
}
'''


def require(source: str, needle: str, label: str) -> None:
    if needle not in source:
        raise AssertionError(label)


with tempfile.TemporaryDirectory(prefix="henyo-open-uri-") as directory:
    test_path = Path(directory) / "link/liaru/henyo/OpenUriContractTest.java"
    test_path.parent.mkdir(parents=True)
    test_path.write_text(TEST_SOURCE, encoding="utf-8")
    subprocess.run([
        "javac", "-encoding", "UTF-8", "-source", "11", "-target", "11",
        "-d", directory, str(CONTRACT), str(test_path),
    ], check=True)
    subprocess.run(["java", "-cp", directory, "link.liaru.henyo.OpenUriContractTest"], check=True)

operations = OPERATIONS.read_text(encoding="utf-8")
service = SERVICE.read_text(encoding="utf-8")
require(operations, 'OP_APP_OPEN_URI = "app.openUri"', "operation constant is required")
require(operations, "SPEC_APP_OPEN_URI", "operation spec is required")
require(service, "executeWsOpenUri(id, paramsJson, started)", "WS dispatch is required")
require(service, "Intent(Intent.ACTION_VIEW, data)", "ACTION_VIEW Intent is required")
require(service, "Intent.FLAG_ACTIVITY_NEW_TASK", "new-task flag is required")
require(service, "intent.resolveActivity(getPackageManager())", "resolution is required")
require(service, '"no_matching_activity"', "stable unresolved error is required")
require(service, '"start_failed"', "stable dispatch error is required")
require(service, "OP_APP_OPEN_URI.equals(spec.op)", "post-action settling is required")
if "/v1/app/open-uri" in service or "/v1/app/openUri" in service:
    raise AssertionError("app.openUri must not add an HTTP v1 endpoint")

dispatch = service.split("private WsCallResult executeWsOpenUri", 1)[1].split(
    "private Response appCurrent", 1
)[0]
if dispatch.count("startActivity(intent)") != 1:
    raise AssertionError("dispatch must contain exactly one startActivity call")
if dispatch.index("intent.resolveActivity") > dispatch.index("startActivity(intent)"):
    raise AssertionError("resolution must precede dispatch")
if "Log." in dispatch or "params.uri +" in dispatch or "escape(params.uri)" in dispatch:
    raise AssertionError("raw URI must not be logged or serialized")

print("open URI wiring verifier passed")
