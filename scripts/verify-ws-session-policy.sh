#!/usr/bin/env bash
set -eu

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT
classes_dir="$tmpdir/classes"
mkdir -p "$classes_dir"

cat > "$tmpdir/TestWebSocketSessionPolicy.java" <<'EOF'
package link.liaru.henyo;

public final class TestWebSocketSessionPolicy {
    public static void main(String[] args) {
        check("timeout", WebSocketSessionPolicy.CONTROL_IDLE_TIMEOUT_MS == 60_000L);
        check("initial remaining", WebSocketSessionPolicy.remainingControlIdleMs(1_000L, 1_000L) == 60_000L);
        check("partial remaining", WebSocketSessionPolicy.remainingControlIdleMs(1_000L, 60_999L) == 1L);
        check("not idle before deadline", !WebSocketSessionPolicy.isControlIdle(1_000L, 60_999L));
        check("idle at deadline", WebSocketSessionPolicy.isControlIdle(1_000L, 61_000L));
        check("idle after deadline", WebSocketSessionPolicy.isControlIdle(1_000L, 90_000L));
        check("clock regression safe", WebSocketSessionPolicy.remainingControlIdleMs(2_000L, 1_000L) == 60_000L);
        long deadline = WebSocketSessionPolicy.controlIdleDeadlineMs(1_000L);
        check("absolute deadline", deadline == 61_000L);
        check("initial frame timeout", WebSocketSessionPolicy.readTimeoutUntilDeadlineMs(deadline, 1_000L) == 60_000);
        check("partial frame does not reset deadline", WebSocketSessionPolicy.readTimeoutUntilDeadlineMs(deadline, 41_000L) == 20_000);
        check("last partial-frame millisecond", WebSocketSessionPolicy.readTimeoutUntilDeadlineMs(deadline, 60_999L) == 1);
        check("partial-frame deadline reached", WebSocketSessionPolicy.deadlineReached(deadline, 61_000L));
        check("read timeout never zero", WebSocketSessionPolicy.readTimeoutUntilDeadlineMs(deadline, 61_000L) == 1);
        check("deadline overflow safe", WebSocketSessionPolicy.controlIdleDeadlineMs(Long.MAX_VALUE - 1L) == Long.MAX_VALUE);
        System.out.println("WebSocket session policy verifier passed");
    }

    private static void check(String label, boolean ok) {
        if (!ok) throw new AssertionError(label);
    }
}
EOF

javac --release 11 -d "$classes_dir" \
    "$repo_dir/src/main/java/link/liaru/henyo/WebSocketSessionPolicy.java" \
    "$tmpdir/TestWebSocketSessionPolicy.java"

java -cp "$classes_dir" link.liaru.henyo.TestWebSocketSessionPolicy

service="$repo_dir/src/main/java/link/liaru/henyo/HenyoAccessibilityService.java"
grep -Fq 'session.closing' "$service"
grep -Fq 'idle_timeout' "$service"
grep -Fq 'displaySummary(frameText)' "$service"
grep -Fq 'session.touchControlActivity();' "$service"

# Heartbeats and hello must remain response-only branches. The control clock is
# deliberately touched only in auth success and accepted call/batch branches.
python - "$service" <<'PY'
import sys
from pathlib import Path

source = Path(sys.argv[1]).read_text(encoding="utf-8")
for branch, next_branch in (
    ('if (frame.opcode == WebSocketProtocol.OPCODE_PING)', 'if (frame.opcode == WebSocketProtocol.OPCODE_PONG)'),
    ('if ("ping".equals(type))', '} else if ("hello".equals(type))'),
    ('} else if ("hello".equals(type))', '} else if ("auth".equals(type))'),
):
    body = source[source.index(branch):source.index(next_branch, source.index(branch))]
    if "touchControlActivity" in body:
        raise AssertionError(f"heartbeat branch extends control idle deadline: {branch}")

frame_start = source.index("private WebSocketProtocol.DecodedFrame readWsFrame")
frame_end = source.index("private static int readRequiredByte(", frame_start)
frame_reader = source[frame_start:frame_end]
if frame_reader.count("readRequiredByteBefore(socket, in, idleDeadlineMs)") < 5:
    raise AssertionError("all frame header/mask/payload reads must share the absolute idle deadline")
if "setSoTimeout(0)" in frame_reader:
    raise AssertionError("partial frames must not switch back to an unbounded socket read")
if "controlIdleDeadlineMs" not in source[source.index("while (running && !socket.isClosed())"):frame_start]:
    raise AssertionError("the websocket loop must calculate one absolute deadline for each frame")
deadline_reader = source[source.index("private static int readRequiredByteBefore"):frame_end]
if "deadlineReached(deadlineMs, now)" not in deadline_reader:
    raise AssertionError("each frame read must reject an already-expired absolute deadline")
if "readTimeoutUntilDeadlineMs(deadlineMs, now)" not in deadline_reader:
    raise AssertionError("each blocking read must use only the remaining absolute deadline")
if deadline_reader.count("deadlineReached(deadlineMs") < 2:
    raise AssertionError("a byte arriving at the deadline must be rejected after the blocking read too")
print("WebSocket session wiring verifier passed")
PY
