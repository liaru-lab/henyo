#!/usr/bin/env python3
"""Model-free readiness regressions, using invented UI data only."""
import copy
from readiness import guarded_request, precheck


def fixture():
    return {"state": {"observation": {
        "available": True, "freshAtCapture": True, "complete": True,
        "withinBudget": True, "blockingWindow": False,
        "package": "example.search", "expectedPackage": "example.search"},
        "columns": ["node", "parent", "clickable", "enabled", "text"],
        "nodes": [["container", "root", 0, 1, ""],
                  ["row", "container", 1, 1, ""],
                  ["label", "row", 0, 1, "Example Reader"]],
        "proposedNode": "label"}}


def main():
    body = fixture()
    original = copy.deepcopy(body)
    assert precheck(body, expected_package="example.search").needs_model
    assert body == original
    cases = []
    for field in ("available", "freshAtCapture", "complete", "withinBudget"):
        for value in (False, None, 1, "true"):
            b = fixture(); b["state"]["observation"][field] = value; cases.append(b)
    for value in (True, None, 0):
        b = fixture(); b["state"]["observation"]["blockingWindow"] = value; cases.append(b)
    for field in ("package", "expectedPackage"):
        b = fixture(); b["state"]["observation"][field] = "example.other"; cases.append(b)
    b = fixture(); b["state"]["proposedNode"] = "missing"; cases.append(b)
    b = fixture(); b["state"]["nodes"][1][3] = 0; cases.append(b)
    b = fixture(); b["state"]["nodes"][1][2] = 0; cases.append(b)
    b = fixture(); b["state"]["nodes"][1][1] = "label"; cases.append(b)
    b = fixture(); b["state"]["nodes"][1][1] = "missing"; cases.append(b)
    b = fixture(); b["state"]["nodes"].append(b["state"]["nodes"][0][:]); cases.append(b)
    b = fixture(); b["state"]["columns"].append("node"); cases.append(b)
    b = fixture(); b["state"]["nodes"][0][2] = "false"; cases.append(b)
    b = fixture(); b["state"]["nodes"][0].pop(); cases.append(b)
    cases.extend([None, {}, {"state": {}}, {"state": []}])
    for b in cases:
        def forbidden(_):
            raise AssertionError("rejected evidence reached inference")
        result = guarded_request(b, expected_package="example.search", infer=forbidden)
        assert result["source"] == "precheck" and result["decision"] == "not_ready"
    calls = []
    # Necessary conditions passing must preserve the model's uncertain result.
    result = guarded_request(body, expected_package="example.search",
                             infer=lambda value: calls.append(value) or {"choice": "uncertain"})
    assert calls == [body] and result == {"source": "model", "response": {"choice": "uncertain"}}
    # Changing only the label must still reach semantic inference, never a cached pass.
    body["state"]["nodes"][2][4] = "Example Calculator"
    assert precheck(body, expected_package="example.search").needs_model
    for package in (None, "", 0):
        try:
            precheck(body, expected_package=package)
        except ValueError:
            pass
        else:
            raise AssertionError("missing independent target accepted")
    print(f"readiness checks passed: {len(cases)} rejection cases and semantic handoff")


if __name__ == "__main__":
    main()
