#!/usr/bin/env python3
"""Deterministic source-level gate for Android observation freshness wiring."""

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICE = (ROOT / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()
OPS = (ROOT / "src/main/java/link/liaru/henyo/WsOperation.java").read_text()


def require(source: str, needle: str) -> None:
    if needle not in source:
        raise AssertionError(f"missing freshness contract marker: {needle}")


for marker in (
    'event\\\":\\\"ui.dirty',
    'serviceEpoch = UUID.randomUUID().toString()',
    'captureBeginEventSeq',
    'captureEndEventSeq',
    'captureBeginElapsedRealtimeMs',
    'captureEndElapsedRealtimeMs',
    'ACTION_TREE_MATCHING_DIGESTS',
    'after_action_timeout',
    'sendTreeSnapshot(session, snapshot, false, true',
    'screenshot.getTimestamp()',
    'relevant_event_during_capture',
    'markSessionsUiDirty(eventSeq, now)',
    'session.lastDirtySentSeq = Math.max(session.lastDirtySentSeq, eventSeq)',
    'snapshot.captureEndEventSeq < session.latestRelevantEventSeq',
    'snapshot.captureEndEventSeq > session.lastDirtySentSeq',
    'boolean foregroundChanged = !packageName.isEmpty()',
    'MAX_DEADLINE_RETRIES',
    'boolean sent = service.sendTreeSnapshot(session, snapshot, false, true',
    'synchronized (session.writeLock)',
):
    require(SERVICE, marker)

require(OPS, 'OP_UI_OBSERVE = "ui.observe"')
if SERVICE.count('!authenticated + ",\\\"serviceEpoch\\\":\\\"" + escape(serviceEpoch)') != 2:
    raise AssertionError("both session.ready paths must expose serviceEpoch")
fallback = SERVICE.split("private void sendUnstableTimeoutConclusion", 1)[1].split("private static String actionIdFor", 1)[0]
for forbidden in ('\\\"root\\\"', '\\\"currentApp\\\"', '\\\"treeDigest\\\"', '\\\"data\\\"'):
    if forbidden in fallback:
        raise AssertionError(f"unstable timeout fallback contains stale payload field: {forbidden}")
if fallback.count('\\\"code\\\":\\\"ui_unstable\\\"') != 1:
    raise AssertionError("unstable timeout fallback must have exactly one terminal event")
if 'lastSnapshot' in SERVICE:
    raise AssertionError("post-action finalization must not reuse a retained lastSnapshot")

print("Android UI freshness source verifier passed")
