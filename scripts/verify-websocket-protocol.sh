#!/usr/bin/env bash
set -eu

repo_dir="$(cd "$(dirname "$0")/.." && pwd)"
tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

classes_dir="$tmpdir/classes"
mkdir -p "$classes_dir"

cat > "$tmpdir/TestWebSocketProtocol.java" <<'EOF'
package link.liaru.henyo;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.io.ByteArrayOutputStream;

public final class TestWebSocketProtocol {
    public static void main(String[] args) {
        check("acceptKey", "s3pPLMBiTxaQ9kYGzzhZRbK+xOo=".equals(
                WebSocketProtocol.acceptKey("dGhlIHNhbXBsZSBub25jZQ==")));

        byte[] text = WebSocketProtocol.encodeTextFrame("hello");
        WebSocketProtocol.DecodedFrame decodedText = WebSocketProtocol.decodeFrame(text, false, 64);
        check("text opcode", decodedText.opcode == WebSocketProtocol.OPCODE_TEXT);
        check("text payload", "hello".equals(decodedText.text));
        check("text masked", !decodedText.masked);

        byte[] masked = WebSocketProtocol.encodeTextFrame(
                "mask me", true, new byte[] {1, 2, 3, 4});
        WebSocketProtocol.DecodedFrame decodedMasked = WebSocketProtocol.decodeFrame(masked, true, 64);
        check("masked text opcode", decodedMasked.opcode == WebSocketProtocol.OPCODE_TEXT);
        check("masked text payload", "mask me".equals(decodedMasked.text));
        check("masked text flag", decodedMasked.masked);

        WebSocketProtocol.DecodedFrame ping = WebSocketProtocol.decodeFrame(
                WebSocketProtocol.encodePing(new byte[] {5, 6}), false, 64);
        check("ping opcode", ping.opcode == WebSocketProtocol.OPCODE_PING);
        check("ping payload", Arrays.equals(ping.payload, new byte[] {5, 6}));

        WebSocketProtocol.DecodedFrame pong = WebSocketProtocol.decodeFrame(
                WebSocketProtocol.encodePong(new byte[0]), false, 64);
        check("pong opcode", pong.opcode == WebSocketProtocol.OPCODE_PONG);
        check("pong payload", pong.payload.length == 0);

        WebSocketProtocol.DecodedFrame close = WebSocketProtocol.decodeFrame(
                WebSocketProtocol.encodeClose(1000, "bye"), false, 64);
        check("close opcode", close.opcode == WebSocketProtocol.OPCODE_CLOSE);
        check("close code", close.closeCode == 1000);
        check("close reason", "bye".equals(close.closeReason));

        expectFailure("unmasked client frame", () -> WebSocketProtocol.decodeFrame(text, true, 64));
        expectFailure("bounded payload", () -> WebSocketProtocol.decodeFrame(
                WebSocketProtocol.encodeTextFrame("hello"), false, 4));

        byte[] clientClose = WebSocketProtocol.encodeFrame(
                WebSocketProtocol.OPCODE_CLOSE,
                closePayload(1001, "payload"),
                true,
                new byte[] {9, 8, 7, 6});
        WebSocketProtocol.DecodedFrame decodedClientClose = WebSocketProtocol.decodeFrame(clientClose, true, 64);
        check("client close masked", decodedClientClose.masked);
        check("client close reason", "payload".equals(decodedClientClose.closeReason));

        System.out.println("WebSocketProtocol verifier passed");
    }

    private static void check(String label, boolean ok) {
        if (!ok) {
            throw new AssertionError(label);
        }
    }

    private static void expectFailure(String label, CheckedRunnable runnable) {
        try {
            runnable.run();
        } catch (RuntimeException expected) {
            return;
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError(label);
    }

    private static byte[] closePayload(int code, String reason) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((code >>> 8) & 0xFF);
        out.write(code & 0xFF);
        out.writeBytes(reason.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private interface CheckedRunnable {
        void run() throws Exception;
    }
}
EOF

javac --release 11 -d "$classes_dir" \
    "$repo_dir/src/main/java/link/liaru/henyo/WebSocketProtocol.java" \
    "$tmpdir/TestWebSocketProtocol.java"

java -cp "$classes_dir" link.liaru.henyo.TestWebSocketProtocol
