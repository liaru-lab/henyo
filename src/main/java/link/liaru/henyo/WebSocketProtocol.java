package link.liaru.henyo;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CharsetDecoder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;

final class WebSocketProtocol {
    static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    static final int OPCODE_TEXT = 0x1;
    static final int OPCODE_CLOSE = 0x8;
    static final int OPCODE_PING = 0x9;
    static final int OPCODE_PONG = 0xA;

    static final int MAX_CONTROL_PAYLOAD_BYTES = 125;

    private WebSocketProtocol() {
    }

    static String acceptKey(String secWebSocketKey) {
        String key = secWebSocketKey == null ? "" : secWebSocketKey.trim();
        if (key.isEmpty()) {
            throw new IllegalArgumentException("sec-websocket-key is required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest((key + WS_GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    static byte[] encodeTextFrame(String text) {
        return encodeFrame(OPCODE_TEXT, utf8(text), false, null);
    }

    static byte[] encodeTextFrame(String text, boolean masked, byte[] maskingKey) {
        return encodeFrame(OPCODE_TEXT, utf8(text), masked, maskingKey);
    }

    static byte[] encodePing(byte[] payload) {
        return encodeFrame(OPCODE_PING, copy(payload), false, null);
    }

    static byte[] encodePong(byte[] payload) {
        return encodeFrame(OPCODE_PONG, copy(payload), false, null);
    }

    static byte[] encodeClose(int code, String reason) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        if (code >= 0) {
            payload.write((code >>> 8) & 0xFF);
            payload.write(code & 0xFF);
        }
        byte[] reasonBytes = utf8(reason);
        payload.write(reasonBytes, 0, reasonBytes.length);
        return encodeFrame(OPCODE_CLOSE, payload.toByteArray(), false, null);
    }

    static byte[] encodeFrame(int opcode, byte[] payload, boolean masked, byte[] maskingKey) {
        validateOpcode(opcode);
        byte[] body = copy(payload);
        if (opcode >= OPCODE_CLOSE) {
            if (body.length > MAX_CONTROL_PAYLOAD_BYTES) {
                throw new IllegalArgumentException("control frame payload too large");
            }
        }
        if (masked) {
            if (maskingKey == null || maskingKey.length != 4) {
                throw new IllegalArgumentException("masking key must be 4 bytes");
            }
            body = applyMask(body, maskingKey);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x80 | (opcode & 0x0F));
        int length = body.length;
        int lengthPrefix = masked ? 0x80 : 0;
        if (length <= 125) {
            out.write(lengthPrefix | length);
        } else if (length <= 0xFFFF) {
            out.write(lengthPrefix | 126);
            out.write((length >>> 8) & 0xFF);
            out.write(length & 0xFF);
        } else {
            out.write(lengthPrefix | 127);
            long encodedLength = length & 0xFFFFFFFFL;
            for (int shift = 56; shift >= 0; shift -= 8) {
                out.write((int) ((encodedLength >>> shift) & 0xFF));
            }
        }
        if (masked) {
            out.write(maskingKey, 0, maskingKey.length);
        }
        out.write(body, 0, body.length);
        return out.toByteArray();
    }

    static DecodedFrame decodeFrame(byte[] frameBytes, boolean requireMasked, int maxPayloadBytes) {
        if (frameBytes == null || frameBytes.length < 2) {
            throw new WebSocketProtocolException("frame too short");
        }
        if (maxPayloadBytes < 0) {
            throw new IllegalArgumentException("maxPayloadBytes must be non-negative");
        }

        int index = 0;
        int first = frameBytes[index++] & 0xFF;
        boolean fin = (first & 0x80) != 0;
        if ((first & 0x70) != 0) {
            throw new WebSocketProtocolException("reserved bits set");
        }
        int opcode = first & 0x0F;
        validateOpcode(opcode);
        if (!fin) {
            throw new WebSocketProtocolException("fragmentation is not supported");
        }

        int second = frameBytes[index++] & 0xFF;
        boolean masked = (second & 0x80) != 0;
        if (requireMasked && !masked) {
            throw new WebSocketProtocolException("client frame must be masked");
        }

        long payloadLength = second & 0x7FL;
        if (payloadLength == 126) {
            ensureAvailable(frameBytes, index, 2);
            payloadLength = ((frameBytes[index] & 0xFFL) << 8) | (frameBytes[index + 1] & 0xFFL);
            index += 2;
        } else if (payloadLength == 127) {
            ensureAvailable(frameBytes, index, 8);
            payloadLength = 0;
            for (int i = 0; i < 8; i++) {
                payloadLength = (payloadLength << 8) | (frameBytes[index + i] & 0xFFL);
            }
            index += 8;
            if (payloadLength < 0) {
                throw new WebSocketProtocolException("negative payload length");
            }
        }

        if (payloadLength > maxPayloadBytes) {
            throw new WebSocketProtocolException("frame payload exceeds limit");
        }
        if (opcode >= OPCODE_CLOSE && payloadLength > MAX_CONTROL_PAYLOAD_BYTES) {
            throw new WebSocketProtocolException("control frame payload too large");
        }

        byte[] maskingKey = null;
        if (masked) {
            ensureAvailable(frameBytes, index, 4);
            maskingKey = Arrays.copyOfRange(frameBytes, index, index + 4);
            index += 4;
        }

        ensureAvailable(frameBytes, index, (int) payloadLength);
        if (index + payloadLength != frameBytes.length) {
            throw new WebSocketProtocolException("unexpected trailing bytes");
        }
        byte[] payload = Arrays.copyOfRange(frameBytes, index, (int) (index + payloadLength));
        if (masked) {
            payload = applyMask(payload, maskingKey);
        }

        String text = "";
        int closeCode = -1;
        String closeReason = "";
        if (opcode == OPCODE_TEXT) {
            text = utf8(payload);
        } else if (opcode == OPCODE_CLOSE) {
            if (payload.length == 1) {
                throw new WebSocketProtocolException("close payload is malformed");
            }
            if (payload.length >= 2) {
                closeCode = ((payload[0] & 0xFF) << 8) | (payload[1] & 0xFF);
                byte[] reasonBytes = Arrays.copyOfRange(payload, 2, payload.length);
                closeReason = utf8(reasonBytes);
            }
        }
        return new DecodedFrame(opcode, fin, masked, payload, text, closeCode, closeReason);
    }

    static byte[] applyMask(byte[] payload, byte[] maskingKey) {
        byte[] out = copy(payload);
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) (out[i] ^ maskingKey[i & 3]);
        }
        return out;
    }

    private static void validateOpcode(int opcode) {
        if (opcode == OPCODE_TEXT
                || opcode == OPCODE_CLOSE
                || opcode == OPCODE_PING
                || opcode == OPCODE_PONG) {
            return;
        }
        throw new WebSocketProtocolException("unsupported opcode: " + opcode);
    }

    private static void ensureAvailable(byte[] frameBytes, int offset, int length) {
        if (offset < 0 || length < 0 || offset > frameBytes.length - length) {
            throw new WebSocketProtocolException("frame truncated");
        }
    }

    private static byte[] utf8(String value) {
        return value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    private static String utf8(byte[] value) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(value)).toString();
        } catch (CharacterCodingException e) {
            throw new WebSocketProtocolException("payload is not valid UTF-8", e);
        }
    }

    private static byte[] copy(byte[] payload) {
        return payload == null ? new byte[0] : Arrays.copyOf(payload, payload.length);
    }

    static final class DecodedFrame {
        final int opcode;
        final boolean fin;
        final boolean masked;
        final byte[] payload;
        final String text;
        final int closeCode;
        final String closeReason;

        DecodedFrame(int opcode, boolean fin, boolean masked, byte[] payload, String text,
                     int closeCode, String closeReason) {
            this.opcode = opcode;
            this.fin = fin;
            this.masked = masked;
            this.payload = payload;
            this.text = text;
            this.closeCode = closeCode;
            this.closeReason = closeReason;
        }
    }

    static final class WebSocketProtocolException extends IllegalArgumentException {
        WebSocketProtocolException(String message) {
            super(message);
        }

        WebSocketProtocolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
