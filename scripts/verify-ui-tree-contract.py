#!/usr/bin/env python3
"""Content-free conformance checks for the vendored common ui.tree contract."""

import copy
import hashlib
import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
FIXTURES = ROOT / "tests" / "fixtures" / "ui-tree-contract"
ANDROID_SOURCE = ROOT / "src" / "main" / "java" / "link" / "liaru" / "henyo" / "HenyoAccessibilityService.java"
SCHEMA_SHA256 = "8307fc7a2bf72a7a036ecfe85bc5fcb0228890af9ca50a2889097244f51eb0af"
READ_FIXTURE_SHA256 = "2392fef41f19239e538b98747417d2d5992150122a204c2249eeea64216c02ce"
NODE_FIELDS = {
    "text", "desc", "viewId", "className", "clickable", "editable",
    "focused", "enabled", "depth", "index", "bounds", "children",
}
RESULT_FIELDS = {
    "ok", "schemaRevision", "treeVersion", "serviceEpoch", "capturedAt",
    "root", "truncated",
}
BOUNDS = re.compile(r"^-?[0-9]+,-?[0-9]+,-?[0-9]+,-?[0-9]+$")


class ContractError(Exception):
    pass


def require(ok, code):
    if not ok:
        raise ContractError(code)


def load(name):
    return json.loads((FIXTURES / name).read_text(encoding="utf-8"))


def sha256(name):
    return hashlib.sha256((FIXTURES / name).read_bytes()).hexdigest()


def scalar_string(value, minimum=0, maximum=4096, utf8_maximum=16384):
    if not isinstance(value, str) or not minimum <= len(value) <= maximum:
        return False
    try:
        return len(value.encode("utf-8")) <= utf8_maximum
    except UnicodeEncodeError:
        return False


def validate_node(node, expected_depth=0, count=None):
    require(isinstance(node, dict) and set(node) == NODE_FIELDS, "node-shape")
    if count is None:
        count = [0]
    count[0] += 1
    require(count[0] <= 1200, "node-count")
    for field in ("text", "desc", "viewId"):
        require(scalar_string(node[field]), "node-string")
    require(scalar_string(node["className"], minimum=1), "node-class")
    require(type(node["clickable"]) is bool or node["clickable"] is None, "node-clickable")
    require(type(node["editable"]) is bool, "node-editable")
    require(type(node["focused"]) is bool or node["focused"] is None, "node-focused")
    require(type(node["enabled"]) is bool, "node-enabled")
    require(type(node["depth"]) is int and node["depth"] == expected_depth <= 32, "node-depth")
    require(type(node["index"]) is int and node["index"] >= 0, "node-index")
    require(isinstance(node["bounds"], str) and BOUNDS.fullmatch(node["bounds"]) is not None, "node-bounds")
    require(isinstance(node["children"], list) and len(node["children"]) <= 1200, "node-children")
    for child in node["children"]:
        validate_node(child, expected_depth + 1, count)
    return count[0]


def validate_result(result):
    require(isinstance(result, dict) and set(result) == RESULT_FIELDS, "result-shape")
    require(result["ok"] is True, "result-ok")
    require(result["schemaRevision"] == "henyo.ui-tree/1", "result-revision")
    require(type(result["treeVersion"]) is int and result["treeVersion"] >= 1, "result-version")
    require(scalar_string(result["serviceEpoch"], 1, 256, 1024), "result-epoch")
    require(scalar_string(result["capturedAt"], 20, 40, 160), "result-time")
    require(result["truncated"] is False, "result-truncated")
    validate_node(result["root"])
    encoded = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    require(len(encoded) <= 1048576, "result-bytes")


def main():
    require(sha256("ui-tree-result.schema.json") == SCHEMA_SHA256, "schema-vendor-hash")
    require(sha256("ui-tree-read.json") == READ_FIXTURE_SHA256, "fixture-vendor-hash")
    schema = load("ui-tree-result.schema.json")
    node_schema = schema.get("$defs", {}).get("node", {})
    require(set(schema.get("required", [])) == RESULT_FIELDS, "schema-result-fields")
    require(set(node_schema.get("required", [])) == NODE_FIELDS, "schema-node-fields")
    require(schema.get("additionalProperties") is False, "schema-result-closed")
    require(node_schema.get("additionalProperties") is False, "schema-node-closed")

    fixture = load("ui-tree-read.json")
    require(isinstance(fixture.get("expectedFrames"), list) and len(fixture["expectedFrames"]) == 1, "fixture-result-count")
    validate_result(fixture["expectedFrames"][0].get("result"))
    request = fixture.get("clientFrames")
    require(isinstance(request, list) and len(request) == 1, "fixture-request-count")
    require(request[0].get("op") == "ui.tree", "fixture-request-op")
    require(request[0].get("params") == {"maxDepth": 8, "maxNodes": 500, "redact": False}, "fixture-request-params")

    android = load("android-nested-node.json")
    require(validate_node(android) == 2, "android-node-count")
    require(type(android["clickable"]) is bool and type(android["focused"]) is bool, "android-nullability")
    nullable = copy.deepcopy(android)
    nullable["clickable"] = None
    nullable["focused"] = None
    validate_node(nullable)
    source = ANDROID_SOURCE.read_text(encoding="utf-8")
    serializer = source.split("private void appendNode", 1)[1].split("private void appendFlatNodes", 1)[0]
    serializer = serializer.replace('\\"', '"')
    for field in ("text", "desc", "viewId", "className", "clickable", "editable",
                  "focused", "enabled", "depth", "index", "bounds", "children"):
        require(f'"{field}"' in serializer, "android-source-shape")
    tree_method = source.split("private Response uiTree", 1)[1].split("private Response findTextResponse", 1)[0]
    require("onlyTextNodes" in tree_method and "schemaRevision" not in tree_method, "android-source-unchanged")
    invalid = copy.deepcopy(android)
    invalid["unexpected"] = True
    try:
        validate_node(invalid)
    except ContractError:
        pass
    else:
        raise ContractError("negative-closed-node")
    print("ui tree contract verifier passed")


if __name__ == "__main__":
    try:
        main()
    except ContractError as exc:
        print(f"ui tree contract verifier failed: {exc}", file=sys.stderr)
        raise SystemExit(1)
    except Exception:
        print("ui tree contract verifier failed: internal", file=sys.stderr)
        raise SystemExit(1)
