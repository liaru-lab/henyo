#!/usr/bin/env python3
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SKILL = (ROOT / "skills/henyo-android-control/SKILL.md").read_text()


def require(fragment, message):
    if fragment not in SKILL:
        raise SystemExit(message)


require("Resolve the real path of this `SKILL.md`", "skill must resolve its real source path")
require("A separately installed copy of\nthis skill does not identify a checkout", "installed skill must not imply a checkout")
require("supplied by the host or user, or the current workspace", "skill must use an explicit or workspace checkout")
require("Require the selected root to contain `bin/henyo`", "skill must validate the CLI")
require("the intended one is ambiguous, ask the\nuser", "skill must stop on ambiguous checkouts")

if "two directory levels above" in SKILL:
    raise SystemExit("skill must not derive the checkout from a fixed relative depth")

print("skill checkout discovery verification passed")
