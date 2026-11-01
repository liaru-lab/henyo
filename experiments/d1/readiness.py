"""Experimental necessary-condition checks; a pass always requires model review.

Observation metadata must come from the current adapter, not UI text or a model.
The caller must independently revalidate the target before dispatching an action.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class Precheck:
    reason: str | None

    @property
    def needs_model(self):
        return self.reason is None


def precheck(body, *, expected_package):
    """Check the table-form readiness observation used by this experiment.

    None as the reason means only 'evaluate semantics', never 'ready'. Unknown
    schemas and missing evidence are rejected conservatively without inference.
    """
    if not isinstance(expected_package, str) or not expected_package:
        raise ValueError("an independently selected expected package is required")
    if not isinstance(body, dict) or not isinstance(body.get("state"), dict):
        return Precheck("invalid_state")
    state = body["state"]
    observation = state.get("observation")
    if not isinstance(observation, dict):
        return Precheck("missing_observation")
    for field in ("available", "freshAtCapture", "complete", "withinBudget"):
        if observation.get(field) is not True:
            return Precheck("observation_" + field)
    if observation.get("blockingWindow") is not False:
        return Precheck("blocked_or_unknown")
    if (observation.get("package") != expected_package
            or observation.get("expectedPackage") != expected_package):
        return Precheck("target_changed")
    columns, rows = state.get("columns"), state.get("nodes")
    required = {"node", "parent", "clickable", "enabled"}
    if (not isinstance(columns, list) or not all(isinstance(x, str) for x in columns)
            or len(set(columns)) != len(columns) or not required.issubset(columns)
            or not isinstance(rows, list) or not 0 < len(rows) <= 2048):
        return Precheck("invalid_tree")
    nodes = {}
    for row in rows:
        if not isinstance(row, list) or len(row) != len(columns):
            return Precheck("invalid_tree")
        node = dict(zip(columns, row))
        identity = node["node"]
        if (not isinstance(identity, str) or not identity or identity in nodes
                or not isinstance(node["parent"], str)):
            return Precheck("invalid_tree")
        for flag in ("clickable", "enabled"):
            if type(node[flag]) not in (int, bool) or node[flag] not in (0, 1):
                return Precheck("invalid_tree")
        nodes[identity] = node
    target = state.get("proposedNode")
    if not isinstance(target, str) or target not in nodes:
        return Precheck("missing_target")
    visited, clickable = set(), False
    while target in nodes:
        if target in visited:
            return Precheck("invalid_tree")
        visited.add(target)
        node = nodes[target]
        if not node["enabled"]:
            return Precheck("disabled_target")
        clickable |= bool(node["clickable"])
        target = node["parent"]
    if target not in ("root", ""):
        return Precheck("incomplete_ancestry")
    if not clickable:
        return Precheck("no_clickable_ancestor")
    return Precheck(None)


def guarded_request(body, *, expected_package, infer):
    """Skip inference on failed necessary conditions; never submit an action."""
    checked = precheck(body, expected_package=expected_package)
    if not checked.needs_model:
        return {"source": "precheck", "decision": "not_ready", "reason": checked.reason}
    return {"source": "model", "response": infer(body)}
