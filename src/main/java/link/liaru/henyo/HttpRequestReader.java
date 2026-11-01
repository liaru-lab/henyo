package link.liaru.henyo;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Bounded HTTP admission. The returned input retains WebSocket read-ahead. */
final class HttpRequestReader {
    static final int MAX_LINE_BYTES = 8192;
    static final int MAX_HEADER_BYTES = 32768;
    static final int MAX_HEADERS = 100;
    static final int MAX_BODY_BYTES = 1024 * 1024;
    static final long READ_TIMEOUT_MS = 5000L;

    static final class Failure extends IOException {
        final int status;
        final String code;
        Failure(int status, String code) { super(code); this.status = status; this.code = code; }
    }

    static final class Request {
        final String method, target, body;
        final Map<String, String> headers;
        final InputStream input;
        Request(String method, String target, Map<String, String> headers, String body, InputStream input) {
            this.method = method; this.target = target; this.body = body;
            this.headers = Collections.unmodifiableMap(headers); this.input = input;
        }
    }

    static Request read(Socket socket) throws IOException { return read(socket, READ_TIMEOUT_MS); }

    static Request read(Socket socket, long timeoutMs) throws IOException {
        if (timeoutMs < 1 || timeoutMs > READ_TIMEOUT_MS) throw new IllegalArgumentException("invalid read timeout");
        int previousTimeout = socket.getSoTimeout();
        DeadlineInput deadline = new DeadlineInput(socket, timeoutMs);
        BufferedInputStream input = new BufferedInputStream(deadline, MAX_LINE_BYTES);
        try {
            String line = line(input, deadline, 414);
            if (line == null) return null;
            String[] parts = line.split(" ", -1);
            if (parts.length != 3 || !token(parts[0]) || !parts[1].startsWith("/")
                    || !(parts[2].equals("HTTP/1.1") || parts[2].equals("HTTP/1.0")))
                throw malformed();
            for (int i = 0; i < parts[1].length(); i++)
                if (parts[1].charAt(i) <= 32 || parts[1].charAt(i) >= 127) throw malformed();
            Map<String, String> headers = new LinkedHashMap<>();
            int total = 0, count = 0;
            while (true) {
                line = line(input, deadline, 431);
                if (line == null) throw malformed();
                total += line.length() + 2;
                if (total > MAX_HEADER_BYTES) throw new Failure(431, "headers_too_large");
                if (line.isEmpty()) break;
                if (++count > MAX_HEADERS) throw new Failure(431, "headers_too_large");
                int colon = line.indexOf(':');
                if (colon < 1 || !token(line.substring(0, colon))) throw malformed();
                String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                for (int i = colon + 1; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if ((c < 32 && c != '\t') || c == 127) throw malformed();
                }
                // Ambiguous framing and repeated credentials are never combined.
                if (headers.containsKey(name) && (name.equals("content-length")
                        || name.equals("authorization") || name.equals("host"))) throw malformed();
                headers.merge(name, value, (a, b) -> a + ", " + b);
            }
            if (headers.containsKey("transfer-encoding")) throw malformed();
            int length = 0;
            String declared = headers.get("content-length");
            if (declared != null) {
                if (declared.isEmpty()) throw malformed();
                long size = 0;
                for (int i = 0; i < declared.length(); i++) {
                    char c = declared.charAt(i);
                    if (c < '0' || c > '9') throw malformed();
                    size = size * 10 + c - '0';
                    if (size > MAX_BODY_BYTES) throw new Failure(413, "body_too_large");
                }
                length = (int) size;
            }
            byte[] body = new byte[length];
            int offset = 0;
            while (offset < length) {
                deadline.check();
                int n = input.read(body, offset, length - offset);
                if (n < 0) throw malformed();
                offset += n;
            }
            deadline.check();
            return new Request(parts[0], parts[1], headers, new String(body, StandardCharsets.UTF_8), input);
        } catch (SocketTimeoutException e) {
            throw new Failure(408, "request_timeout");
        } finally {
            deadline.active = false;
            socket.setSoTimeout(previousTimeout);
        }
    }

    private static String line(InputStream input, DeadlineInput deadline, int oversizedStatus) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        while (true) {
            deadline.check();
            int b = input.read();
            if (b < 0) {
                if (bytes.size() == 0) return null;
                throw malformed();
            }
            if (b == '\r') {
                deadline.check();
                if (input.read() != '\n') throw malformed();
                return bytes.toString(StandardCharsets.ISO_8859_1.name());
            }
            if (b == '\n') throw malformed();
            if (bytes.size() >= MAX_LINE_BYTES)
                throw new Failure(oversizedStatus, oversizedStatus == 414 ? "request_line_too_large" : "headers_too_large");
            bytes.write(b);
        }
    }

    private static boolean token(String value) {
        if (value.isEmpty()) return false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) return false;
        }
        return true;
    }

    private static Failure malformed() { return new Failure(400, "malformed_request"); }

    private static final class DeadlineInput extends FilterInputStream {
        final Socket socket;
        final long endNanos;
        boolean active = true;
        DeadlineInput(Socket socket, long timeoutMs) throws IOException {
            super(socket.getInputStream()); this.socket = socket;
            endNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        }
        void check() throws SocketTimeoutException {
            if (active && System.nanoTime() - endNanos >= 0) throw new SocketTimeoutException();
        }
        private void prepareRead() throws IOException {
            if (!active) return;
            check();
            long remaining = endNanos - System.nanoTime();
            socket.setSoTimeout((int) Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining) + 1));
        }
        @Override public int read() throws IOException {
            prepareRead(); int value = in.read(); check(); return value;
        }
        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            prepareRead(); int count = in.read(bytes, offset, length); check(); return count;
        }
    }
}
