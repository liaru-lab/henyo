package link.liaru.henyo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

public final class HttpRequestReaderTest {
    private static final String START = "POST /v1/synthetic HTTP/1.1\r\n";

    public static void main(String[] args) throws Exception {
        MemorySocket valid = new MemorySocket(START + "Content-Length: 2\r\nHost: example.invalid\r\n\r\n{}");
        HttpRequestReader.Request parsed = HttpRequestReader.read(valid);
        check(parsed.method.equals("POST") && parsed.target.equals("/v1/synthetic")
                && parsed.body.equals("{}"), "valid request");
        check(valid.timeout == 731, "socket timeout restored");
        check(HttpRequestReader.read(new MemorySocket("")) == null, "empty connection");
        check(HttpRequestReader.read(new MemorySocket("GET / HTTP/1.0\r\n\r\n")).body.isEmpty(), "HTTP 1.0");

        reject(START + "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}", 400);
        reject(START + "Content-Length: 1, 2\r\n\r\n{}", 400);
        reject(START + "Content-Length: -1\r\n\r\n", 400);
        reject(START + "Content-Length: +1\r\n\r\n", 400);
        reject(START + "Content-Length:\r\n\r\n", 400);
        reject(START + "Content-Length: 999999999999999999999999\r\n\r\n", 413);
        reject(START + "Transfer-Encoding: chunked\r\nContent-Length: 0\r\n\r\n", 400);
        reject(START + "Transfer-Encoding: identity\r\n\r\n", 400);
        reject(START + "Content-Length: 3\r\n\r\n{}", 400);
        reject(START + "Content-Length : 0\r\n\r\n", 400);
        reject(START + "Header: value\r\n continued\r\n\r\n", 400);
        reject(START + "Header: a\u0000b\r\n\r\n", 400);
        reject(START + "Authorization: a\r\nauthorization: b\r\n\r\n", 400);
        reject("GET / HTTP/1.1\n\n", 400);
        reject("GET / HTTP/1.1\r", 400);
        reject("GET / HTTP/1.1\r\nHeader: x", 400);
        reject("GET  / HTTP/1.1\r\n\r\n", 400);
        reject("GET / HTTP/2.0\r\n\r\n", 400);
        reject("GET /\u0000 HTTP/1.1\r\n\r\n", 400);

        String body = "x".repeat(HttpRequestReader.MAX_BODY_BYTES);
        check(HttpRequestReader.read(new MemorySocket(START + "Content-Length: " + body.length()
                + "\r\n\r\n" + body)).body.length() == body.length(), "body size boundary");
        reject(START + "Content-Length: " + (body.length() + 1) + "\r\n\r\n", 413);
        String requestLine = "GET /" + "x".repeat(HttpRequestReader.MAX_LINE_BYTES - 14) + " HTTP/1.1";
        check(requestLine.length() == HttpRequestReader.MAX_LINE_BYTES, "line fixture");
        HttpRequestReader.read(new MemorySocket(requestLine + "\r\n\r\n"));
        reject(requestLine + "x\r\n\r\n", 414);
        String header = "X:" + "x".repeat(HttpRequestReader.MAX_LINE_BYTES - 2);
        HttpRequestReader.read(new MemorySocket(START + header + "\r\n\r\n"));
        reject(START + header + "x\r\n\r\n", 431);
        String many = "X: x\r\n".repeat(HttpRequestReader.MAX_HEADERS);
        HttpRequestReader.read(new MemorySocket(START + many + "\r\n"));
        reject(START + many + "X: x\r\n\r\n", 431);
        // Four full lines would exceed the aggregate limit, even though each fits.
        reject(START + (header + "\r\n").repeat(4) + "\r\n", 431);
        String full = (header + "\r\n").repeat(3);
        int remaining = HttpRequestReader.MAX_HEADER_BYTES - full.length() - 4;
        HttpRequestReader.read(new MemorySocket(START + full + "X:" + "x".repeat(remaining - 2) + "\r\n\r\n"));

        MemorySocket upgrade = new MemorySocket("GET /v1/ws/control HTTP/1.1\r\n"
                + "Connection: keep-alive\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n\u0081\u0002ok");
        HttpRequestReader.Request upgraded = HttpRequestReader.read(upgrade, 10);
        check(upgraded.headers.get("connection").equals("keep-alive, Upgrade"), "list headers combined");
        Thread.sleep(20);
        check(upgraded.input.read() == 0x81 && upgraded.input.read() == 2
                && upgraded.input.read() == 'o' && upgraded.input.read() == 'k', "upgrade read-ahead retained without HTTP deadline");
        check(upgrade.timeout == 731, "upgrade timeout restored");
        slowRead(false);
        slowRead(true);
        System.out.println("HttpRequestReaderTest passed");
    }

    private static void slowRead(boolean body) throws Exception {
        AtomicReference<Throwable> serverFailure = new AtomicReference<>();
        try (ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = new Thread(() -> {
                try (Socket socket = listener.accept()) {
                    socket.setSoTimeout(731);
                    long started = System.nanoTime();
                    try {
                        HttpRequestReader.read(socket, 250);
                        throw new AssertionError("slow input accepted");
                    } catch (HttpRequestReader.Failure expected) {
                        check(expected.status == 408, "absolute read timeout");
                        check((System.nanoTime() - started) / 1_000_000 < 1000, "dribbling cannot extend deadline");
                        check(socket.getSoTimeout() == 731, "failure restores socket timeout");
                    }
                } catch (Throwable failure) { serverFailure.set(failure); }
            });
            server.start();
            try (Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort())) {
                if (body) {
                    client.getOutputStream().write((START + "Content-Length: 20\r\n").getBytes(StandardCharsets.US_ASCII));
                    Thread.sleep(150);
                    client.getOutputStream().write("\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                for (int i = 0; i < 20 && server.isAlive(); i++) {
                    try { client.getOutputStream().write('x'); client.getOutputStream().flush(); }
                    catch (IOException closed) { break; }
                    Thread.sleep(40);
                }
            } finally { server.join(2000); }
            check(!server.isAlive(), "bounded server lifetime");
            if (serverFailure.get() != null) throw new AssertionError("loopback reader", serverFailure.get());
        }
    }

    private static void reject(String request, int status) throws Exception {
        try { HttpRequestReader.read(new MemorySocket(request)); throw new AssertionError("malformed request accepted"); }
        catch (HttpRequestReader.Failure expected) { check(expected.status == status, "bounded failure status"); }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static final class MemorySocket extends Socket {
        final InputStream input;
        int timeout = 731;
        MemorySocket(String request) { input = new ByteArrayInputStream(request.getBytes(StandardCharsets.ISO_8859_1)); }
        @Override public InputStream getInputStream() { return input; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void setSoTimeout(int value) { timeout = value; }
    }
}
