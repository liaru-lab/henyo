#!/usr/bin/env python3
"""Model-free checks for comparison boundaries and numeric agreement reporting."""
import io
import time
from unittest.mock import patch

import compare_kernels as compare

work = compare.workloads()
assert len(work) == 18
assert len({(case["task"], case["id"]) for case in work}) == 18
assert all("expected" not in case["body"] for case in work)


def expect_error(error, body):
    try:
        compare.request(1, body, .05)
    except error:
        return
    raise AssertionError("request limit was not enforced")


expect_error(ValueError, {"text": "x" * 32768})
with patch.object(compare.ev.OPENER, "open", return_value=io.BytesIO(b"x" * 65537)):
    expect_error(ValueError, {})
with patch.object(compare.ev.OPENER, "open", side_effect=lambda *a, **kw: time.sleep(.2)):
    start = time.monotonic()
    expect_error(TimeoutError, {})
    assert time.monotonic() - start < .15

# Equal labels with different numeric answers must not be called exact agreement.
# IDs may overlap between workloads; comparisons must include the task.
def row(task, probability):
    return {"task": task, "id": "repeated-id", "latencyMs": 1,
            "response": {"answers": {"match": {"noul": probability}}}}


runs = [{"variant": "baseline", "rows": [row("semantic", .7), row("control", .8)]},
        {"variant": "candidate", "rows": [row("semantic", .7), row("control", .8)]}]
assert compare.summarize(runs)["exactAnswerAgreement"]
runs[1]["rows"][0] = row("semantic", .71)
assert not compare.summarize(runs)["exactAnswerAgreement"]
assert compare.summarize(runs)["answerComparisons"] == 2
print("kernel comparison model-free checks passed")
