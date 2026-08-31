package link.liaru.henyo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.Region;
import android.hardware.HardwareBuffer;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.net.Uri;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.util.DisplayMetrics;
import android.util.SparseArray;

import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

public class HenyoAccessibilityService extends AccessibilityService {
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE = Pattern.compile("\\+?[0-9][0-9 .()_-]{7,}[0-9]");
    private static final int ENDPOINT_LIMITED_HEALTH = 1;
    private static final int ENDPOINT_LOCAL_ONLY_MANAGEMENT = 2;
    private static final int ENDPOINT_REMOTE_PAIRING = 3;
    private static final int ENDPOINT_AUTHENTICATED_CONTROL = 4;
    private static final int ENDPOINT_TOKEN_MANAGEMENT = 5;
    private static final int ENDPOINT_LOCAL_LEGACY = 6;
    private static final int ENDPOINT_UNKNOWN = 7;
    private static final int ENDPOINT_PAIRING_STATUS = 8;
    private static final long ACTION_TREE_INITIAL_DELAY_MS = 200L;
    private static final long ACTION_TREE_MAX_DELAY_MS = 1000L;
    private static final long ACTION_TREE_SETTLE_WINDOW_MS = 10_000L;
    private static final long ACTION_TREE_QUIET_WINDOW_MS = 400L;
    private static final int ACTION_TREE_MATCHING_DIGESTS = 2;
    private static final long MAJOR_TREE_DEBOUNCE_MS = 200L;
    private static final int UI_EVENT_NOISE = 0;
    private static final int UI_EVENT_RELEVANT = 1;
    private static final int UI_EVENT_MAJOR = 2;
    private static final int CAPTURE_MAPPING_LIMIT = 16;
    private static final long CAPTURE_MAPPING_TTL_MS = 120_000L;
    private static volatile HenyoAccessibilityService instance;

    private final Object serverLock = new Object();
    private final Object uiTreeStateLock = new Object();
    private final Object wsSessionsLock = new Object();
    private final Object uiTreeScheduleLock = new Object();
    private final Object clientSocketsLock = new Object();
    private final Object controlExecutionLock = new Object();
    private final Object captureMappingsLock = new Object();
    private final ThreadLocal<String> requestBearerToken = new ThreadLocal<>();
    private final PerformanceMetrics performanceMetrics = new PerformanceMetrics();
    private final LinkedHashMap<String, CaptureCoordinateMapping> captureMappings = new LinkedHashMap<>();
    private final ScheduledExecutorService uiTreeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "henyo-ui-tree");
        thread.setDaemon(true);
        return thread;
    });
    private final ThreadPoolExecutor clientExecutor = new ThreadPoolExecutor(
            2, 16, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(), r -> {
                Thread thread = new Thread(r, "henyo-client");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean running;
    private volatile long lastEventTime;
    private ServerSocket serverSocket;
    private Thread serverThread;
    private volatile String activeBindHost = RemoteAccessConfig.DEFAULT_BIND_HOST;
    private volatile int activeBindPort = RemoteAccessConfig.DEFAULT_PORT;
    private volatile boolean activeRemoteServing;
    private volatile int activeWsSessions;
    private long uiEventSeq;
    private volatile long lastRelevantUiEventAt;
    private volatile long lastMajorUiEventAt;
    private volatile String lastObservedPackageName = "";
    private volatile String lastObservedClassName = "";
    private volatile int preferredTargetWindowId = -1;
    private volatile int preferredTargetDisplayId = Display.DEFAULT_DISPLAY;
    private long treeVersion;
    private long actionSeq;
    private volatile String serviceEpoch = "";
    private ScheduledFuture<?> pendingMajorTreePush;
    private final List<WsSession> wsSessions = new ArrayList<>();
    private final List<Socket> clientSockets = new ArrayList<>();
    private BearerTokenManager bearerTokens;
    private ConnectionStatusOverlay connectionStatusOverlay;
    private TailscaleWatchdog tailscaleWatchdog;

    @Override
    protected void onServiceConnected() {
        running = true;
        instance = this;
        serviceEpoch = UUID.randomUUID().toString();
        synchronized (captureMappingsLock) {
            captureMappings.clear();
        }
        bearerTokens = new BearerTokenManager(this);
        connectionStatusOverlay = new ConnectionStatusOverlay(this, performanceMetrics);
        tailscaleWatchdog = new TailscaleWatchdog(this);
        tailscaleWatchdog.start();
        startHttpServer();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        long callbackStartedNanos = System.nanoTime();
        lastEventTime = System.currentTimeMillis();
        String eventPackage = str(event.getPackageName());
        if (!eventPackage.isEmpty() && !ExcludedAppStore.load(this).contains(eventPackage)
                && event.getWindowId() >= 0) {
            preferredTargetWindowId = event.getWindowId();
            if (Build.VERSION.SDK_INT >= 30) preferredTargetDisplayId = event.getDisplayId();
        }
        UiEventClassification classification = classifyUiEvent(event);
        if (classification.kind == UI_EVENT_NOISE) {
            performanceMetrics.recordAccessibility(classification.kind, SystemClock.uptimeMillis(),
                    System.nanoTime() - callbackStartedNanos);
            return;
        }
        long now = SystemClock.elapsedRealtime();
        long eventSeq;
        boolean majorChange;
        synchronized (uiTreeStateLock) {
            eventSeq = ++uiEventSeq;
            lastRelevantUiEventAt = now;
            String packageName = classification.packageName;
            String className = classification.className;
            boolean foregroundChanged = !packageName.isEmpty() && !packageName.equals(lastObservedPackageName);
            majorChange = classification.kind == UI_EVENT_MAJOR || foregroundChanged;
            if (!packageName.isEmpty()) {
                lastObservedPackageName = packageName;
            }
            if (!className.isEmpty()) {
                lastObservedClassName = className;
            }
            if (majorChange) {
                lastMajorUiEventAt = now;
            }
        }
        markSessionsUiDirty(eventSeq, now);
        scheduleDirtyUiEvent(eventSeq, now);
        if (majorChange) {
            scheduleMajorUiTreePush();
        }
        performanceMetrics.recordAccessibility(classification.kind, now,
                System.nanoTime() - callbackStartedNanos);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        running = false;
        if (instance == this) instance = null;
        if (connectionStatusOverlay != null) {
            connectionStatusOverlay.destroy();
            connectionStatusOverlay = null;
        }
        if (tailscaleWatchdog != null) {
            tailscaleWatchdog.stop();
            tailscaleWatchdog = null;
        }
        uiTreeExecutor.shutdownNow();
        closeHttpServer();
        clientExecutor.shutdownNow();
        super.onDestroy();
    }

    static void setTailscaleWatchdogEnabled(Context context, boolean enabled) {
        TailscaleWatchdog.setEnabled(context, enabled);
        HenyoAccessibilityService service = instance;
        if (service != null && service.tailscaleWatchdog != null) {
            service.tailscaleWatchdog.checkSoon();
        }
    }

    static RemoteAccessConfig applyRemoteAccessConfig(Context context, RemoteAccessConfig.Update update)
            throws RemoteAccessConfig.ValidationException, IOException {
        HenyoAccessibilityService service = instance;
        if (service == null) {
            return RemoteAccessConfig.save(context, update);
        }
        RemoteAccessConfig current = RemoteAccessConfig.load(context);
        RemoteAccessConfig config = current.with(update);
        service.restartHttpServer(config);
        RemoteAccessConfig.save(context, update);
        return config;
    }

    private void startHttpServer() {
        try {
            restartHttpServer(RemoteAccessConfig.load(this));
        } catch (IOException ignored) {
        }
    }

    private void restartHttpServer(RemoteAccessConfig config) throws IOException {
        String bindHost = config.effectiveBindHost();
        int bindPort = config.port;
        synchronized (serverLock) {
            if (serverSocket != null && !serverSocket.isClosed()
                    && bindHost.equals(activeBindHost) && bindPort == activeBindPort) {
                activeRemoteServing = remoteServing(config, activeBindHost);
                return;
            }
            ServerSocket oldSocket = serverSocket;
            String oldBindHost = activeBindHost;
            int oldBindPort = activeBindPort;
            boolean oldRemoteServing = activeRemoteServing;
            ServerSocket nextSocket = null;
            try {
                nextSocket = bindServerSocket(bindHost, bindPort);
            } catch (IOException first) {
                if (oldSocket == null || oldSocket.isClosed()) throw first;
                closeHttpServerLocked();
                try {
                    nextSocket = bindServerSocket(bindHost, bindPort);
                } catch (IOException second) {
                    restoreHttpServer(oldBindHost, oldBindPort, oldRemoteServing);
                    throw second;
                }
            }
            closeHttpServerLocked();
            startHttpServerLocked(nextSocket, config, bindHost, bindPort);
        }
    }

    private static ServerSocket bindServerSocket(String bindHost, int bindPort) throws IOException {
        return new ServerSocket(bindPort, 20, InetAddress.getByName(bindHost));
    }

    private void startHttpServerLocked(ServerSocket socket, RemoteAccessConfig config, String bindHost, int bindPort) {
        serverSocket = socket;
        activeBindHost = bindHost;
        activeBindPort = bindPort;
        activeRemoteServing = remoteServing(config, bindHost);
        Thread thread = new Thread(() -> runHttpServer(socket), "henyo-http");
        thread.setDaemon(true);
        serverThread = thread;
        thread.start();
    }

    private void restoreHttpServer(String bindHost, int bindPort, boolean remoteServing) {
        try {
            ServerSocket restoredSocket = bindServerSocket(bindHost, bindPort);
            serverSocket = restoredSocket;
            activeBindHost = bindHost;
            activeBindPort = bindPort;
            activeRemoteServing = remoteServing;
            Thread thread = new Thread(() -> runHttpServer(restoredSocket), "henyo-http");
            thread.setDaemon(true);
            serverThread = thread;
            thread.start();
        } catch (IOException ignored) {
            serverSocket = null;
            activeRemoteServing = false;
        }
    }

    private void runHttpServer(ServerSocket socket) {
        while (running && !socket.isClosed()) {
            try {
                Socket client = socket.accept();
                synchronized (clientSocketsLock) {
                    clientSockets.add(client);
                }
                try {
                    clientExecutor.execute(() -> handleClient(client));
                } catch (RejectedExecutionException rejected) {
                    forgetAndCloseClient(client);
                }
            } catch (IOException ignored) {
                break;
            }
        }
    }

    private void closeHttpServer() {
        synchronized (serverLock) {
            closeHttpServerLocked();
        }
    }

    private void closeHttpServerLocked() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
            serverSocket = null;
        }
        List<Socket> sockets;
        synchronized (clientSocketsLock) {
            sockets = new ArrayList<>(clientSockets);
            clientSockets.clear();
        }
        for (Socket socket : sockets) {
            closeQuietly(socket);
        }
        serverThread = null;
        activeRemoteServing = false;
    }

    private static boolean remoteServing(RemoteAccessConfig config, String actualBindHost) {
        try {
            return config.enabled && !InetAddress.getByName(actualBindHost).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private void handleClient(Socket socket) {
        try (socket) {
            InetAddress sourceAddress = socket.getInetAddress();
            InputStream in = socket.getInputStream();
            String line = readHttpLine(in);
            if (line == null) return;

            String[] parts = line.split(" ");
            String method = parts.length > 0 ? parts[0] : "GET";
            String target = parts.length > 1 ? parts[1] : "/";
            Map<String, String> headers = new HashMap<>();
            int contentLength = 0;
            while ((line = readHttpLine(in)) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                    if ("content-length".equals(name)) {
                        try {
                            contentLength = Integer.parseInt(value);
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
            }
            byte[] bodyBytes = new byte[Math.max(0, contentLength)];
            int read = 0;
            while (read < bodyBytes.length) {
                int n = in.read(bodyBytes, read, bodyBytes.length - read);
                if (n < 0) break;
                read += n;
            }
            String body = new String(bodyBytes, 0, read, StandardCharsets.UTF_8);

            RemoteAccessConfig config = RemoteAccessConfig.load(this);
            int sourceClass = new SourceAccessFilter(config.enabled, config.allowedCidrs).classify(sourceAddress);
            Response response = sourcePolicy(method, target, sourceClass);
            if (response == null && isWebSocketUpgrade(method, target, headers)) {
                handleWebSocket(socket, in, headers, sourceClass, sourceAddress);
                return;
            }
            if (response == null) {
                response = authPolicy(method, target, sourceClass, headers.get("authorization"), sourceAddress);
            }
            if (response == null) {
                requestBearerToken.set(bearerToken(headers.get("authorization")));
                try {
                    response = route(method, target, body);
                } finally {
                    requestBearerToken.remove();
                }
            }
            byte[] responseBody = response.body;
            OutputStream out = socket.getOutputStream();
            writeHttpResponse(out, response);
        } catch (IOException ignored) {
        } finally {
            synchronized (clientSocketsLock) {
                clientSockets.remove(socket);
            }
        }
    }

    private void forgetAndCloseClient(Socket socket) {
        synchronized (clientSocketsLock) {
            clientSockets.remove(socket);
        }
        closeQuietly(socket);
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static void writeHttpResponse(OutputStream out, Response response) throws IOException {
        byte[] responseBody = response.body;
        StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(response.status).append("\r\n")
                .append("Content-Type: ").append(response.contentType).append("\r\n")
                .append("Content-Length: ").append(responseBody.length).append("\r\n");
        for (Map.Entry<String, String> header : response.headers.entrySet()) {
            headers.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        headers.append("Connection: close\r\n\r\n");
        out.write(headers.toString().getBytes(StandardCharsets.UTF_8));
        out.write(responseBody);
        out.flush();
    }

    private boolean isWebSocketUpgrade(String method, String target, Map<String, String> headers) {
        return "GET".equals(method)
                && "/v1/ws/control".equals(pathOnly(target))
                && headerContains(headers.get("upgrade"), "websocket")
                && headerContains(headers.get("connection"), "upgrade");
    }

    private void handleWebSocket(Socket socket, InputStream in, Map<String, String> headers,
                                 int sourceClass, InetAddress sourceAddress) throws IOException {
        String key = headers.get("sec-websocket-key");
        if (key == null || key.trim().isEmpty()) {
            writeHttpResponse(socket.getOutputStream(), json(400, "{\"ok\":false,\"error\":\"bad_request\",\"code\":\"bad_request\"}"));
            return;
        }
        boolean authenticated = sourceClass == SourceAccessFilter.SOURCE_LOCALHOST;
        String sessionToken = "";
        String sessionTokenId = "";
        String authorization = headers.get("authorization");
        if (authorization != null && !authorization.trim().isEmpty()) {
            BearerAuthPolicy.ParsedAuthorization parsed = BearerAuthPolicy.parse(authorization);
            if (parsed.status == BearerAuthPolicy.AUTH_PRESENT) {
                BearerTokenManager.Verification verification = tokens().verify(parsed.token, sourceAddress.getHostAddress());
                if (verification.ok) {
                    authenticated = true;
                    sessionToken = parsed.token;
                    sessionTokenId = verification.record.id;
                } else if (sourceClass != SourceAccessFilter.SOURCE_LOCALHOST) {
                    authenticated = false;
                }
            }
        }

        OutputStream out = socket.getOutputStream();
        out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                "Upgrade: websocket\r\n" +
                "Connection: Upgrade\r\n" +
                "Sec-WebSocket-Accept: " + WebSocketProtocol.acceptKey(key) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.flush();

        WsSession session = new WsSession(out);
        session.authenticated = authenticated;
        session.sessionTokenId = sessionTokenId;
        session.sessionToken = sessionToken;
        registerWsSession(session);
        try {
            session.sendText("{\"type\":\"event\",\"event\":\"session.ready\",\"protocolVersion\":1,\"requiresAuth\":" +
                    !authenticated + ",\"serviceEpoch\":\"" + escape(serviceEpoch) + "\"}");
            if (authenticated) {
                session.sendText("{\"type\":\"event\",\"event\":\"session.authenticated\"}");
            }
            while (running && !socket.isClosed()) {
                long now = SystemClock.elapsedRealtime();
                if (WebSocketSessionPolicy.isControlIdle(session.lastControlActivityElapsedRealtimeMs, now)) {
                    closeIdleWsSession(session);
                    return;
                }
                WebSocketProtocol.DecodedFrame frame;
                try {
                    frame = readWsFrame(socket, in, WebSocketSessionPolicy.controlIdleDeadlineMs(
                            session.lastControlActivityElapsedRealtimeMs));
                } catch (SocketTimeoutException ignored) {
                    closeIdleWsSession(session);
                    return;
                }
                if (frame.opcode == WebSocketProtocol.OPCODE_CLOSE) {
                    session.sendClose(1000, "bye");
                    return;
                }
                if (frame.opcode == WebSocketProtocol.OPCODE_PING) {
                    session.sendFrame(WebSocketProtocol.encodePong(frame.payload));
                    continue;
                }
                if (frame.opcode == WebSocketProtocol.OPCODE_PONG) {
                    continue;
                }
                Map<String, String> message = parseJsonObject(frame.text);
                String type = message.getOrDefault("type", "");
                String id = message.getOrDefault("id", "");
                if ("ping".equals(type)) {
                    session.sendText("{\"type\":\"pong\",\"id\":\"" + escape(id) + "\"}");
                } else if ("hello".equals(type)) {
                    session.sendText("{\"type\":\"event\",\"event\":\"session.ready\",\"protocolVersion\":1,\"requiresAuth\":" +
                            !authenticated + ",\"serviceEpoch\":\"" + escape(serviceEpoch) + "\"}");
                } else if ("auth".equals(type)) {
                    BearerTokenManager.Verification verification = tokens().verify(message.get("token"), sourceAddress.getHostAddress());
                    if (verification.ok) {
                        authenticated = true;
                        sessionToken = message.get("token");
                        session.authenticated = true;
                        session.sessionToken = sessionToken;
                        session.sessionTokenId = verification.record.id;
                        session.touchControlActivity();
                        refreshConnectionStatusOverlay();
                        session.sendText("{\"type\":\"result\",\"id\":\"" + escape(id) + "\",\"ok\":true,\"result\":{\"authenticated\":true}}");
                        session.sendText("{\"type\":\"event\",\"event\":\"session.authenticated\",\"tokenId\":\"" + escape(verification.record.id) + "\"}");
                    } else {
                        sendWsError(session, id, verification.revoked ? "auth_revoked" : "auth_invalid", "Bearer token is invalid");
                    }
                } else if (!authenticated) {
                    sendWsError(session, id, "auth_required", "Bearer token required");
                } else {
                    if (sourceClass == SourceAccessFilter.SOURCE_ALLOWED_REMOTE) {
                        BearerTokenManager.Verification verification = tokens().verify(sessionToken, sourceAddress.getHostAddress());
                        if (!verification.ok) {
                            authenticated = false;
                            sessionToken = "";
                            session.authenticated = false;
                            session.sessionToken = "";
                            session.sessionTokenId = "";
                            refreshConnectionStatusOverlay();
                            sendWsError(session, id, verification.revoked ? "auth_revoked" : "auth_invalid", "Bearer token is invalid");
                            continue;
                        }
                    }
                    if ("batch".equals(type)) {
                        if (id.isEmpty()) {
                            sendWsError(session, id, "op_invalid", "Batch frames require id");
                            continue;
                        }
                        session.touchControlActivity();
                        try {
                            synchronized (controlExecutionLock) {
                                executeWsBatch(session, id, frame.text);
                            }
                        } finally {
                            session.touchControlActivity();
                        }
                    } else if ("call".equals(type)) {
                        if (id.isEmpty() || message.getOrDefault("op", "").isEmpty()) {
                            sendWsError(session, id, "op_invalid", "Call frames require type, id, and op");
                            continue;
                        }
                        session.touchControlActivity();
                        WsCallResult result;
                        try {
                            synchronized (controlExecutionLock) {
                                result = executeWsCall(session, id, message.get("op"), frame.text);
                            }
                        } finally {
                            session.touchControlActivity();
                        }
                        session.sendText(result.toFrameJson());
                        schedulePostActionBurst(session, result, WsOperation.specFor(message.get("op")), actionIdFor(message, id));
                    } else {
                        sendWsError(session, id, "op_invalid", "Call frames require type, id, and op");
                    }
                }
            }
        } catch (IOException | WebSocketProtocol.WebSocketProtocolException ignored) {
        } finally {
            unregisterWsSession(session);
        }
    }

    private static void closeIdleWsSession(WsSession session) throws IOException {
        session.sendText("{\"type\":\"event\",\"event\":\"session.closing\",\"reason\":\"idle_timeout\"}");
        session.sendClose(1000, "idle_timeout");
    }

    private WsCallResult executeWsCall(WsSession session, String id, String op, String frameText) {
        long started = System.currentTimeMillis();
        WsOperation.OperationSpec spec = WsOperation.specFor(op);
        if (spec == null) {
            return WsCallResult.error(id, "op_unknown", "Unknown WS operation", elapsed(started));
        }
        if (WsOperation.OP_TERMUX_EXEC.equals(spec.op)) {
            BearerTokenManager.Verification authorization = authorizedTermuxToken(session);
            if (!authorization.ok || !authorization.record.hasScope(BearerTokenManager.SCOPE_TERMUX_COMMAND)) {
                return WsCallResult.error(id, "termux_permission_required",
                        "This paired client is not allowed to run Termux commands", elapsed(started));
            }
        }
        if (SensitiveUiAccessPolicy.protectsOperation(spec.op)
                && !sensitiveUiAccessAllowed(session == null ? "" : session.sessionToken)) {
            return WsCallResult.error(id, "sensitive_ui_permission_required",
                    "This paired client is not allowed to access protected Android controls", elapsed(started));
        }
        String paramsJson = extractJsonMemberObject(frameText, "params");
        if (WsOperation.OP_TASK_PROGRESS_SET.equals(spec.op)) {
            TaskProgressParams progress;
            try {
                progress = parseTaskProgressParams(paramsJson);
            } catch (IllegalArgumentException e) {
                return WsCallResult.error(id, "op_invalid",
                        "Progress fields have invalid types", elapsed(started));
            }
            ConnectionStatusOverlay overlay = connectionStatusOverlay;
            if (progress.stepsPresent) {
                if (progress.completedPresent || progress.currentPresent) {
                    return WsCallResult.error(id, "op_invalid",
                            "Structured progress cannot include completed or current", elapsed(started));
                }
                int result = overlay == null ? TaskProgressModel.UPDATE_UNAVAILABLE
                        : overlay.setTaskProgressPlan(session, progress.goal, progress.steps,
                                progress.replan);
                if (result == TaskProgressModel.UPDATE_INVALID) {
                    return WsCallResult.error(id, "op_invalid",
                            "Structured progress requires a goal and one to six valid steps",
                            elapsed(started));
                }
                if (result == TaskProgressModel.UPDATE_PLAN_MISMATCH) {
                    return WsCallResult.error(id, "op_invalid",
                            "Progress plan differs; set replan true to replace it", elapsed(started));
                }
                if (result == TaskProgressModel.UPDATE_UNAVAILABLE) {
                    return WsCallResult.error(id, "progress_unavailable",
                            "Task progress overlay unavailable", elapsed(started));
                }
                return WsCallResult.ok(id, "{\"ok\":true,\"applied\":true}", elapsed(started));
            }
            if (progress.replanPresent) {
                return WsCallResult.error(id, "op_invalid",
                        "Replan requires structured progress steps", elapsed(started));
            }
            String goal = TaskProgressModel.sanitize(progress.goal);
            String current = TaskProgressModel.sanitize(progress.current);
            List<String> completed = progress.completed;
            boolean hasCompleted = false;
            for (String value : completed) {
                if (!TaskProgressModel.sanitize(value).isEmpty()) {
                    hasCompleted = true;
                    break;
                }
            }
            if (goal.isEmpty() && current.isEmpty() && !hasCompleted) {
                return WsCallResult.error(id, "op_invalid",
                        "Progress set requires goal, completed, or current", elapsed(started));
            }
            if (overlay == null || !overlay.setTaskProgress(session, goal, completed, current)) {
                return WsCallResult.error(id, "progress_unavailable",
                        "Task progress overlay unavailable", elapsed(started));
            }
            return WsCallResult.ok(id, "{\"ok\":true,\"applied\":true}", elapsed(started));
        }
        if (WsOperation.OP_TASK_PROGRESS_FINISH.equals(spec.op)) {
            ConnectionStatusOverlay overlay = connectionStatusOverlay;
            boolean cleared = overlay != null && overlay.clearTaskProgress(session, false);
            return WsCallResult.ok(id, "{\"ok\":true,\"applied\":true,\"cleared\":" +
                    cleared + "}", elapsed(started));
        }
        if (WsOperation.OP_TASK_COMPLETION_SHOW.equals(spec.op)) {
            String message;
            try {
                message = parseTaskCompletionMessage(paramsJson);
            } catch (IllegalArgumentException e) {
                return WsCallResult.error(id, "completion_invalid",
                        "Completion message must be a non-empty string", elapsed(started));
            }
            int validation = CompletionMessageModel.validate(message);
            if (validation == CompletionMessageModel.SHOW_TOO_LONG) {
                return WsCallResult.error(id, "completion_too_long",
                        "Completion message exceeds 250 Unicode code points", elapsed(started));
            }
            if (validation != CompletionMessageModel.SHOW_ACCEPTED) {
                return WsCallResult.error(id, "completion_invalid",
                        "Completion message must be a non-empty string", elapsed(started));
            }
            ConnectionStatusOverlay overlay = connectionStatusOverlay;
            int result = overlay == null ? CompletionMessageModel.SHOW_INVALID
                    : overlay.showTaskCompletion(message);
            if (result == CompletionMessageModel.SHOW_PROGRESS_ACTIVE) {
                return WsCallResult.error(id, "completion_progress_active",
                        "Finish task progress before showing completion", elapsed(started));
            }
            if (result != CompletionMessageModel.SHOW_ACCEPTED) {
                return WsCallResult.error(id, "completion_unavailable",
                        "Completion presentation unavailable", elapsed(started));
            }
            return WsCallResult.ok(id, "{\"ok\":true,\"applied\":true}", elapsed(started));
        }
        noteControlActivity(displaySummary(frameText));
        Map<String, String> envelope = parseJsonObject(frameText);
        if (!envelope.getOrDefault("timeoutMs", "").isEmpty()) {
            paramsJson = jsonObjectWithDefaultNumber(paramsJson, "timeout", envelope.get("timeoutMs"));
        }
        if (isUserVisibleObservation(spec.op)) {
            noteObservation(id + ":" + spec.op + ":" + started);
        }
        if (WsOperation.OP_UI_OBSERVE.equals(spec.op)) {
            return executeUiObserve(id, parseJsonObject(paramsJson), started);
        }
        if (WsOperation.OP_UI_TREE.equals(spec.op)) {
            UiTreeSnapshot snapshot = captureUiTreeSnapshot("direct", "", parseJsonObject(paramsJson));
            if (snapshot == null || !snapshot.ok()) {
                return WsCallResult.error(id, snapshot == null ? "tree_unavailable" : snapshot.errorCode,
                        "UI tree unavailable", elapsed(started));
            }
            return WsCallResult.ok(id, snapshot.toObservationTreeJson(), elapsed(started));
        }
        if (WsOperation.OP_SCREEN_SCREENSHOT.equals(spec.op)) {
            return executeWsScreenshot(id, parseJsonObject(paramsJson), started);
        }
        if (WsOperation.OP_APP_LIST.equals(spec.op)) {
            return WsCallResult.ok(id, appList(parseJsonObject(paramsJson)), elapsed(started));
        }
        if (WsOperation.OP_APP_OPEN_URI.equals(spec.op)) {
            return executeWsOpenUri(id, paramsJson, started);
        }
        if (WsOperation.OP_TERMUX_EXEC.equals(spec.op)) {
            return executeWsTermux(id, paramsJson, started);
        }
        Map<String, String> queryParams = "GET".equals(spec.httpMethod)
                ? parseJsonObject(paramsJson)
                : new HashMap<String, String>();
        Response response = routeV1(spec.httpMethod, spec.httpPath, queryParams, paramsJson);
        long duration = System.currentTimeMillis() - started;
        if (spec.binaryResult) {
            if (!response.status.startsWith("200 ")) {
                String errorBody = new String(response.body, StandardCharsets.UTF_8);
                Map<String, String> error = parseJsonObject(errorBody);
                String code = error.getOrDefault("code", error.getOrDefault("error", "op_failed"));
                return WsCallResult.error(id, code, code, duration);
            }
            String payload = "{\"ok\":true,\"contentType\":\"" + escape(response.contentType) +
                    "\",\"encoding\":\"base64\",\"byteLength\":" + response.body.length +
                    ",\"data\":\"" + Base64.getEncoder().encodeToString(response.body) + "\"}";
            return WsCallResult.ok(id, payload, duration);
        }
        String body = new String(response.body, StandardCharsets.UTF_8);
        if (!response.status.startsWith("200 ")) {
            Map<String, String> error = parseJsonObject(body);
            String code = error.getOrDefault("code", error.getOrDefault("error", "op_failed"));
            return WsCallResult.error(id, code, code, duration);
        }
        return WsCallResult.ok(id, body, duration);
    }

    private void executeWsBatch(WsSession session, String id, String frameText) throws IOException {
        long started = System.currentTimeMillis();
        Map<String, String> batch = parseJsonObject(frameText);
        boolean stopOnError = boolParam(batch, "stopOnError", true);
        long timeoutMs = longParam(batch, "timeoutMs", 0L);
        long deadline = timeoutMs > 0 ? started + timeoutMs : 0L;
        List<String> steps = extractJsonObjectArray(frameText, "steps");
        if (steps.isEmpty()) {
            sendWsError(session, id, "op_invalid", "Batch requires at least one step");
            return;
        }
        noteControlActivity(displaySummary(frameText));
        StringBuilder stepJson = new StringBuilder("[");
        boolean stopped = false;
        boolean shouldBurst = false;
        for (int i = 0; i < steps.size(); i++) {
            if (deadline > 0 && System.currentTimeMillis() >= deadline) {
                if (i > 0) stepJson.append(",");
                stepJson.append(WsCallResult.error("batch-timeout", "batch_timeout", "Batch timeout exceeded", elapsed(started)).toStepJson());
                stopped = true;
                break;
            }
            String step = steps.get(i);
            Map<String, String> stepMessage = parseJsonObject(step);
            String stepId = stepMessage.getOrDefault("id", "step-" + (i + 1));
            String op = stepMessage.getOrDefault("op", "");
            WsCallResult result = op.isEmpty()
                    ? WsCallResult.error(stepId, "op_invalid", "Step requires op", 0)
                    : executeWsCall(session, stepId, op, step);
            if (result.ok && shouldPushTree(WsOperation.specFor(op))) {
                shouldBurst = true;
            }
            if (i > 0) stepJson.append(",");
            stepJson.append(result.toStepJson());
            if (!result.ok && stopOnError) {
                stopped = true;
                break;
            }
            if (deadline > 0 && System.currentTimeMillis() >= deadline && i + 1 < steps.size()) {
                stepJson.append(",");
                stepJson.append(WsCallResult.error("batch-timeout", "batch_timeout", "Batch timeout exceeded", elapsed(started)).toStepJson());
                stopped = true;
                break;
            }
        }
        stepJson.append("]");
        session.sendText("{\"type\":\"result\",\"id\":\"" + escape(id) + "\",\"ok\":true,\"result\":{\"ok\":true,\"stoppedOnError\":" +
                stopped + ",\"steps\":" + stepJson + "},\"durationMs\":" + elapsed(started) + "}");
        if (shouldBurst) {
            schedulePostActionBurst(session, "after_action", actionIdFor(batch, id));
        }
        if (boolParam(batch, "returnTree", false)) {
            sendTreeEvent(session, "batch_return_tree", "", true, false, true);
        }
    }

    private static long elapsed(long started) {
        return Math.max(0, System.currentTimeMillis() - started);
    }

    private WsCallResult executeWsScreenshot(String id, Map<String, String> params, long started) {
        ScreenshotCapture capture = captureScreenshot(params);
        if (capture.bytes == null) {
            String code = capture.error == null ? "capture_failed" : capture.error;
            return WsCallResult.error(id, code, code, elapsed(started));
        }
        String payload = "{\"ok\":true,\"contentType\":\"image/png\",\"encoding\":\"base64\",\"byteLength\":" +
                capture.bytes.length + ",\"data\":\"" + Base64.getEncoder().encodeToString(capture.bytes) +
                "\",\"serviceEpoch\":\"" + escape(serviceEpoch) +
                "\",\"captureTimestampElapsedRealtimeMs\":" + capture.timestampElapsedRealtimeMs +
                ",\"captureBeginElapsedRealtimeMs\":" + capture.beginElapsedRealtimeMs +
                ",\"captureEndElapsedRealtimeMs\":" + capture.endElapsedRealtimeMs +
                ",\"coordinates\":" + capture.coordinateJson() + "}";
        return WsCallResult.ok(id, payload, elapsed(started));
    }

    private BearerTokenManager.Verification authorizedTermuxToken(WsSession session) {
        if (session == null || session.sessionToken == null || session.sessionToken.isEmpty()) {
            return BearerTokenManager.Verification.invalid();
        }
        BearerTokenManager.Verification verification = tokens().verify(session.sessionToken, "ws-termux");
        if (!verification.ok) {
            session.sessionTokenId = "";
            return verification;
        }
        session.sessionTokenId = verification.record.id;
        return verification;
    }

    private boolean sensitiveUiAccessAllowed(String token) {
        boolean pairedClient = token != null && !token.isEmpty();
        boolean hasSensitiveScope = pairedClient
                && tokens().hasActiveScope(token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL);
        return SensitiveUiAccessPolicy.allows(activeWindowContainsSensitiveUi(), pairedClient, hasSensitiveScope);
    }

    private boolean mayReceiveSensitiveUi(String token) {
        boolean pairedClient = token != null && !token.isEmpty();
        boolean hasSensitiveScope = pairedClient
                && tokens().hasActiveScope(token, BearerTokenManager.SCOPE_SENSITIVE_UI_CONTROL);
        return SensitiveUiAccessPolicy.allows(true, pairedClient, hasSensitiveScope);
    }

    private WsCallResult executeWsTermux(String id, String paramsJson, long started) {
        Map<String, String> params = parseJsonObject(paramsJson);
        String commandPath = params.getOrDefault("commandPath", params.getOrDefault("command", ""));
        List<String> arguments = extractJsonStringArray(paramsJson, "arguments");
        long timeoutMs = longParam(params, "timeout", 30_000L);
        TermuxCommandBridge.Request request = new TermuxCommandBridge.Request(
                commandPath,
                arguments,
                params.getOrDefault("workdir", ""),
                params.getOrDefault("stdin", ""),
                timeoutMs);
        TermuxCommandBridge.ExecutionResult result = TermuxCommandBridge.execute(this, request);
        return WsCallResult.ok(id, result.toJson(), elapsed(started));
    }

    private WsCallResult executeUiObserve(String id, Map<String, String> params, long started) {
        int maxAttempts = Math.max(1, Math.min(5, intParam(params, "maxAttempts", 3)));
        String observationId = UUID.randomUUID().toString();
        UiTreeSnapshot tree = null;
        ScreenshotCapture screenshotCapture = null;
        long beginEventSeq = 0L;
        long endEventSeq = 0L;
        long beginElapsedRealtimeMs = 0L;
        long endElapsedRealtimeMs = 0L;
        int attempt = 0;
        boolean stable = false;
        for (attempt = 1; attempt <= maxAttempts; attempt++) {
            beginElapsedRealtimeMs = SystemClock.elapsedRealtime();
            synchronized (uiTreeStateLock) {
                beginEventSeq = uiEventSeq;
            }
            tree = captureUiTreeSnapshot("observe", "", params);
            if (tree == null || !tree.ok()) {
                return WsCallResult.error(id, tree == null ? "tree_unavailable" : tree.errorCode,
                        "UI tree unavailable", elapsed(started));
            }
            screenshotCapture = captureScreenshot(params);
            endElapsedRealtimeMs = SystemClock.elapsedRealtime();
            synchronized (uiTreeStateLock) {
                endEventSeq = uiEventSeq;
            }
            if (screenshotCapture.bytes == null) {
                String code = screenshotCapture.error == null ? "capture_failed" : screenshotCapture.error;
                return WsCallResult.error(id, code, code, elapsed(started));
            }
            stable = beginEventSeq == endEventSeq
                    && tree.captureBeginEventSeq == tree.captureEndEventSeq;
            if (stable) break;
        }
        String unstableReason = stable ? "" : "relevant_event_during_capture";
        String result = "{\"ok\":true,\"observation\":{\"observationId\":\"" + escape(observationId) +
                "\",\"serviceEpoch\":\"" + escape(serviceEpoch) +
                "\",\"attempt\":" + Math.min(attempt, maxAttempts) + ",\"maxAttempts\":" + maxAttempts +
                ",\"stable\":" + stable + ",\"unstableReason\":\"" + escape(unstableReason) +
                "\",\"beginEventSeq\":" + beginEventSeq + ",\"endEventSeq\":" + endEventSeq +
                ",\"beginElapsedRealtimeMs\":" + beginElapsedRealtimeMs +
                ",\"endElapsedRealtimeMs\":" + endElapsedRealtimeMs + "},\"tree\":" + tree.toObservationTreeJson() +
                ",\"screenshot\":{\"contentType\":\"image/png\",\"encoding\":\"base64\",\"byteLength\":" +
                screenshotCapture.bytes.length + ",\"data\":\"" + Base64.getEncoder().encodeToString(screenshotCapture.bytes) +
                "\",\"captureTimestampElapsedRealtimeMs\":" + screenshotCapture.timestampElapsedRealtimeMs +
                ",\"captureBeginElapsedRealtimeMs\":" + screenshotCapture.beginElapsedRealtimeMs +
                ",\"captureEndElapsedRealtimeMs\":" + screenshotCapture.endElapsedRealtimeMs +
                ",\"coordinates\":" + screenshotCapture.coordinateJson() + "}}";
        return WsCallResult.ok(id, result, elapsed(started));
    }

    private void scheduleDirtyUiEvent(long eventSeq, long eventElapsedRealtimeMs) {
        if (!running) return;
        try {
            uiTreeExecutor.execute(() -> {
                if (!running) return;
                List<WsSession> sessions;
                synchronized (wsSessionsLock) {
                    sessions = new ArrayList<>(wsSessions);
                }
                String eventJson = "{\"type\":\"event\",\"event\":\"ui.dirty\",\"serviceEpoch\":\"" +
                        escape(serviceEpoch) + "\",\"eventSeq\":" + eventSeq +
                        ",\"eventElapsedRealtimeMs\":" + eventElapsedRealtimeMs + "}";
                for (WsSession session : sessions) {
                    if (!session.authenticated || session.closed) continue;
                    try {
                        synchronized (session.writeLock) {
                            if (eventSeq <= session.lastDirtySentSeq) continue;
                            session.sendText(eventJson);
                            session.lastDirtySentSeq = Math.max(session.lastDirtySentSeq, eventSeq);
                        }
                    } catch (IOException ignored) {
                        removeWsSession(session);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // The executor may be shutting down with the service.
        }
    }

    private void markSessionsUiDirty(long eventSeq, long eventElapsedRealtimeMs) {
        long listWaitStartedNanos = System.nanoTime();
        synchronized (wsSessionsLock) {
            performanceMetrics.recordSessionListLockWait(System.nanoTime() - listWaitStartedNanos);
            for (WsSession session : wsSessions) {
                long writeWaitStartedNanos = System.nanoTime();
                synchronized (session.writeLock) {
                    performanceMetrics.recordSessionWriteLockWait(System.nanoTime() - writeWaitStartedNanos);
                    session.latestRelevantEventSeq = Math.max(session.latestRelevantEventSeq, eventSeq);
                    session.latestRelevantEventElapsedRealtimeMs = eventElapsedRealtimeMs;
                }
            }
        }
    }

    private void sendUnstableTimeoutConclusion(WsSession session, String actionId) throws IOException {
        synchronized (session.writeLock) {
            if (session.lastDirtySentSeq < session.latestRelevantEventSeq) {
                session.sendText("{\"type\":\"event\",\"event\":\"ui.dirty\",\"serviceEpoch\":\"" +
                        escape(serviceEpoch) + "\",\"eventSeq\":" + session.latestRelevantEventSeq +
                        ",\"eventElapsedRealtimeMs\":" + session.latestRelevantEventElapsedRealtimeMs + "}");
                session.lastDirtySentSeq = session.latestRelevantEventSeq;
            }
            session.sendText("{\"type\":\"event\",\"event\":\"ui.tree\",\"serviceEpoch\":\"" +
                    escape(serviceEpoch) + "\",\"eventSeq\":" + session.latestRelevantEventSeq +
                    ",\"eventElapsedRealtimeMs\":" + session.latestRelevantEventElapsedRealtimeMs +
                    ",\"actionId\":\"" + escape(actionId) +
                    "\",\"reason\":\"after_action_timeout\",\"settled\":false,\"timedOut\":true," +
                    "\"ok\":false,\"code\":\"ui_unstable\"}");
        }
    }

    private static String actionIdFor(Map<String, String> message, String fallback) {
        if (message != null) {
            String actionId = message.get("actionId");
            if (actionId != null && !actionId.isEmpty()) {
                return actionId;
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static String displaySummary(String json) {
        String displayJson = extractJsonMemberObject(json, "display");
        return parseJsonObject(displayJson).getOrDefault("summary", "");
    }

    private void schedulePostActionBurst(WsSession session, WsCallResult result, WsOperation.OperationSpec spec, String actionId) {
        if (result == null || !result.ok || spec == null || !shouldPushTree(spec)) return;
        schedulePostActionBurst(session, "after_action", actionId);
    }

    private boolean shouldPushTree(WsOperation.OperationSpec spec) {
        return WsOperation.OP_UI_CLICK.equals(spec.op)
                || WsOperation.OP_UI_SET_TEXT.equals(spec.op)
                || WsOperation.OP_UI_TAP.equals(spec.op)
                || WsOperation.OP_UI_SWIPE.equals(spec.op)
                || WsOperation.OP_UI_SCROLL.equals(spec.op)
                || WsOperation.OP_UI_SCROLL_UNTIL.equals(spec.op)
                || WsOperation.OP_APP_LAUNCH.equals(spec.op)
                || WsOperation.OP_APP_OPEN_URI.equals(spec.op)
                || WsOperation.OP_APP_START.equals(spec.op)
                || WsOperation.OP_GLOBAL_BACK.equals(spec.op)
                || WsOperation.OP_GLOBAL_HOME.equals(spec.op);
    }

    private static boolean isUserVisibleObservation(String op) {
        return WsOperation.OP_UI_TREE.equals(op)
                || WsOperation.OP_UI_OBSERVE.equals(op)
                || WsOperation.OP_UI_FIND.equals(op)
                || WsOperation.OP_UI_WAIT.equals(op)
                || WsOperation.OP_SCREEN_SCREENSHOT.equals(op);
    }

    private void sendTreeEvent(WsSession session, String reason, String actionId, boolean settled, boolean suppressDuplicate, boolean forceSend) throws IOException {
        UiTreeSnapshot snapshot = captureUiTreeSnapshot(reason, actionId);
        if (snapshot == null) {
            return;
        }
        sendTreeSnapshot(session, snapshot, settled, false, suppressDuplicate, forceSend);
    }

    private boolean sendTreeSnapshot(WsSession session, UiTreeSnapshot snapshot, boolean settled, boolean timedOut,
                                     boolean suppressDuplicate, boolean forceSend) throws IOException {
        if (snapshot == null) {
            return false;
        }
        if (!snapshot.ok()) {
            if (session != null) {
                session.sendText(snapshot.errorJson(settled, timedOut));
            }
            return true;
        }
        if (snapshot.sensitiveUi && session != null && !mayReceiveSensitiveUi(session.sessionToken)) {
            return false;
        }
        if (session != null) {
            synchronized (session.writeLock) {
                boolean changed = !snapshot.treeDigest.equals(session.lastTreeDigest);
                if (suppressDuplicate && !changed && !forceSend) {
                    return false;
                }
                if (snapshot.captureEndEventSeq < session.latestRelevantEventSeq
                        || snapshot.captureEndEventSeq > session.lastDirtySentSeq) {
                    return false;
                }
                session.sendText(snapshot.toEventJson(settled, timedOut, changed));
                session.lastTreeDigest = snapshot.treeDigest;
            }
        }
        return true;
    }

    private void schedulePostActionBurst(WsSession session, String reason, String actionId) {
        if (session == null || !session.authenticated || session.closed || !running) {
            return;
        }
        String resolvedActionId = actionId == null || actionId.isEmpty() ? nextActionId() : actionId;
        String baselineDigest = session.lastTreeDigest;
        ActionBurst burst = new ActionBurst(this, session, resolvedActionId, reason, baselineDigest);
        uiTreeExecutor.schedule(burst, ACTION_TREE_INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static final class ActionBurst implements Runnable {
        private final HenyoAccessibilityService service;
        private final WsSession session;
        private final String actionId;
        private final String reason;
        private final String baselineDigest;
        private final long deadlineElapsedRealtimeMs;
        private boolean finished;
        private int delayIndex;
        private String lastSentDigest;
        private String previousCaptureDigest = "";
        private int matchingCaptureCount;
        private int deadlineRetryCount;
        private static final int MAX_DEADLINE_RETRIES = 5;

        ActionBurst(HenyoAccessibilityService service, WsSession session, String actionId, String reason, String baselineDigest) {
            this.service = service;
            this.session = session;
            this.actionId = actionId == null ? "" : actionId;
            this.reason = reason == null ? "after_action" : reason;
            this.baselineDigest = baselineDigest == null ? "" : baselineDigest;
            this.deadlineElapsedRealtimeMs = SystemClock.elapsedRealtime() + ACTION_TREE_SETTLE_WINDOW_MS;
            this.lastSentDigest = this.baselineDigest;
        }

        private void scheduleNext(long requestedDelayMs) {
            long now = SystemClock.elapsedRealtime();
            long remaining = deadlineElapsedRealtimeMs - now;
            if (remaining <= 0L) {
                service.uiTreeExecutor.execute(this);
                return;
            }
            long delayMs = Math.max(1L, Math.min(requestedDelayMs, remaining));
            service.uiTreeExecutor.schedule(this, delayMs, TimeUnit.MILLISECONDS);
        }

        @Override
        public void run() {
            if (!service.running || session == null || session.closed || !session.authenticated) {
                return;
            }
            if (finished) return;
            long now = SystemClock.elapsedRealtime();
            boolean finalTick = now >= deadlineElapsedRealtimeMs;
            UiTreeSnapshot snapshot = service.captureUiTreeSnapshot(finalTick ? "after_action_timeout" : reason, actionId);
            if (snapshot == null) {
                if (!finalTick) {
                    scheduleNext(nextBurstDelay());
                }
                return;
            }
            try {
                if (!snapshot.ok()) {
                    if (finalTick) {
                        finished = true;
                        service.sendTreeSnapshot(session, snapshot, false, true, false, true);
                    } else {
                        scheduleNext(nextBurstDelay());
                    }
                    return;
                }
                boolean crossedByEvent = snapshot.captureBeginEventSeq != snapshot.captureEndEventSeq;
                if (!crossedByEvent && snapshot.treeDigest.equals(previousCaptureDigest)) {
                    matchingCaptureCount++;
                } else {
                    previousCaptureDigest = snapshot.treeDigest;
                    matchingCaptureCount = 1;
                }
                long lastRelevant;
                long currentEventSeq;
                synchronized (service.uiTreeStateLock) {
                    lastRelevant = service.lastRelevantUiEventAt;
                    currentEventSeq = service.uiEventSeq;
                }
                boolean quiet = snapshot.captureEndElapsedRealtimeMs - lastRelevant >= ACTION_TREE_QUIET_WINDOW_MS;
                boolean stable = !crossedByEvent && currentEventSeq == snapshot.captureEndEventSeq
                        && quiet && matchingCaptureCount >= ACTION_TREE_MATCHING_DIGESTS;
                boolean changed = !snapshot.treeDigest.equals(lastSentDigest);
                if (finalTick) {
                    boolean sent = service.sendTreeSnapshot(session, snapshot, false, true, true, true);
                    if (sent) {
                        finished = true;
                        lastSentDigest = snapshot.treeDigest;
                    } else if (deadlineRetryCount++ < MAX_DEADLINE_RETRIES) {
                        service.uiTreeExecutor.schedule(this, 25L, TimeUnit.MILLISECONDS);
                    } else {
                        service.sendUnstableTimeoutConclusion(session, actionId);
                        finished = true;
                    }
                } else if (stable) {
                    boolean sent = service.sendTreeSnapshot(session, snapshot, true, false, true, true);
                    if (sent) {
                        finished = true;
                        lastSentDigest = snapshot.treeDigest;
                    }
                } else if (changed) {
                    if (service.sendTreeSnapshot(session, snapshot, false, false, true, false)) {
                        lastSentDigest = snapshot.treeDigest;
                    }
                }
            } catch (IOException ignored) {
                return;
            }
            if (!finalTick && !finished) {
                scheduleNext(nextBurstDelay());
            }
        }

        private long nextBurstDelay() {
            switch (delayIndex++) {
                case 0:
                    return 400L;
                case 1:
                    return 600L;
                case 2:
                    return 800L;
                default:
                    return ACTION_TREE_MAX_DELAY_MS;
            }
        }
    }

    private void scheduleMajorUiTreePush() {
        synchronized (uiTreeScheduleLock) {
            if (pendingMajorTreePush != null) {
                pendingMajorTreePush.cancel(false);
            }
            pendingMajorTreePush = uiTreeExecutor.schedule(this::pushMajorUiTreeSnapshot, MAJOR_TREE_DEBOUNCE_MS, TimeUnit.MILLISECONDS);
        }
    }

    private void pushMajorUiTreeSnapshot() {
        if (!running) {
            return;
        }
        synchronized (uiTreeScheduleLock) {
            pendingMajorTreePush = null;
        }
        List<WsSession> sessions;
        synchronized (wsSessionsLock) {
            sessions = new ArrayList<>(wsSessions);
        }
        if (sessions.isEmpty()) {
            return;
        }
        UiTreeSnapshot snapshot = captureUiTreeSnapshot("major_change", "");
        if (snapshot == null || !snapshot.ok()) {
            return;
        }
        for (WsSession session : sessions) {
            if (!session.authenticated || session.closed) {
                continue;
            }
            try {
                sendTreeSnapshot(session, snapshot, false, false, true, false);
            } catch (IOException ignored) {
                removeWsSession(session);
            }
        }
    }

    private UiTreeSnapshot captureUiTreeSnapshot(String reason, String actionId) {
        return captureUiTreeSnapshot(reason, actionId, null);
    }

    private UiTreeSnapshot captureUiTreeSnapshot(String reason, String actionId, Map<String, String> requestedParams) {
        boolean sensitiveUi = activeWindowContainsSensitiveUi();
        long captureBeginEventSeq;
        long captureBeginElapsedRealtimeMs = SystemClock.elapsedRealtime();
        synchronized (uiTreeStateLock) {
            captureBeginEventSeq = uiEventSeq;
        }
        Map<String, String> params = new HashMap<>();
        params.put("maxDepth", "12");
        params.put("maxNodes", "1200");
        params.put("redact", "false");
        if (requestedParams != null) {
            for (String key : new String[]{"maxDepth", "maxNodes", "onlyTextNodes", "redact"}) {
                String value = requestedParams.get(key);
                if (value != null && !value.isEmpty()) params.put(key, value);
            }
        }
        Response tree = routeV1("GET", "/v1/ui/tree", params, "{}");
        Response current = tree.status.startsWith("200 ") ? appCurrent() : null;
        long version;
        long captureEndEventSeq;
        long captureEndElapsedRealtimeMs = SystemClock.elapsedRealtime();
        synchronized (uiTreeStateLock) {
            version = ++treeVersion;
            captureEndEventSeq = uiEventSeq;
        }
        String capturedAt = PairingSessionManager.instant(System.currentTimeMillis());
        if (!tree.status.startsWith("200 ")) {
            return UiTreeSnapshot.error(version, captureBeginEventSeq, captureEndEventSeq,
                    captureBeginElapsedRealtimeMs, captureEndElapsedRealtimeMs, capturedAt, reason, "tree_unavailable",
                    serviceEpoch, sensitiveUi);
        }
        String currentJson = current != null && current.status.startsWith("200 ")
                ? new String(current.body, StandardCharsets.UTF_8)
                : "{}";
        String treeBody = new String(tree.body, StandardCharsets.UTF_8);
        Map<String, String> treeMeta = parseJsonObject(treeBody);
        if (boolParam(treeMeta, "truncated", false)) {
            return UiTreeSnapshot.error(version, captureBeginEventSeq, captureEndEventSeq,
                    captureBeginElapsedRealtimeMs, captureEndElapsedRealtimeMs, capturedAt, reason, "tree_too_large",
                    serviceEpoch, sensitiveUi);
        }
        String treeJson = stripJsonObjectFields(treeBody, "ok");
        String currentJsonForDigest = stripJsonObjectFields(currentJson, "ok");
        String treeJsonForDigest = treeJson;
        String treeDigest = digest(currentJsonForDigest + "|" + treeJsonForDigest);
        return UiTreeSnapshot.success(version, captureBeginEventSeq, captureEndEventSeq,
                captureBeginElapsedRealtimeMs, captureEndElapsedRealtimeMs, capturedAt, reason, actionId,
                currentJson, treeJson, treeDigest, serviceEpoch, sensitiveUi);
    }

    private synchronized String nextActionId() {
        actionSeq++;
        return "action-" + actionSeq;
    }

    private WebSocketProtocol.DecodedFrame readWsFrame(Socket socket, InputStream in, long idleDeadlineMs) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        // Every byte in the frame shares the same absolute control-idle
        // deadline. A client cannot keep a session alive by dribbling a partial
        // header or payload, and each byte read does not restart the 60s clock.
        int first = readRequiredByteBefore(socket, in, idleDeadlineMs);
        int second = readRequiredByteBefore(socket, in, idleDeadlineMs);
        frame.write(first);
        frame.write(second);
        int lengthCode = second & 0x7f;
        int extraLengthBytes = lengthCode == 126 ? 2 : (lengthCode == 127 ? 8 : 0);
        for (int i = 0; i < extraLengthBytes; i++) {
            frame.write(readRequiredByteBefore(socket, in, idleDeadlineMs));
        }
        if ((second & 0x80) != 0) {
            for (int i = 0; i < 4; i++) {
                frame.write(readRequiredByteBefore(socket, in, idleDeadlineMs));
            }
        }
        long payloadLength = lengthCode;
        byte[] bytes = frame.toByteArray();
        if (lengthCode == 126) {
            payloadLength = ((bytes[2] & 0xffL) << 8) | (bytes[3] & 0xffL);
        } else if (lengthCode == 127) {
            payloadLength = 0;
            for (int i = 2; i < 10; i++) payloadLength = (payloadLength << 8) | (bytes[i] & 0xffL);
        }
        if (payloadLength > 65536L) throw new WebSocketProtocol.WebSocketProtocolException("frame payload exceeds limit");
        for (long i = 0; i < payloadLength; i++) {
            frame.write(readRequiredByteBefore(socket, in, idleDeadlineMs));
        }
        return WebSocketProtocol.decodeFrame(frame.toByteArray(), true, 65536);
    }

    private static int readRequiredByteBefore(Socket socket, InputStream in, long deadlineMs) throws IOException {
        long now = SystemClock.elapsedRealtime();
        if (WebSocketSessionPolicy.deadlineReached(deadlineMs, now)) {
            throw new SocketTimeoutException("control idle deadline exceeded");
        }
        socket.setSoTimeout(WebSocketSessionPolicy.readTimeoutUntilDeadlineMs(deadlineMs, now));
        int value = readRequiredByte(in);
        if (WebSocketSessionPolicy.deadlineReached(deadlineMs, SystemClock.elapsedRealtime())) {
            throw new SocketTimeoutException("control idle deadline exceeded");
        }
        return value;
    }

    private static int readRequiredByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) throw new IOException("unexpected EOF");
        return value;
    }

    private static void sendWs(OutputStream out, String json) throws IOException {
        out.write(WebSocketProtocol.encodeTextFrame(json));
        out.flush();
    }

    private static void sendWsError(WsSession session, String id, String code, String message) throws IOException {
        session.sendText("{\"type\":\"error\",\"id\":\"" + escape(id == null ? "" : id) + "\",\"ok\":false,\"code\":\"" +
                escape(code) + "\",\"message\":\"" + escape(message) + "\"}");
    }

    private static boolean headerContains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }

    private static String pathOnly(String target) {
        int q = target.indexOf('?');
        return q >= 0 ? target.substring(0, q) : target;
    }

    private String readHttpLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int b;
        boolean sawAny = false;
        while ((b = in.read()) != -1) {
            sawAny = true;
            if (b == '\n') break;
            if (b != '\r') out.write(b);
        }
        if (!sawAny && out.size() == 0) return null;
        return out.toString(StandardCharsets.UTF_8.name());
    }

    private Response route(String method, String target, String body) {
        String path = target;
        String query = "";
        int q = target.indexOf('?');
        if (q >= 0) {
            path = target.substring(0, q);
            query = target.substring(q + 1);
        }
        Map<String, String> params = parseQuery(query);

        if (path.startsWith("/v1/")) return routeV1(method, path, params, body);

        if ("/health".equals(path)) return health();
        if ("/ui/tree".equals(path)) return uiTree(params);
        if ("/ui/find-text".equals(path)) return findTextResponse(params);
        if ("/ui/click-text".equals(path)) return serializedControl(() -> clickText(params));
        if ("/ui/wait-text".equals(path)) return waitText(params, false);
        if ("/ui/wait-gone-text".equals(path)) return waitText(params, true);
        if ("/ui/set-text".equals(path)) return serializedControl(() -> setText(params));
        if ("/ui/tap".equals(path)) return serializedControl(() -> tapResponse(params));
        if ("/ui/swipe".equals(path)) return serializedControl(() -> swipeResponse(params));
        if ("/ui/scroll".equals(path)) return serializedControl(() -> scroll(params));
        if ("/ui/scroll-until-text".equals(path)) return serializedControl(() -> scrollUntilText(params));
        if ("/app/launch".equals(path)) return appLaunch(params);
        if ("/app/start".equals(path)) return appStart(params);
        if ("/app/current".equals(path)) return appCurrent();
        if ("/global/back".equals(path)) return serializedControl(() -> {
            showBackGesture();
            return json(200, "{\"ok\":" + performGlobalAction(GLOBAL_ACTION_BACK) + "}");
        });
        if ("/global/home".equals(path)) return json(200, "{\"ok\":" + performGlobalAction(GLOBAL_ACTION_HOME) + "}");
        return json(404, "{\"ok\":false,\"error\":\"not_found\"}");
    }

    private Response routeV1(String method, String path, Map<String, String> queryParams, String body) {
        if ("GET".equals(method) && "/v1/remote/access".equals(path)) return remoteAccessStatus();
        if ("PUT".equals(method) && "/v1/remote/access".equals(path)) return remoteAccessUpdate(body);
        if ("GET".equals(method) && "/v1/remote/pairing".equals(path)) return pairingStatus();
        if ("POST".equals(method) && "/v1/remote/pairing".equals(path)) return pairingStart(body);
        if ("DELETE".equals(method) && "/v1/remote/pairing".equals(path)) return pairingCancel();
        if ("POST".equals(method) && "/v1/auth/tokens/local".equals(path)) return localTokenCreate(body);
        if ("POST".equals(method) && "/v1/auth/tokens/import-local".equals(path)) return localTokenImport(body);

        if (SensitiveUiAccessPolicy.protectsHttpPath(path)
                && !sensitiveUiAccessAllowed(requestBearerToken.get())) {
            return json(403, "{\"ok\":false,\"error\":\"sensitive_ui_permission_required\",\"code\":\"sensitive_ui_permission_required\"}");
        }

        Map<String, String> params = parseJsonObject(body);
        params.putAll(queryParams);
        if ("GET".equals(method) && "/v1/health".equals(path)) return health();
        if ("GET".equals(method) && "/v1/debug/performance".equals(path)) {
            return json(200, "{\"ok\":true,\"performance\":" + performanceMetrics.toJson() + "}");
        }
        if ("POST".equals(method) && "/v1/debug/performance/reset".equals(path)) {
            performanceMetrics.reset();
            return json(200, "{\"ok\":true,\"performance\":" + performanceMetrics.toJson() + "}");
        }
        if ("POST".equals(method) && "/v1/remote/pairing/register".equals(path)) return pairingRegister(params);
        if ("GET".equals(method) && "/v1/auth/tokens".equals(path)) return tokenList();
        if ("DELETE".equals(method) && path.startsWith("/v1/auth/tokens/")) return tokenRevoke(path.substring("/v1/auth/tokens/".length()));
        if ("GET".equals(method) && "/v1/app/current".equals(path)) return appCurrent();
        if ("GET".equals(method) && "/v1/ui/tree".equals(path)) return uiTree(params);
        if ("GET".equals(method) && "/v1/screen/screenshot".equals(path)) return screenshot(params);
        if ("POST".equals(method) && "/v1/ui/find".equals(path)) return findTextResponse(params);
        if ("POST".equals(method) && "/v1/ui/click".equals(path)) return serializedControl(() -> clickV1(params));
        if ("POST".equals(method) && "/v1/ui/set-text".equals(path)) return serializedControl(() -> setText(params));
        if ("POST".equals(method) && "/v1/ui/tap".equals(path)) return serializedControl(() -> tapResponse(params));
        if ("POST".equals(method) && "/v1/ui/swipe".equals(path)) return serializedControl(() -> swipeResponse(params));
        if ("POST".equals(method) && "/v1/ui/scroll".equals(path)) return serializedControl(() -> scroll(params));
        if ("POST".equals(method) && "/v1/ui/scroll-until".equals(path)) return serializedControl(() -> scrollUntilText(params));
        if ("POST".equals(method) && "/v1/ui/wait".equals(path)) return waitText(params, boolParam(params, "gone", false));
        if ("POST".equals(method) && "/v1/app/launch".equals(path)) return appLaunch(params);
        if ("POST".equals(method) && "/v1/app/start".equals(path)) return appStart(params);
        if ("POST".equals(method) && "/v1/global/back".equals(path)) return serializedControl(() -> {
            showBackGesture();
            return json(200, "{\"ok\":" + performGlobalAction(GLOBAL_ACTION_BACK) + "}");
        });
        if ("POST".equals(method) && "/v1/global/home".equals(path)) return json(200, "{\"ok\":" + performGlobalAction(GLOBAL_ACTION_HOME) + "}");
        return json(404, "{\"ok\":false,\"error\":\"not_found\"}");
    }

    private interface ControlResponse {
        Response run();
    }

    private Response serializedControl(ControlResponse operation) {
        boolean outerControlCall = !Thread.holdsLock(controlExecutionLock);
        synchronized (controlExecutionLock) {
            if (outerControlCall) noteControlActivity("");
            return operation.run();
        }
    }

    private Response sourcePolicy(String method, String target, int sourceClass) {
        int endpointClass = endpointClass(method, target);
        if (sourceClass == SourceAccessFilter.SOURCE_DENIED) {
            return sourceDenied();
        }
        if (sourceClass == SourceAccessFilter.SOURCE_LOCALHOST) {
            return endpointClass == ENDPOINT_REMOTE_PAIRING ? sourceDenied() : null;
        }
        switch (endpointClass) {
            case ENDPOINT_LIMITED_HEALTH:
            case ENDPOINT_PAIRING_STATUS:
            case ENDPOINT_REMOTE_PAIRING:
            case ENDPOINT_AUTHENTICATED_CONTROL:
            case ENDPOINT_TOKEN_MANAGEMENT:
                return null;
            case ENDPOINT_LOCAL_ONLY_MANAGEMENT:
            case ENDPOINT_LOCAL_LEGACY:
            case ENDPOINT_UNKNOWN:
            default:
                return sourceDenied();
        }
    }

    private static int endpointClass(String method, String target) {
        String path = pathOnly(target);
        if ("GET".equals(method) && "/v1/health".equals(path)) return ENDPOINT_LIMITED_HEALTH;
        if ("GET".equals(method) && "/v1/remote/pairing".equals(path)) return ENDPOINT_PAIRING_STATUS;
        if (path.equals("/v1/remote/access") || path.equals("/v1/remote/pairing")) return ENDPOINT_LOCAL_ONLY_MANAGEMENT;
        if ("POST".equals(method) && "/v1/remote/pairing/register".equals(path)) return ENDPOINT_REMOTE_PAIRING;
        if ("POST".equals(method) && "/v1/auth/tokens/local".equals(path)) return ENDPOINT_LOCAL_ONLY_MANAGEMENT;
        if ("POST".equals(method) && "/v1/auth/tokens/import-local".equals(path)) return ENDPOINT_LOCAL_ONLY_MANAGEMENT;
        if (path.equals("/v1/auth/tokens") || path.startsWith("/v1/auth/tokens/")) return ENDPOINT_TOKEN_MANAGEMENT;
        if (path.equals("/v1/ws/control")) return ENDPOINT_AUTHENTICATED_CONTROL;
        if (path.startsWith("/v1/app/") || path.startsWith("/v1/ui/")
                || path.startsWith("/v1/screen/") || path.startsWith("/v1/global/")) {
            return ENDPOINT_AUTHENTICATED_CONTROL;
        }
        if (path.startsWith("/v1/")) return ENDPOINT_UNKNOWN;
        return ENDPOINT_LOCAL_LEGACY;
    }

    private static Response sourceDenied() {
        return json(403, "{\"ok\":false,\"error\":\"source_not_allowed\",\"code\":\"source_not_allowed\",\"auth\":{\"sourceAllowed\":false}}");
    }

    private Response authPolicy(String method, String target, int sourceClass, String authorization, InetAddress sourceAddress) {
        if (sourceClass != SourceAccessFilter.SOURCE_ALLOWED_REMOTE) return null;
        if (!BearerAuthPolicy.requiresRemoteBearer(method, target)) return null;
        BearerAuthPolicy.ParsedAuthorization parsed = BearerAuthPolicy.parse(authorization);
        if (parsed.status == BearerAuthPolicy.AUTH_MISSING) return authError("auth_required");
        if (parsed.status == BearerAuthPolicy.AUTH_MALFORMED) return authError("auth_malformed");
        BearerTokenManager.Verification verification = tokens().verify(parsed.token, sourceAddress.getHostAddress());
        if (verification.ok) return null;
        return authError(verification.revoked ? "auth_revoked" : "auth_invalid");
    }

    private static Response authError(String code) {
        return json(401, "{\"ok\":false,\"error\":\"" + code + "\",\"code\":\"" + code +
                "\",\"auth\":{\"required\":true,\"scheme\":\"Bearer\",\"sourceAllowed\":true}}");
    }

    private static String bearerToken(String authorization) {
        BearerAuthPolicy.ParsedAuthorization parsed = BearerAuthPolicy.parse(authorization);
        return parsed.status == BearerAuthPolicy.AUTH_PRESENT ? parsed.token : "";
    }

    private Response health() {
        RemoteAccessConfig config = RemoteAccessConfig.load(this);
        String watchdog = tailscaleWatchdog == null
                ? TailscaleWatchdog.disabledStatusJson(this)
                : tailscaleWatchdog.statusJson();
        return json(200, "{\"ok\":true,\"service\":\"connected\",\"lastEventTime\":" + lastEventTime +
                ",\"remoteAccess\":" + withTokenCount(config.summaryJson(activeBindHost, activeRemoteServing)) +
                ",\"webSocket\":{\"endpoint\":\"/v1/ws/control\",\"activeSessions\":" + activeWsSessions + "}" +
                ",\"tailscaleWatchdog\":" + watchdog + "}");
    }

    private Response remoteAccessStatus() {
        RemoteAccessConfig config = RemoteAccessConfig.load(this);
        return json(200, "{\"ok\":true,\"remoteAccess\":" + withTokenCount(config.statusJson(activeBindHost, activeRemoteServing)) +
                ",\"endpointClasses\":" + endpointClassesJson() + "}");
    }

    private Response remoteAccessUpdate(String body) {
        RemoteAccessConfig.Update update;
        try {
            update = parseRemoteAccessUpdate(body);
        } catch (IllegalArgumentException e) {
            return json(400, "{\"ok\":false,\"error\":\"invalid_remote_access_update\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
        try {
            RemoteAccessConfig current = RemoteAccessConfig.load(this);
            RemoteAccessConfig config = current.with(update);
            restartHttpServer(config);
            RemoteAccessConfig.save(this, update);
            return json(200, "{\"ok\":true,\"remoteAccess\":" + withTokenCount(config.statusJson(activeBindHost, activeRemoteServing)) +
                    ",\"endpointClasses\":" + endpointClassesJson() + "}");
        } catch (RemoteAccessConfig.ValidationException e) {
            return json(400, "{\"ok\":false,\"error\":\"invalid_remote_access_config\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        } catch (IOException e) {
            return json(400, "{\"ok\":false,\"error\":\"bind_failed\",\"message\":\"" + escape(e.getMessage()) + "\"}");
        }
    }

    private String withTokenCount(String json) {
        if (json.endsWith("}")) {
            return json.substring(0, json.length() - 1) + ",\"tokenCount\":" + tokens().count() + "}";
        }
        return json;
    }

    private Response localTokenCreate(String body) {
        Map<String, String> params = parseJsonObject(body);
        BearerTokenManager.CreatedToken created = tokens().create(params.getOrDefault("name", "local-management"), null);
        return json(200, "{\"ok\":true,\"token\":\"" + escape(created.token) + "\",\"tokenType\":\"Bearer\",\"tokenMetadata\":" +
                created.record.metadataJson() + "}");
    }

    private Response localTokenImport(String body) {
        Map<String, String> params = parseJsonObject(body);
        String rawToken = params.getOrDefault("token", "");
        if (rawToken.isEmpty()) {
            return json(400, "{\"ok\":false,\"error\":\"token_required\",\"code\":\"token_required\"}");
        }
        try {
            BearerTokenManager.TokenRecord record = tokens().importToken(
                    rawToken,
                    params.getOrDefault("name", "migrated-local-client"),
                    requestedScopes(params.get("requestedScopes"), true));
            return json(200, "{\"ok\":true,\"tokenMetadata\":" + record.metadataJson() + "}");
        } catch (IllegalArgumentException e) {
            return json(400, "{\"ok\":false,\"error\":\"invalid_token\",\"code\":\"invalid_token\"}");
        }
    }

    private Response pairingStatus() {
        return json(200, pairingStatusJson(PairingSessionManager.get().status()));
    }

    private Response pairingStart(String body) {
        Map<String, String> params = parseJsonObject(body);
        Integer ttl = null;
        if (params.containsKey("ttlSeconds")) {
            try {
                ttl = Integer.parseInt(params.get("ttlSeconds"));
            } catch (NumberFormatException ignored) {
            }
        }
        PairingSessionManager.StartResult result = PairingSessionManager.get().start(ttl, params.get("clientHint"));
        if (!result.ok) return pairingError(409, result.error);
        return json(200, pairingStatusJson(result.status));
    }

    private Response pairingCancel() {
        return json(200, pairingStatusJson(PairingSessionManager.get().cancel()));
    }

    private Response pairingRegister(Map<String, String> params) {
        String pairingId = params.getOrDefault("pairingId", "");
        String pin = params.getOrDefault("pin", "");
        String clientName = params.getOrDefault("clientName", "").trim();
        if (pairingId.isEmpty() || pin.isEmpty() || clientName.isEmpty()) {
            return pairingError(400, "bad_request");
        }
        PairingSessionManager.RegisterResult result = PairingSessionManager.get().register(pairingId, pin);
        if (!result.ok) {
            int status = pairingErrorStatus(result.error);
            return pairingError(status, result.error);
        }
        BearerTokenManager.CreatedToken created = tokens().create(clientName, requestedScopes(params.get("requestedScopes"), false));
        return json(200, "{\"ok\":true,\"token\":\"" + escape(created.token) + "\",\"tokenType\":\"Bearer\",\"tokenMetadata\":" +
                created.record.metadataJson() + "}");
    }

    private static String pairingStatusJson(PairingSessionManager.Status status) {
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"active\":").append(status.active);
        if (!status.pairingId.isEmpty()) {
            sb.append(",\"pairingId\":\"").append(escape(status.pairingId)).append("\"");
            sb.append(",\"createdAt\":\"").append(escape(PairingSessionManager.instant(status.createdAt))).append("\"");
            sb.append(",\"expiresAt\":\"").append(escape(PairingSessionManager.instant(status.expiresAt))).append("\"");
            sb.append(",\"pinDigits\":").append(PairingSessionManager.PIN_DIGITS);
            sb.append(",\"attemptsRemaining\":").append(status.attemptsRemaining);
        }
        sb.append(",\"state\":\"").append(escape(status.state)).append("\"}");
        return sb.toString();
    }

    private static Response pairingError(int status, String code) {
        return json(status, "{\"ok\":false,\"error\":\"" + code + "\",\"code\":\"" + code +
                "\",\"auth\":{\"required\":true,\"scheme\":\"pairing-pin\",\"sourceAllowed\":true}}");
    }

    private static int pairingErrorStatus(String code) {
        if ("pairing_pin_invalid".equals(code)) return 400;
        if ("pairing_rate_limited".equals(code)) return 429;
        return 409;
    }

    private static List<String> requestedScopes(String rawScopes, boolean includeTokenManagementByDefault) {
        List<String> scopes = new ArrayList<>();
        String value = rawScopes == null ? "" : rawScopes;
        if (value.contains("control")) scopes.add("control");
        if (value.contains("token-management")) scopes.add("token-management");
        if (value.contains("termux-command")) scopes.add("termux-command");
        if (scopes.isEmpty()) {
            scopes.add("control");
            if (includeTokenManagementByDefault) scopes.add("token-management");
        }
        return scopes;
    }

    private Response tokenList() {
        List<BearerTokenManager.TokenRecord> records = tokens().list();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"tokens\":[");
        for (int i = 0; i < records.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(records.get(i).metadataJson());
        }
        sb.append("]}");
        return json(200, sb.toString());
    }

    private Response tokenRevoke(String tokenId) {
        if (tokenId == null || tokenId.isEmpty()) return json(404, "{\"ok\":false,\"error\":\"not_found\",\"code\":\"not_found\"}");
        String decodedTokenId = decode(tokenId);
        if (!tokens().revoke(decodedTokenId)) return json(404, "{\"ok\":false,\"error\":\"not_found\",\"code\":\"not_found\"}");
        return json(200, "{\"ok\":true}");
    }

    private BearerTokenManager tokens() {
        if (bearerTokens == null) {
            bearerTokens = new BearerTokenManager(this);
        }
        return bearerTokens;
    }

    private static String endpointClassesJson() {
        return "[" +
                endpointClassJson("local-only-management", "allowed", "denied", "denied") + "," +
                endpointClassJson("pairing-status", "allowed", "allowed without Bearer auth", "denied") + "," +
                endpointClassJson("remote-pairing", "denied", "allowed during active pairing with PIN", "denied") + "," +
                endpointClassJson("authenticated-control", "allowed for compatibility", "requires Bearer auth", "denied") + "," +
                endpointClassJson("limited-health", "allowed", "allowed without Bearer auth", "denied") + "," +
                endpointClassJson("token-management", "allowed", "requires Bearer auth", "denied") +
                "]";
    }

    private static String endpointClassJson(String className, String localhost, String allowedRemoteCidrs, String otherAddresses) {
        return "{" +
                "\"className\":\"" + escape(className) + "\"," +
                "\"localhost\":\"" + escape(localhost) + "\"," +
                "\"allowedRemoteCidrs\":\"" + escape(allowedRemoteCidrs) + "\"," +
                "\"otherAddresses\":\"" + escape(otherAddresses) + "\"" +
                "}";
    }

    private AccessibilityNodeInfo resolvedTargetRoot() {
        ResolvedTarget target = resolveTarget();
        return target == null ? null : target.root;
    }

    private ResolvedTarget resolveTarget() {
        List<AccessibilityWindowInfo> windows = allWindows();
        if (windows.isEmpty()) return null;
        List<WindowTargetResolver.Candidate> candidates = new ArrayList<>();
        for (AccessibilityWindowInfo window : windows) {
            AccessibilityNodeInfo root = window == null ? null : window.getRoot();
            String packageName = root == null ? "" : str(root.getPackageName());
            candidates.add(new WindowTargetResolver.Candidate(
                    window == null ? -1 : window.getId(),
                    window == null || Build.VERSION.SDK_INT < 30
                            ? Display.DEFAULT_DISPLAY : window.getDisplayId(),
                    window == null ? Integer.MIN_VALUE : window.getLayer(),
                    packageName,
                    window != null && window.getType() == AccessibilityWindowInfo.TYPE_APPLICATION,
                    root != null,
                    window != null && window.isActive(),
                    window != null && window.isFocused()));
            if (root != null) root.recycle();
        }
        int selected = WindowTargetResolver.select(candidates, ExcludedAppStore.load(this),
                preferredTargetWindowId, preferredTargetDisplayId);
        if (selected < 0) {
            recycleWindows(windows);
            return null;
        }
        AccessibilityWindowInfo window = windows.get(selected);
        AccessibilityNodeInfo root = window.getRoot();
        if (root == null) {
            recycleWindows(windows);
            return null;
        }
        int windowId = window.getId();
        int displayId = Build.VERSION.SDK_INT >= 30 ? window.getDisplayId() : Display.DEFAULT_DISPLAY;
        preferredTargetWindowId = windowId;
        preferredTargetDisplayId = displayId;
        recycleWindows(windows);
        return new ResolvedTarget(root, windowId, displayId);
    }

    private List<AccessibilityWindowInfo> allWindows() {
        List<AccessibilityWindowInfo> out = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            SparseArray<List<AccessibilityWindowInfo>> displays = getWindowsOnAllDisplays();
            if (displays != null) {
                for (int i = 0; i < displays.size(); i++) {
                    List<AccessibilityWindowInfo> displayWindows = displays.valueAt(i);
                    if (displayWindows != null) out.addAll(displayWindows);
                }
            }
        } else {
            List<AccessibilityWindowInfo> windows = getWindows();
            if (windows != null) out.addAll(windows);
        }
        return out;
    }

    private static void recycleWindows(List<AccessibilityWindowInfo> windows) {
        for (AccessibilityWindowInfo window : windows) {
            if (window != null) window.recycle();
        }
    }

    private GestureDescription.Builder gestureBuilderForTarget() {
        GestureDescription.Builder builder = new GestureDescription.Builder();
        if (Build.VERSION.SDK_INT >= 30) {
            ResolvedTarget target = resolveTarget();
            if (target != null) {
                builder.setDisplayId(target.displayId);
                target.root.recycle();
            }
        }
        return builder;
    }

    private Response uiTree(Map<String, String> params) {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return json(503, "{\"ok\":false,\"error\":\"no_root\"}");
        try {
            boolean redact = boolParam(params, "redact", false);
            boolean onlyTextNodes = boolParam(params, "onlyTextNodes", false);
            int maxDepth = intParam(params, "maxDepth", 8);
            int maxNodes = intParam(params, "maxNodes", 500);
            Counter counter = new Counter(maxNodes);
            StringBuilder sb = new StringBuilder();
            if (onlyTextNodes) {
                sb.append("{\"ok\":true,\"nodes\":[");
                appendFlatNodes(sb, root, 0, 0, maxDepth, redact, counter);
                sb.append("],\"truncated\":").append(counter.truncated).append("}");
            } else {
                sb.append("{\"ok\":true,\"root\":");
                appendNode(sb, root, 0, 0, maxDepth, redact, counter);
                sb.append(",\"truncated\":").append(counter.truncated).append("}");
            }
            return json(200, sb.toString());
        } finally {
            root.recycle();
        }
    }

    private boolean activeWindowContainsSensitiveUi() {
        if (Build.VERSION.SDK_INT < 34) return false;
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return false;
        try {
            return nodeTreeContainsSensitiveUi(root, 0, new int[]{2000});
        } finally {
            root.recycle();
        }
    }

    private boolean nodeTreeContainsSensitiveUi(AccessibilityNodeInfo node, int depth, int[] remaining) {
        if (node == null || remaining[0]-- <= 0 || depth > 50) return false;
        if (node.isAccessibilityDataSensitive()) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (nodeTreeContainsSensitiveUi(child, depth + 1, remaining)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private Response findTextResponse(Map<String, String> params) {
        SearchOptions options = SearchOptions.from(params);
        Match match = findMatch(options);
        if (match == null) return json(404, "{\"ok\":false,\"error\":\"not_found\"}");
        String body = "{\"ok\":true,\"node\":" + match.toJson(boolParam(params, "redact", false)) + "}";
        match.recycle();
        return json(200, body);
    }

    private Response clickText(Map<String, String> params) {
        SearchOptions options = SearchOptions.from(params);
        Match match = findMatch(options);
        if (match == null) return json(404, "{\"ok\":false,\"error\":\"not_found\"}");
        ClickResult result = clickMatch(match);
        match.recycle();
        return json(200, "{\"ok\":" + result.ok + ",\"strategy\":\"" + result.strategy + "\"}");
    }

    private Response clickV1(Map<String, String> params) {
        Point point = pointParam(params);
        if (point != null) {
            return json(200, "{\"ok\":" + tap(point.x, point.y) + ",\"strategy\":\"coordinate\"}");
        }
        return clickText(params);
    }

    private Response waitText(Map<String, String> params, boolean waitGone) {
        SearchOptions options = SearchOptions.from(params);
        int timeout = intParam(params, "timeout", 5000);
        int interval = Math.max(20, intParam(params, "interval", 100));
        long end = System.currentTimeMillis() + timeout;
        Match match = null;
        while (System.currentTimeMillis() < end) {
            match = findMatch(options);
            if (waitGone) {
                if (match == null) return json(200, "{\"ok\":true,\"gone\":true}");
                match.recycle();
                match = null;
            } else if (match != null) {
                String body = "{\"ok\":true,\"node\":" + match.toJson(boolParam(params, "redact", false)) + "}";
                match.recycle();
                return json(200, body);
            }
            sleep(interval);
        }
        if (match != null) match.recycle();
        return json(404, "{\"ok\":false,\"error\":\"timeout\",\"texts\":" + visibleTextsJson(12, true) + "}");
    }

    private Response setText(Map<String, String> params) {
        String value = params.getOrDefault("value", "");
        boolean clear = boolParam(params, "clear", true);
        SearchOptions options = SearchOptions.from(params);
        if (options.needle.isEmpty()) {
            options = SearchOptions.from(aliasParam(params, "target", params.getOrDefault("target", "")));
        }
        Match match = findEditableMatch(options);
        if (match == null) match = findFocusedEditable();
        if (match == null) return json(404, "{\"ok\":false,\"error\":\"not_found\"}");
        showPointGesture(match.centerX(), match.centerY());
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, clear ? value : match.text + value);
        boolean ok = match.node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        match.recycle();
        return json(200, "{\"ok\":" + ok + "}");
    }

    private Response tapResponse(Map<String, String> params) {
        int x = intParam(params, "x", -1);
        int y = intParam(params, "y", -1);
        if (x < 0 || y < 0) return json(400, "{\"ok\":false,\"error\":\"missing_x_y\"}");
        CoordinateResolution point = resolveControlPoint(params, x, y);
        if (!point.ok) return coordinateError(point);
        boolean ok = tap(point.x, point.y);
        return json(200, "{\"ok\":" + ok + point.resultMetadata() + "}");
    }

    private Response swipeResponse(Map<String, String> params) {
        int x1 = intParam(params, "x1", -1);
        int y1 = intParam(params, "y1", -1);
        int x2 = intParam(params, "x2", -1);
        int y2 = intParam(params, "y2", -1);
        int duration = intParam(params, "duration", 300);
        if (x1 < 0 || y1 < 0 || x2 < 0 || y2 < 0) return json(400, "{\"ok\":false,\"error\":\"missing_coordinates\"}");
        CoordinateResolution start = resolveControlPoint(params, x1, y1);
        if (!start.ok) return coordinateError(start);
        CoordinateResolution end = resolveControlPoint(params, x2, y2);
        if (!end.ok) return coordinateError(end);
        boolean ok = swipe(start.x, start.y, end.x, end.y, duration);
        return json(200, "{\"ok\":" + ok +
                (start.transformed ? ",\"coordinateSpace\":\"screenshot\",\"screenX1\":" + start.x +
                        ",\"screenY1\":" + start.y + ",\"screenX2\":" + end.x + ",\"screenY2\":" + end.y : "") + "}");
    }

    private Response coordinateError(CoordinateResolution resolution) {
        int status = "capture_mapping_unavailable".equals(resolution.error) ? 409 : 400;
        return json(status, "{\"ok\":false,\"error\":\"" + escape(resolution.error) + "\"}");
    }

    private CoordinateResolution resolveControlPoint(Map<String, String> params, int x, int y) {
        String coordinateSpace = params.getOrDefault("coordinateSpace", "screen");
        if ("screen".equals(coordinateSpace)) return CoordinateResolution.screen(x, y);
        if (!"screenshot".equals(coordinateSpace)) return CoordinateResolution.error("invalid_coordinate_space");
        String captureId = params.getOrDefault("captureId", "");
        if (captureId.isEmpty()) return CoordinateResolution.error("missing_capture_id");
        CaptureCoordinateMapping mapping = findCaptureMapping(captureId);
        if (mapping == null) return CoordinateResolution.error("capture_mapping_unavailable");
        if (!mapping.certain) return CoordinateResolution.error("capture_mapping_uncertain");
        ScreenshotCoordinateMapper.Result transformed = ScreenshotCoordinateMapper.map(
                x, y, mapping.imageWidth, mapping.imageHeight, mapping.bounds.left, mapping.bounds.top,
                mapping.bounds.right, mapping.bounds.bottom);
        if (!transformed.ok) return CoordinateResolution.error(transformed.error);
        return CoordinateResolution.transformed(transformed.x, transformed.y);
    }

    private Response scroll(Map<String, String> params) {
        String direction = params.getOrDefault("direction", "down");
        Rect screen = screenBounds();
        int x = screen.centerX();
        int top = Math.max(screen.top + 240, screen.height() / 4);
        int bottom = Math.max(top + 1, screen.bottom - 240);
        boolean ok = "up".equals(direction)
                ? swipe(x, top, x, bottom, intParam(params, "duration", 350))
                : swipe(x, bottom, x, top, intParam(params, "duration", 350));
        return json(200, "{\"ok\":" + ok + "}");
    }

    private Response scrollUntilText(Map<String, String> params) {
        SearchOptions options = SearchOptions.from(params);
        int attempts = intParam(params, "attempts", 8);
        int interval = Math.max(50, intParam(params, "interval", 250));
        int gestureDuration = Math.max(1, intParam(params, "duration", 350));
        String direction = params.getOrDefault("direction", "down");
        Map<String, String> scrollParams = new HashMap<>(params);
        scrollParams.put("direction", direction);
        for (int i = 0; i <= attempts; i++) {
            Match match = findMatch(options);
            if (match != null) {
                String body = "{\"ok\":true,\"attempts\":" + i + ",\"node\":" + match.toJson(boolParam(params, "redact", false)) + "}";
                match.recycle();
                return json(200, body);
            }
            if (i < attempts) {
                scroll(scrollParams);
                sleep(Math.max(interval, gestureDuration + 32));
            }
        }
        return json(404, "{\"ok\":false,\"error\":\"not_found\",\"texts\":" + visibleTextsJson(12, true) + "}");
    }

    private Response appLaunch(Map<String, String> params) {
        String pkg = params.getOrDefault("package", "");
        if (pkg.isEmpty()) return json(400, "{\"ok\":false,\"error\":\"missing_package\"}");
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(pkg);
        if (intent == null) return json(404, "{\"ok\":false,\"error\":\"no_launch_intent\"}");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        performanceMetrics.beginLaunch(SystemClock.uptimeMillis());
        long dispatchStartedNanos = System.nanoTime();
        try {
            startActivity(intent);
            performanceMetrics.recordLaunchDispatch(System.nanoTime() - dispatchStartedNanos, true);
            return json(200, "{\"ok\":true}");
        } catch (Exception e) {
            performanceMetrics.recordLaunchDispatch(System.nanoTime() - dispatchStartedNanos, false);
            return json(500, "{\"ok\":false,\"error\":\"" + escape(e.getClass().getSimpleName()) + "\"}");
        }
    }

    private Response appStart(Map<String, String> params) {
        String component = params.getOrDefault("component", "");
        if (component.isEmpty()) return json(400, "{\"ok\":false,\"error\":\"missing_component\"}");
        ComponentName name = ComponentName.unflattenFromString(component);
        if (name == null && component.contains("/.")) {
            String[] parts = component.split("/", 2);
            name = new ComponentName(parts[0], parts[0] + parts[1]);
        }
        if (name == null) return json(400, "{\"ok\":false,\"error\":\"bad_component\"}");
        Intent intent = new Intent();
        intent.setComponent(name);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        try {
            startActivity(intent);
            return json(200, "{\"ok\":true}");
        } catch (Exception e) {
            return json(500, "{\"ok\":false,\"error\":\"" + escape(e.getClass().getSimpleName()) + "\"}");
        }
    }

    private WsCallResult executeWsOpenUri(String id, String paramsJson, long started) {
        OpenUriParams params;
        try {
            params = parseOpenUriParams(paramsJson);
        } catch (OpenUriParamException e) {
            return WsCallResult.error(id, e.code, e.code, elapsed(started));
        }

        String uriError = OpenUriContract.validateUri(params.uri);
        if (!uriError.isEmpty()) {
            return WsCallResult.error(id, uriError, uriError, elapsed(started));
        }
        String packageError = OpenUriContract.validatePackage(params.packageName, params.packagePresent);
        if (!packageError.isEmpty()) {
            return WsCallResult.error(id, packageError, packageError, elapsed(started));
        }

        final Uri data;
        try {
            data = Uri.parse(params.uri);
        } catch (RuntimeException e) {
            return WsCallResult.error(id, "invalid_uri", "invalid_uri", elapsed(started));
        }
        if (data == null || !data.isAbsolute() || data.getScheme() == null) {
            return WsCallResult.error(id, "invalid_uri", "invalid_uri", elapsed(started));
        }

        Intent intent = new Intent(Intent.ACTION_VIEW, data);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (params.packagePresent) intent.setPackage(params.packageName);

        final ComponentName resolved;
        try {
            resolved = intent.resolveActivity(getPackageManager());
        } catch (RuntimeException e) {
            return WsCallResult.error(id, "no_matching_activity", "no_matching_activity", elapsed(started));
        }
        if (resolved == null) {
            return WsCallResult.error(id, "no_matching_activity", "no_matching_activity", elapsed(started));
        }

        try {
            startActivity(intent);
            return WsCallResult.ok(id,
                    "{\"ok\":true,\"resolved\":true,\"dispatched\":true}", elapsed(started));
        } catch (RuntimeException e) {
            return WsCallResult.error(id, "start_failed", "start_failed", elapsed(started));
        }
    }

    private Response appCurrent() {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return json(503, "{\"ok\":false,\"error\":\"no_root\"}");
        try {
            return json(200, "{\"ok\":true,\"package\":\"" + escape(str(root.getPackageName())) + "\",\"className\":\"" + escape(str(root.getClassName())) + "\"}");
        } finally {
            root.recycle();
        }
    }

    private String appList(Map<String, String> params) {
        boolean all = boolParam(params, "all", false);
        PackageManager pm = getPackageManager();
        List<AppEntry> entries = all ? installedAppEntries(pm) : launchableAppEntries(pm);
        sortAppEntries(entries);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"all\":").append(all).append(",\"apps\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append(entries.get(i).toJson());
        }
        sb.append("]}");
        return sb.toString();
    }

    private List<AppEntry> launchableAppEntries(PackageManager pm) {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolves = pm.queryIntentActivities(intent, 0);
        List<AppEntry> out = new ArrayList<>();
        for (ResolveInfo info : resolves) {
            if (info == null || info.activityInfo == null || info.activityInfo.packageName == null || info.activityInfo.name == null) {
                continue;
            }
            ApplicationInfo appInfo = info.activityInfo.applicationInfo;
            String packageName = info.activityInfo.packageName;
            String activityName = info.activityInfo.name;
            String label = str(info.loadLabel(pm));
            if (label.isEmpty() && appInfo != null) label = str(appInfo.loadLabel(pm));
            if (label.isEmpty()) label = packageName;
            boolean enabled = appInfo == null || (appInfo.enabled && info.activityInfo.enabled);
            out.add(new AppEntry(label, packageName, activityName, componentName(packageName, activityName), isSystemApp(appInfo), enabled, true));
        }
        return out;
    }

    private List<AppEntry> installedAppEntries(PackageManager pm) {
        List<ApplicationInfo> apps = pm.getInstalledApplications(0);
        List<AppEntry> out = new ArrayList<>();
        for (ApplicationInfo appInfo : apps) {
            if (appInfo == null || appInfo.packageName == null) continue;
            String packageName = appInfo.packageName;
            Intent launchIntent = pm.getLaunchIntentForPackage(packageName);
            String activityName = "";
            String component = "";
            boolean launchable = false;
            if (launchIntent != null) {
                ResolveInfo resolve = pm.resolveActivity(launchIntent, 0);
                if (resolve != null && resolve.activityInfo != null) {
                    activityName = str(resolve.activityInfo.name);
                    component = componentName(packageName, activityName);
                    launchable = !activityName.isEmpty();
                }
            }
            String label = str(appInfo.loadLabel(pm));
            if (label.isEmpty()) label = packageName;
            out.add(new AppEntry(label, packageName, activityName, component, isSystemApp(appInfo), appInfo.enabled, launchable));
        }
        return out;
    }

    private static boolean isSystemApp(ApplicationInfo appInfo) {
        return appInfo != null && (appInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
    }

    private static String componentName(String packageName, String activityName) {
        if (packageName == null || packageName.isEmpty() || activityName == null || activityName.isEmpty()) return "";
        if (activityName.startsWith(packageName + ".")) {
            return packageName + "/." + activityName.substring(packageName.length() + 1);
        }
        return packageName + "/" + activityName;
    }

    private static void sortAppEntries(List<AppEntry> entries) {
        for (int i = 1; i < entries.size(); i++) {
            AppEntry value = entries.get(i);
            int j = i - 1;
            while (j >= 0 && compareAppEntries(entries.get(j), value) > 0) {
                entries.set(j + 1, entries.get(j));
                j--;
            }
            entries.set(j + 1, value);
        }
    }

    private static int compareAppEntries(AppEntry left, AppEntry right) {
        int byLabel = left.label.toLowerCase(Locale.ROOT).compareTo(right.label.toLowerCase(Locale.ROOT));
        if (byLabel != 0) return byLabel;
        return left.packageName.compareTo(right.packageName);
    }

    private Response screenshot(Map<String, String> params) {
        ScreenshotCapture capture = captureScreenshot(params);
        if (capture.bytes == null) {
            int status = "unsupported_api_level".equals(capture.error) ? 501
                    : "timeout".equals(capture.error) ? 504 : 500;
            return json(status, "{\"ok\":false,\"error\":\"" + escape(capture.error == null ? "capture_failed" : capture.error) + "\"}");
        }
        return bytes(200, "image/png", capture.bytes, capture.httpHeaders());
    }

    private ScreenshotCapture captureScreenshot(Map<String, String> params) {
        ScreenshotCapture capture = new ScreenshotCapture();
        capture.captureId = UUID.randomUUID().toString();
        capture.beginElapsedRealtimeMs = SystemClock.elapsedRealtime();
        if (Build.VERSION.SDK_INT < 30) {
            capture.error = "unsupported_api_level";
            capture.endElapsedRealtimeMs = SystemClock.elapsedRealtime();
            return capture;
        }
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        boolean includeIndicator = boolParam(params, "includeIndicator", false);
        int screenshotWindowId = -1;
        int screenshotDisplayId = preferredTargetDisplayId;
        Rect rootBounds = new Rect();
        if (!includeIndicator && Build.VERSION.SDK_INT >= 34) {
            ResolvedTarget target = resolveTarget();
            if (target != null) {
                screenshotWindowId = target.windowId;
                screenshotDisplayId = target.displayId;
                target.root.getBoundsInScreen(rootBounds);
                target.root.recycle();
            }
        }
        boolean windowScopedCapture = screenshotWindowId >= 0;
        prepareCaptureCoordinates(capture, windowScopedCapture, screenshotWindowId, screenshotDisplayId, rootBounds);
        boolean suppressIndicator = overlay != null && !includeIndicator && !windowScopedCapture;
        if (suppressIndicator && !overlay.beginScreenshotSuppression(500L)) {
            capture.error = "indicator_suppression_timeout";
            capture.endElapsedRealtimeMs = SystemClock.elapsedRealtime();
            return capture;
        }
        AtomicBoolean indicatorRestored = new AtomicBoolean(false);
        Runnable restoreIndicator = () -> {
            if (suppressIndicator && indicatorRestored.compareAndSet(false, true)) {
                overlay.endScreenshotSuppression();
            }
        };
        CountDownLatch latch = new CountDownLatch(1);
        Executor executor = command -> {
            if (Looper.myLooper() == Looper.getMainLooper()) command.run();
            else getMainExecutor().execute(command);
        };
        try {
            ScreenshotCallback callback = new ScreenshotCallback(capture, latch, restoreIndicator);
            if (windowScopedCapture) {
                takeScreenshotOfWindow(screenshotWindowId, executor, callback);
            } else {
                takeScreenshot(screenshotDisplayId, executor, callback);
            }
            if (!latch.await(intParam(params, "timeout", 5000), TimeUnit.MILLISECONDS)) {
                capture.error = "timeout";
            }
        } catch (RuntimeException e) {
            capture.error = e.getClass().getSimpleName();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            capture.error = "interrupted";
        } finally {
            restoreIndicator.run();
        }
        capture.endElapsedRealtimeMs = SystemClock.elapsedRealtime();
        if (capture.bytes != null) {
            finalizeCaptureCoordinates(capture);
            rememberCaptureMapping(capture.mapping);
        }
        return capture;
    }

    private void prepareCaptureCoordinates(ScreenshotCapture capture, boolean windowScoped, int windowId,
                                           int displayId, Rect rootBounds) {
        capture.captureMode = windowScoped ? "window" : "display";
        capture.windowId = windowScoped ? windowId : -1;
        capture.displayId = displayId;
        Rect displayBounds = realDisplayBounds(capture.displayId);
        capture.displayMetricsCertain = displayBounds != null;
        if (displayBounds == null) displayBounds = screenBounds();
        capture.displayWidth = displayBounds.width();
        capture.displayHeight = displayBounds.height();
        if (!windowScoped) {
            capture.captureBounds = displayBounds;
            capture.boundsSource = "display";
            return;
        }
        List<AccessibilityWindowInfo> windows = allWindows();
        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window.getId() != windowId) continue;
                Rect bounds = new Rect();
                window.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) {
                    capture.captureBounds = bounds;
                    capture.boundsSource = "accessibility_window";
                    if (Build.VERSION.SDK_INT >= 30) capture.displayId = window.getDisplayId();
                    Rect selectedDisplay = realDisplayBounds(capture.displayId);
                    capture.displayMetricsCertain = selectedDisplay != null;
                    if (selectedDisplay != null) {
                        capture.displayWidth = selectedDisplay.width();
                        capture.displayHeight = selectedDisplay.height();
                    }
                }
                break;
            }
            recycleWindows(windows);
        }
        if (capture.captureBounds == null && rootBounds != null && !rootBounds.isEmpty()) {
            capture.captureBounds = new Rect(rootBounds);
            capture.boundsSource = "active_root_fallback";
        }
    }

    private Rect realDisplayBounds(int displayId) {
        DisplayManager manager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null && manager != null) display = manager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display != null) {
            DisplayMetrics metrics = new DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
            }
        }
        return null;
    }

    private void finalizeCaptureCoordinates(ScreenshotCapture capture) {
        Rect bounds = capture.captureBounds;
        if ("display".equals(capture.captureMode) && !capture.displayMetricsCertain) {
            capture.displayWidth = capture.imageWidth;
            capture.displayHeight = capture.imageHeight;
            bounds = new Rect(0, 0, capture.imageWidth, capture.imageHeight);
            capture.boundsSource = "display_bitmap";
        }
        boolean exactDisplay = capture.displayMetricsCertain && capture.imageWidth == capture.displayWidth
                && capture.imageHeight == capture.displayHeight
                && capture.displayWidth > 0 && capture.displayHeight > 0;
        if (exactDisplay) {
            bounds = new Rect(0, 0, capture.displayWidth, capture.displayHeight);
            capture.boundsSource = "bitmap_matches_display";
        }
        boolean valid = bounds != null && !bounds.isEmpty() && capture.imageWidth > 0 && capture.imageHeight > 0;
        boolean withinDisplay = valid && bounds.left >= 0 && bounds.top >= 0
                && bounds.right <= capture.displayWidth && bounds.bottom <= capture.displayHeight;
        boolean boundsVerifiable = capture.displayMetricsCertain || "display_bitmap".equals(capture.boundsSource);
        boolean certain = withinDisplay && boundsVerifiable
                && (exactDisplay || "display".equals(capture.boundsSource)
                || "display_bitmap".equals(capture.boundsSource)
                || "accessibility_window".equals(capture.boundsSource));
        if (!valid) bounds = new Rect();
        double scaleX = valid ? ((double) bounds.width()) / capture.imageWidth : 0.0;
        double scaleY = valid ? ((double) bounds.height()) / capture.imageHeight : 0.0;
        capture.mapping = new CaptureCoordinateMapping(capture.captureId, capture.captureMode,
                capture.displayId, capture.windowId, capture.imageWidth, capture.imageHeight,
                capture.displayWidth, capture.displayHeight, bounds, scaleX, scaleY, certain,
                capture.boundsSource, SystemClock.elapsedRealtime());
    }

    private void rememberCaptureMapping(CaptureCoordinateMapping mapping) {
        if (mapping == null) return;
        synchronized (captureMappingsLock) {
            pruneCaptureMappingsLocked(SystemClock.elapsedRealtime());
            captureMappings.put(mapping.captureId, mapping);
            while (captureMappings.size() > CAPTURE_MAPPING_LIMIT) {
                Iterator<String> iterator = captureMappings.keySet().iterator();
                if (!iterator.hasNext()) break;
                iterator.next();
                iterator.remove();
            }
        }
    }

    private CaptureCoordinateMapping findCaptureMapping(String captureId) {
        synchronized (captureMappingsLock) {
            pruneCaptureMappingsLocked(SystemClock.elapsedRealtime());
            return captureMappings.get(captureId);
        }
    }

    private void pruneCaptureMappingsLocked(long now) {
        Iterator<Map.Entry<String, CaptureCoordinateMapping>> iterator = captureMappings.entrySet().iterator();
        while (iterator.hasNext()) {
            CaptureCoordinateMapping mapping = iterator.next().getValue();
            if (now - mapping.createdElapsedRealtimeMs > CAPTURE_MAPPING_TTL_MS) iterator.remove();
        }
    }

    private ClickResult clickMatch(Match match) {
        showPointGesture(match.centerX(), match.centerY());
        if (match.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return new ClickResult(true, "node");
        }
        AccessibilityNodeInfo parent = match.node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    parent.recycle();
                    return new ClickResult(true, "clickable_parent");
                }
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return new ClickResult(dispatchTap(match.centerX(), match.centerY()), "tap");
    }

    private boolean tap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        showPointGesture(x, y);
        return dispatchTap(x, y);
    }

    private boolean dispatchTap(int x, int y) {
        if (Build.VERSION.SDK_INT < 24) return false;
        if (isGesturePointBlocked(x, y)) return false;
        Path path = new Path();
        path.moveTo(x, y);
        return dispatchGesture(gestureBuilderForTarget()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, 50))
                .build(), null, null);
    }

    private boolean swipe(int x1, int y1, int x2, int y2, int duration) {
        if (Build.VERSION.SDK_INT < 24) return false;
        for (int step = 0; step <= 16; step++) {
            int x = x1 + (x2 - x1) * step / 16;
            int y = y1 + (y2 - y1) * step / 16;
            if (isGesturePointBlocked(x, y)) return false;
        }
        showSwipeGesture(x1, y1, x2, y2, duration);
        Path path = new Path();
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
        return dispatchGesture(gestureBuilderForTarget()
                .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, duration)))
                .build(), null, null);
    }

    private boolean isGesturePointBlocked(int x, int y) {
        ResolvedTarget target = resolveTarget();
        if (target == null) return true;
        target.root.recycle();
        List<AccessibilityWindowInfo> windows = allWindows();
        int targetLayer = Integer.MIN_VALUE;
        for (AccessibilityWindowInfo window : windows) {
            if (window != null && window.getId() == target.windowId
                    && (Build.VERSION.SDK_INT < 30 || window.getDisplayId() == target.displayId)) {
                targetLayer = window.getLayer();
                break;
            }
        }
        if (targetLayer == Integer.MIN_VALUE) {
            recycleWindows(windows);
            return true;
        }
        boolean blocked = false;
        for (AccessibilityWindowInfo window : windows) {
            if (window == null || window.getId() == target.windowId || window.getLayer() <= targetLayer) continue;
            int displayId = Build.VERSION.SDK_INT >= 30 ? window.getDisplayId() : Display.DEFAULT_DISPLAY;
            if (displayId != target.displayId) continue;
            Region touchable = new Region();
            window.getRegionInScreen(touchable);
            if (touchable.contains(x, y)) {
                blocked = true;
                break;
            }
        }
        recycleWindows(windows);
        return blocked;
    }

    private Match findMatch(SearchOptions options) {
        if (options.needle.isEmpty()) return null;
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return null;
        try {
            return findMatch(root, options);
        } finally {
            root.recycle();
        }
    }

    private Match findEditableMatch(SearchOptions options) {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return null;
        try {
            return findEditableMatch(root, options);
        } finally {
            root.recycle();
        }
    }

    private Match findEditableMatch(AccessibilityNodeInfo node, SearchOptions options) {
        if (node.isEditable() && nodeMatches(node, options)) return Match.from(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Match match = findEditableMatch(child, options);
            child.recycle();
            if (match != null) return match;
        }
        return null;
    }

    private Match findFocusedEditable() {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return null;
        try {
            return findFocusedEditable(root);
        } finally {
            root.recycle();
        }
    }

    private Match findFocusedEditable(AccessibilityNodeInfo node) {
        if (node.isEditable() && node.isFocused()) return Match.from(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Match match = findFocusedEditable(child);
            child.recycle();
            if (match != null) return match;
        }
        return null;
    }

    private Match findMatch(AccessibilityNodeInfo node, SearchOptions options) {
        if (nodeMatches(node, options)) return Match.from(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            Match match = findMatch(child, options);
            child.recycle();
            if (match != null) return match;
        }
        return null;
    }

    private boolean nodeMatches(AccessibilityNodeInfo node, SearchOptions options) {
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (options.visibleOnly && !isVisible(bounds)) return false;
        if (options.clickableOnly && !node.isClickable() && !hasClickableParent(node)) return false;
        if ("text".equals(options.field)) return valueMatches(str(node.getText()), options);
        if ("desc".equals(options.field)) return valueMatches(str(node.getContentDescription()), options);
        if ("viewId".equals(options.field)) return valueMatches(str(node.getViewIdResourceName()), options);
        return valueMatches(str(node.getText()), options)
                || valueMatches(str(node.getContentDescription()), options)
                || valueMatches(str(node.getViewIdResourceName()), options);
    }

    private boolean hasClickableParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo parent = node.getParent();
        while (parent != null) {
            if (parent.isClickable()) {
                parent.recycle();
                return true;
            }
            AccessibilityNodeInfo next = parent.getParent();
            parent.recycle();
            parent = next;
        }
        return false;
    }

    private boolean valueMatches(String value, SearchOptions options) {
        if (value.isEmpty()) return false;
        String lower = value.toLowerCase(Locale.ROOT);
        return options.exact ? lower.equals(options.needle) : lower.contains(options.needle);
    }

    private Rect screenBounds() {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        Rect rect = new Rect(0, 0, 1080, 2400);
        if (root != null) {
            root.getBoundsInScreen(rect);
            root.recycle();
        }
        return rect;
    }

    private boolean isVisible(Rect bounds) {
        return bounds.width() > 0 && bounds.height() > 0 && bounds.right > 0 && bounds.bottom > 0;
    }

    private void appendNode(StringBuilder sb, AccessibilityNodeInfo node, int depth, int index, int maxDepth, boolean redact, Counter counter) {
        counter.count++;
        if (counter.count > counter.max) {
            counter.truncated = true;
            sb.append("{}");
            return;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        sb.append("{");
        field(sb, "text", filter(str(node.getText()), redact));
        comma(sb); field(sb, "desc", filter(str(node.getContentDescription()), redact));
        comma(sb); field(sb, "viewId", str(node.getViewIdResourceName()));
        comma(sb); field(sb, "className", str(node.getClassName()));
        comma(sb); sb.append("\"clickable\":").append(node.isClickable());
        comma(sb); sb.append("\"editable\":").append(node.isEditable());
        comma(sb); sb.append("\"focused\":").append(node.isFocused());
        comma(sb); sb.append("\"enabled\":").append(node.isEnabled());
        comma(sb); sb.append("\"depth\":").append(depth);
        comma(sb); sb.append("\"index\":").append(index);
        comma(sb); field(sb, "bounds", boundsString(bounds));
        comma(sb); sb.append("\"children\":[");
        if (depth < maxDepth) {
            boolean first = true;
            for (int i = 0; i < node.getChildCount() && !counter.truncated; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                if (!first) sb.append(",");
                appendNode(sb, child, depth + 1, i, maxDepth, redact, counter);
                child.recycle();
                first = false;
            }
        }
        sb.append("]}");
    }

    private void appendFlatNodes(StringBuilder sb, AccessibilityNodeInfo node, int depth, int index, int maxDepth, boolean redact, Counter counter) {
        if (counter.truncated || depth > maxDepth) return;
        boolean hasText = !str(node.getText()).isEmpty() || !str(node.getContentDescription()).isEmpty();
        if (hasText) {
            if (counter.count >= counter.max) {
                counter.truncated = true;
                return;
            }
            if (counter.count > 0) sb.append(",");
            counter.count++;
            Match match = Match.from(node);
            sb.append(match.toJson(redact));
            match.recycle();
        }
        for (int i = 0; i < node.getChildCount() && !counter.truncated; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            appendFlatNodes(sb, child, depth + 1, i, maxDepth, redact, counter);
            child.recycle();
        }
    }

    private String visibleTextsJson(int limit, boolean redact) {
        AccessibilityNodeInfo root = resolvedTargetRoot();
        if (root == null) return "[]";
        try {
            List<String> texts = new ArrayList<>();
            collectVisibleTexts(root, texts, limit, redact);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < texts.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append("\"").append(escape(texts.get(i))).append("\"");
            }
            sb.append("]");
            return sb.toString();
        } finally {
            root.recycle();
        }
    }

    private void collectVisibleTexts(AccessibilityNodeInfo node, List<String> out, int limit, boolean redact) {
        if (out.size() >= limit) return;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        String text = str(node.getText());
        if (!text.isEmpty() && isVisible(bounds)) out.add(filter(text, redact));
        String desc = str(node.getContentDescription());
        if (out.size() < limit && !desc.isEmpty() && !desc.equals(text) && isVisible(bounds)) out.add(filter(desc, redact));
        for (int i = 0; i < node.getChildCount() && out.size() < limit; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            collectVisibleTexts(child, out, limit, redact);
            child.recycle();
        }
    }

    private static Map<String, String> aliasParam(Map<String, String> params, String key, String value) {
        Map<String, String> copy = new HashMap<>(params);
        copy.put("text", value);
        copy.remove(key);
        return copy;
    }

    private static final class ResolvedTarget {
        final AccessibilityNodeInfo root;
        final int windowId;
        final int displayId;

        ResolvedTarget(AccessibilityNodeInfo root, int windowId, int displayId) {
            this.root = root;
            this.windowId = windowId;
            this.displayId = displayId;
        }
    }

    private static String filter(String value, boolean redact) {
        if (!redact || value.isEmpty()) return value;
        String out = EMAIL.matcher(value).replaceAll("[email]");
        out = PHONE.matcher(out).replaceAll("[number]");
        return out;
    }

    private static void field(StringBuilder sb, String name, String value) {
        sb.append("\"").append(name).append("\":\"").append(escape(value)).append("\"");
    }

    private static void comma(StringBuilder sb) {
        sb.append(",");
    }

    private static String boundsString(Rect bounds) {
        return bounds.left + "," + bounds.top + "," + bounds.right + "," + bounds.bottom;
    }

    private static String str(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new HashMap<>();
        if (query.isEmpty()) return out;
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq >= 0 ? pair.substring(0, eq) : pair;
            String value = eq >= 0 ? pair.substring(eq + 1) : "";
            out.put(decode(key), decode(value));
        }
        return out;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Map<String, String> parseJsonObject(String json) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        Map<String, String> out = new HashMap<>();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) return out;
        parseJsonMembers(cursor, out);
        return out;
    }

    private static String extractJsonMemberObject(String json, String memberName) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        cursor.skipWhitespace();
        if (!cursor.consume('{')) return "{}";
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return "{}";
            String key = cursor.readString();
            if (key == null) return "{}";
            cursor.skipWhitespace();
            if (!cursor.consume(':')) return "{}";
            cursor.skipWhitespace();
            if (memberName.equals(key) && cursor.peek() == '{') {
                int start = cursor.pos;
                cursor.skipValue();
                return json.substring(start, cursor.pos);
            }
            cursor.skipValue();
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            cursor.consume('}');
            return "{}";
        }
    }

    private static List<String> extractJsonObjectArray(String json, String memberName) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        List<String> out = new ArrayList<>();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) return out;
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return out;
            String key = cursor.readString();
            if (key == null) return out;
            cursor.skipWhitespace();
            if (!cursor.consume(':')) return out;
            cursor.skipWhitespace();
            if (memberName.equals(key) && cursor.consume('[')) {
                while (true) {
                    cursor.skipWhitespace();
                    if (cursor.consume(']')) return out;
                    if (cursor.peek() != '{') return out;
                    int start = cursor.pos;
                    cursor.skipValue();
                    out.add(json.substring(start, cursor.pos));
                    cursor.skipWhitespace();
                    if (cursor.consume(',')) continue;
                    cursor.consume(']');
                    return out;
                }
            }
            cursor.skipValue();
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            cursor.consume('}');
            return out;
        }
    }

    private static List<String> extractJsonStringArray(String json, String memberName) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        List<String> out = new ArrayList<>();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) return out;
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return out;
            String key = cursor.readString();
            if (key == null) return out;
            cursor.skipWhitespace();
            if (!cursor.consume(':')) return out;
            cursor.skipWhitespace();
            if (memberName.equals(key) && cursor.peek() == '[') {
                try {
                    return cursor.readStringArray();
                } catch (IllegalArgumentException ignored) {
                    return new ArrayList<>();
                }
            }
            cursor.skipValue();
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            cursor.consume('}');
            return out;
        }
    }

    private static TaskProgressParams parseTaskProgressParams(String json) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        TaskProgressParams params = new TaskProgressParams();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) throw new IllegalArgumentException("params must be an object");
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return params;
            String key = cursor.readString();
            if (key == null) throw new IllegalArgumentException("progress keys must be strings");
            cursor.skipWhitespace();
            if (!cursor.consume(':')) throw new IllegalArgumentException("missing progress separator");
            cursor.skipWhitespace();
            if ("goal".equals(key) || "current".equals(key)) {
                String value = cursor.readString();
                if (value == null) throw new IllegalArgumentException("progress text must be strings");
                if ("goal".equals(key)) params.goal = value;
                else {
                    params.current = value;
                    params.currentPresent = true;
                }
            } else if ("completed".equals(key)) {
                params.completed = cursor.readStringArray();
                params.completedPresent = true;
            } else if ("steps".equals(key)) {
                params.steps = readTaskProgressSteps(cursor);
                params.stepsPresent = true;
            } else if ("replan".equals(key)) {
                params.replan = cursor.readBoolean();
                params.replanPresent = true;
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            if (cursor.consume('}')) return params;
            throw new IllegalArgumentException("malformed progress params");
        }
    }

    private static OpenUriParams parseOpenUriParams(String json) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        OpenUriParams params = new OpenUriParams();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) throw new OpenUriParamException("missing_uri");
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) break;
            String key = cursor.readString();
            if (key == null) throw new OpenUriParamException("invalid_uri");
            cursor.skipWhitespace();
            if (!cursor.consume(':')) throw new OpenUriParamException("invalid_uri");
            cursor.skipWhitespace();
            if ("uri".equals(key)) {
                params.uriPresent = true;
                params.uri = cursor.readString();
                if (params.uri == null) throw new OpenUriParamException("invalid_uri");
            } else if ("package".equals(key)) {
                params.packagePresent = true;
                params.packageName = cursor.readString();
                if (params.packageName == null) throw new OpenUriParamException("invalid_package");
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            if (cursor.consume('}')) break;
            throw new OpenUriParamException("invalid_uri");
        }
        if (!params.uriPresent) throw new OpenUriParamException("missing_uri");
        return params;
    }

    private static String parseTaskCompletionMessage(String json) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        cursor.skipWhitespace();
        if (!cursor.consume('{')) throw new IllegalArgumentException("params must be an object");
        String message = null;
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) {
                if (message == null) throw new IllegalArgumentException("message is required");
                return message;
            }
            String key = cursor.readString();
            if (key == null) throw new IllegalArgumentException("completion keys must be strings");
            if (!cursor.consume(':')) throw new IllegalArgumentException("missing completion separator");
            if ("message".equals(key)) {
                message = cursor.readString();
                if (message == null) throw new IllegalArgumentException("message must be a string");
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            if (cursor.consume('}')) {
                if (message == null) throw new IllegalArgumentException("message is required");
                return message;
            }
            throw new IllegalArgumentException("malformed completion params");
        }
    }

    private static List<TaskProgressModel.Step> readTaskProgressSteps(JsonCursor cursor) {
        if (!cursor.consume('[')) throw new IllegalArgumentException("steps must be an array");
        List<TaskProgressModel.Step> steps = new ArrayList<>();
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume(']')) return steps;
            if (!cursor.consume('{')) throw new IllegalArgumentException("steps must contain objects");
            String text = null;
            String status = null;
            while (true) {
                cursor.skipWhitespace();
                if (cursor.consume('}')) break;
                String key = cursor.readString();
                if (key == null) throw new IllegalArgumentException("step keys must be strings");
                if (!cursor.consume(':')) throw new IllegalArgumentException("missing step separator");
                if ("text".equals(key) || "status".equals(key)) {
                    String value = cursor.readString();
                    if (value == null) throw new IllegalArgumentException("step fields must be strings");
                    if ("text".equals(key)) text = value;
                    else status = value;
                } else {
                    cursor.skipValue();
                }
                cursor.skipWhitespace();
                if (cursor.consume(',')) continue;
                if (cursor.consume('}')) break;
                throw new IllegalArgumentException("malformed step object");
            }
            steps.add(new TaskProgressModel.Step(text, status));
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            if (cursor.consume(']')) return steps;
            throw new IllegalArgumentException("malformed steps array");
        }
    }

    private static RemoteAccessConfig.Update parseRemoteAccessUpdate(String json) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        RemoteAccessConfig.Update update = new RemoteAccessConfig.Update();
        cursor.skipWhitespace();
        if (!cursor.consume('{')) throw new IllegalArgumentException("body must be a JSON object");
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return update;
            String key = cursor.readString();
            if (key == null) throw new IllegalArgumentException("object keys must be strings");
            cursor.skipWhitespace();
            if (!cursor.consume(':')) throw new IllegalArgumentException("missing ':' after " + key);
            cursor.skipWhitespace();
            if ("enabled".equals(key)) {
                update.enabled = cursor.readBoolean();
            } else if ("bindHost".equals(key)) {
                update.bindHost = cursor.readString();
                if (update.bindHost == null) throw new IllegalArgumentException("bindHost must be a string");
            } else if ("bindPort".equals(key) || "port".equals(key)) {
                update.port = cursor.readInteger();
            } else if ("allowedCidrs".equals(key)) {
                update.allowedCidrs = cursor.readStringArray();
            } else if ("requireAuth".equals(key)) {
                update.requireAuth = cursor.readBoolean();
            } else {
                cursor.skipValue();
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            if (cursor.consume('}')) return update;
            throw new IllegalArgumentException("expected ',' or '}'");
        }
    }

    private static void parseJsonMembers(JsonCursor cursor, Map<String, String> out) {
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) return;
            String key = cursor.readString();
            if (key == null) return;
            cursor.skipWhitespace();
            if (!cursor.consume(':')) return;
            cursor.skipWhitespace();
            if ("selector".equals(key) && cursor.peek() == '{') {
                cursor.consume('{');
                parseJsonMembers(cursor, out);
            } else if (cursor.peek() == '{' || cursor.peek() == '[') {
                // Envelope members such as `params` may precede scalar
                // correlation metadata. Consume the complete nested value so
                // parsing resumes at the next top-level member (`actionId`,
                // `timeoutMs`, ...), instead of mistaking the nested closing
                // brace for the end of this object.
                cursor.skipValue();
            } else {
                String value = cursor.readValue();
                if (value != null) out.put(key, value);
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            cursor.consume('}');
            return;
        }
    }

    private static int intParam(Map<String, String> params, String key, int fallback) {
        try {
            return Integer.parseInt(params.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static Point pointParam(Map<String, String> params) {
        int x = intParam(params, "x", -1);
        int y = intParam(params, "y", -1);
        if (x >= 0 && y >= 0) return new Point(x, y);

        String bounds = params.getOrDefault("bounds", "");
        String[] parts = bounds.split(",");
        if (parts.length != 4) return null;
        try {
            int left = Integer.parseInt(parts[0].trim());
            int top = Integer.parseInt(parts[1].trim());
            int right = Integer.parseInt(parts[2].trim());
            int bottom = Integer.parseInt(parts[3].trim());
            if (right < left || bottom < top) return null;
            return new Point((left + right) / 2, (top + bottom) / 2);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static boolean boolParam(Map<String, String> params, String key, boolean fallback) {
        String value = params.get(key);
        if (value == null) return fallback;
        return "1".equals(value) || "true".equalsIgnoreCase(value) || "yes".equalsIgnoreCase(value);
    }

    private static long longParam(Map<String, String> params, String key, long fallback) {
        try {
            return Long.parseLong(params.getOrDefault(key, String.valueOf(fallback)));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static String jsonObjectWithDefaultNumber(String json, String key, String value) {
        if (parseJsonObject(json).containsKey(key)) return json;
        String cleanValue = value == null ? "" : value.trim();
        if (!cleanValue.matches("[0-9]+")) return json;
        String body = json == null || json.trim().isEmpty() ? "{}" : json.trim();
        if (!body.startsWith("{") || !body.endsWith("}")) return json;
        if ("{}".equals(body)) return "{\"" + key + "\":" + cleanValue + "}";
        return body.substring(0, body.length() - 1) + ",\"" + key + "\":" + cleanValue + "}";
    }

    private static String stripJsonObjectFields(String json, String... names) {
        JsonCursor cursor = new JsonCursor(json == null ? "" : json);
        StringBuilder out = new StringBuilder("{");
        boolean first = true;
        cursor.skipWhitespace();
        if (!cursor.consume('{')) return "{}";
        while (true) {
            cursor.skipWhitespace();
            if (cursor.consume('}')) break;
            int memberStart = cursor.pos;
            String key = cursor.readString();
            if (key == null) break;
            cursor.skipWhitespace();
            if (!cursor.consume(':')) break;
            cursor.skipValue();
            int memberEnd = cursor.pos;
            boolean strip = false;
            for (String name : names) {
                if (name.equals(key)) {
                    strip = true;
                    break;
                }
            }
            if (!strip) {
                if (!first) out.append(",");
                out.append(json, memberStart, memberEnd);
                first = false;
            }
            cursor.skipWhitespace();
            if (cursor.consume(',')) continue;
            cursor.consume('}');
            break;
        }
        out.append("}");
        return out.toString();
    }

    private static String digest(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                int v = b & 0xff;
                if (v < 0x10) {
                    out.append('0');
                }
                out.append(Integer.toHexString(v));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private static Response json(int status, String body) {
        return new Response(status + " " + (status == 200 ? "OK" : "ERROR"), "application/json", body.getBytes(StandardCharsets.UTF_8));
    }

    private static Response bytes(int status, String contentType, byte[] body) {
        return new Response(status + " " + (status == 200 ? "OK" : "ERROR"), contentType, body);
    }

    private static Response bytes(int status, String contentType, byte[] body, Map<String, String> headers) {
        return new Response(status + " " + (status == 200 ? "OK" : "ERROR"), contentType, body, headers);
    }

    private static final class JsonCursor {
        final String text;
        int pos;

        JsonCursor(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) pos++;
        }

        char peek() {
            return pos < text.length() ? text.charAt(pos) : '\0';
        }

        boolean consume(char expected) {
            skipWhitespace();
            if (pos < text.length() && text.charAt(pos) == expected) {
                pos++;
                return true;
            }
            return false;
        }

        String readString() {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != '"') return null;
            pos++;
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') return out.toString();
                if (c == '\\' && pos < text.length()) {
                    char e = text.charAt(pos++);
                    switch (e) {
                        case '"': out.append('"'); break;
                        case '\\': out.append('\\'); break;
                        case '/': out.append('/'); break;
                        case 'b': out.append('\b'); break;
                        case 'f': out.append('\f'); break;
                        case 'n': out.append('\n'); break;
                        case 'r': out.append('\r'); break;
                        case 't': out.append('\t'); break;
                        case 'u':
                            if (pos + 4 <= text.length()) {
                                try {
                                    out.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                                    pos += 4;
                                } catch (NumberFormatException ignored) {
                                }
                            }
                            break;
                        default:
                            out.append(e);
                    }
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }

        String readValue() {
            skipWhitespace();
            if (peek() == '"') return readString();
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ',' || c == '}' || Character.isWhitespace(c)) break;
                pos++;
            }
            if (start == pos) return "";
            String value = text.substring(start, pos);
            return "null".equals(value) ? "" : value;
        }

        Boolean readBoolean() {
            String value = readValue();
            if ("true".equals(value)) return true;
            if ("false".equals(value)) return false;
            throw new IllegalArgumentException("expected boolean");
        }

        Integer readInteger() {
            String value = readValue();
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("expected integer");
            }
        }

        List<String> readStringArray() {
            skipWhitespace();
            if (!consume('[')) throw new IllegalArgumentException("expected string array");
            List<String> out = new ArrayList<>();
            while (true) {
                skipWhitespace();
                if (consume(']')) return out;
                String value = readString();
                if (value == null) throw new IllegalArgumentException("array values must be strings");
                out.add(value);
                skipWhitespace();
                if (consume(',')) continue;
                if (consume(']')) return out;
                throw new IllegalArgumentException("expected ',' or ']'");
            }
        }

        void skipValue() {
            skipWhitespace();
            char c = peek();
            if (c == '"') {
                readString();
                return;
            }
            if (c == '{') {
                pos++;
                int depth = 1;
                while (pos < text.length() && depth > 0) {
                    char next = peek();
                    if (next == '"') {
                        readString();
                    } else {
                        pos++;
                        if (next == '{') depth++;
                        else if (next == '}') depth--;
                    }
                }
                return;
            }
            if (c == '[') {
                pos++;
                int depth = 1;
                while (pos < text.length() && depth > 0) {
                    char next = peek();
                    if (next == '"') {
                        readString();
                    } else {
                        pos++;
                        if (next == '[') depth++;
                        else if (next == ']') depth--;
                    }
                }
                return;
            }
            readValue();
        }
    }

    private static final class SearchOptions {
        final String needle;
        final boolean exact;
        final String field;
        final boolean clickableOnly;
        final boolean visibleOnly;

        SearchOptions(String needle, boolean exact, String field, boolean clickableOnly, boolean visibleOnly) {
            this.needle = needle == null ? "" : needle.toLowerCase(Locale.ROOT);
            this.exact = exact;
            this.field = field == null ? "any" : field;
            this.clickableOnly = clickableOnly;
            this.visibleOnly = visibleOnly;
        }

        static SearchOptions from(Map<String, String> params) {
            return new SearchOptions(
                    params.getOrDefault("text", ""),
                    boolParam(params, "exact", false),
                    params.getOrDefault("field", "any"),
                    boolParam(params, "clickableOnly", false),
                    boolParam(params, "visibleOnly", true));
        }
    }

    private static final class Match {
        final AccessibilityNodeInfo node;
        final Rect bounds;
        final String text;
        final String desc;
        final String viewId;
        final String className;
        final boolean clickable;
        final boolean editable;
        final boolean focused;

        private Match(AccessibilityNodeInfo node, Rect bounds, String text, String desc, String viewId, String className, boolean clickable, boolean editable, boolean focused) {
            this.node = AccessibilityNodeInfo.obtain(node);
            this.bounds = bounds;
            this.text = text;
            this.desc = desc;
            this.viewId = viewId;
            this.className = className;
            this.clickable = clickable;
            this.editable = editable;
            this.focused = focused;
        }

        static Match from(AccessibilityNodeInfo node) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            return new Match(node, bounds, str(node.getText()), str(node.getContentDescription()), str(node.getViewIdResourceName()), str(node.getClassName()), node.isClickable(), node.isEditable(), node.isFocused());
        }

        int centerX() {
            return (bounds.left + bounds.right) / 2;
        }

        int centerY() {
            return (bounds.top + bounds.bottom) / 2;
        }

        void recycle() {
            node.recycle();
        }

        String toJson(boolean redact) {
            return "{" +
                    "\"text\":\"" + escape(filter(text, redact)) + "\"," +
                    "\"desc\":\"" + escape(filter(desc, redact)) + "\"," +
                    "\"viewId\":\"" + escape(viewId) + "\"," +
                    "\"className\":\"" + escape(className) + "\"," +
                    "\"clickable\":" + clickable + "," +
                    "\"editable\":" + editable + "," +
                    "\"focused\":" + focused + "," +
                    "\"bounds\":\"" + boundsString(bounds) + "\"," +
                    "\"centerX\":" + centerX() + "," +
                    "\"centerY\":" + centerY() +
                    "}";
        }
    }

    private static final class AppEntry {
        final String label;
        final String packageName;
        final String activity;
        final String component;
        final boolean system;
        final boolean enabled;
        final boolean launchable;

        AppEntry(String label, String packageName, String activity, String component, boolean system, boolean enabled, boolean launchable) {
            this.label = label == null ? "" : label;
            this.packageName = packageName == null ? "" : packageName;
            this.activity = activity == null ? "" : activity;
            this.component = component == null ? "" : component;
            this.system = system;
            this.enabled = enabled;
            this.launchable = launchable;
        }

        String toJson() {
            return "{" +
                    "\"label\":\"" + escape(label) + "\"," +
                    "\"package\":\"" + escape(packageName) + "\"," +
                    "\"activity\":\"" + escape(activity) + "\"," +
                    "\"component\":\"" + escape(component) + "\"," +
                    "\"system\":" + system + "," +
                    "\"enabled\":" + enabled + "," +
                    "\"launchable\":" + launchable +
                    "}";
        }

    }

    private static final class ClickResult {
        final boolean ok;
        final String strategy;

        ClickResult(boolean ok, String strategy) {
            this.ok = ok;
            this.strategy = strategy;
        }
    }

    private static final class OpenUriParams {
        String uri;
        boolean uriPresent;
        String packageName;
        boolean packagePresent;
    }

    private static final class OpenUriParamException extends IllegalArgumentException {
        final String code;

        OpenUriParamException(String code) {
            super(code);
            this.code = code;
        }
    }

    private static final class TaskProgressParams {
        String goal = "";
        String current = "";
        List<String> completed = new ArrayList<>();
        List<TaskProgressModel.Step> steps = new ArrayList<>();
        boolean completedPresent;
        boolean currentPresent;
        boolean stepsPresent;
        boolean replan;
        boolean replanPresent;
    }

    private static final class Point {
        final int x;
        final int y;

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    private static final class Counter {
        final int max;
        int count;
        boolean truncated;

        Counter(int max) {
            this.max = Math.max(1, max);
        }
    }

    private static final class Response {
        final String status;
        final String contentType;
        final byte[] body;
        final Map<String, String> headers;

        Response(String status, String contentType, byte[] body) {
            this(status, contentType, body, new LinkedHashMap<>());
        }

        Response(String status, String contentType, byte[] body, Map<String, String> headers) {
            this.status = status;
            this.contentType = contentType;
            this.body = body;
            this.headers = headers == null ? new LinkedHashMap<>() : headers;
        }
    }

    private static final class UiTreeSnapshot {
        final long treeVersion;
        final long captureBeginEventSeq;
        final long captureEndEventSeq;
        final long captureBeginElapsedRealtimeMs;
        final long captureEndElapsedRealtimeMs;
        final String capturedAt;
        final String reason;
        final String actionId;
        final String currentAppJson;
        final String treeJson;
        final String treeDigest;
        final String errorCode;
        final String serviceEpoch;
        final boolean sensitiveUi;

        private UiTreeSnapshot(long treeVersion, long captureBeginEventSeq, long captureEndEventSeq,
                               long captureBeginElapsedRealtimeMs, long captureEndElapsedRealtimeMs,
                               String capturedAt, String reason, String actionId, String currentAppJson,
                               String treeJson, String treeDigest, String errorCode, String serviceEpoch,
                               boolean sensitiveUi) {
            this.treeVersion = treeVersion;
            this.captureBeginEventSeq = captureBeginEventSeq;
            this.captureEndEventSeq = captureEndEventSeq;
            this.captureBeginElapsedRealtimeMs = captureBeginElapsedRealtimeMs;
            this.captureEndElapsedRealtimeMs = captureEndElapsedRealtimeMs;
            this.capturedAt = capturedAt == null ? "" : capturedAt;
            this.reason = reason == null ? "" : reason;
            this.actionId = actionId == null ? "" : actionId;
            this.currentAppJson = currentAppJson == null ? "{}" : currentAppJson;
            this.treeJson = treeJson == null ? "{}" : treeJson;
            this.treeDigest = treeDigest == null ? "" : treeDigest;
            this.errorCode = errorCode == null ? "" : errorCode;
            this.serviceEpoch = serviceEpoch == null ? "" : serviceEpoch;
            this.sensitiveUi = sensitiveUi;
        }

        static UiTreeSnapshot success(long treeVersion, long captureBeginEventSeq, long captureEndEventSeq,
                                      long captureBeginElapsedRealtimeMs, long captureEndElapsedRealtimeMs,
                                      String capturedAt, String reason, String actionId, String currentAppJson,
                                      String treeJson, String treeDigest, String serviceEpoch, boolean sensitiveUi) {
            return new UiTreeSnapshot(treeVersion, captureBeginEventSeq, captureEndEventSeq,
                    captureBeginElapsedRealtimeMs, captureEndElapsedRealtimeMs, capturedAt, reason, actionId,
                    currentAppJson, treeJson, treeDigest, "", serviceEpoch, sensitiveUi);
        }

        static UiTreeSnapshot error(long treeVersion, long captureBeginEventSeq, long captureEndEventSeq,
                                    long captureBeginElapsedRealtimeMs, long captureEndElapsedRealtimeMs,
                                    String capturedAt, String reason, String errorCode, String serviceEpoch,
                                    boolean sensitiveUi) {
            return new UiTreeSnapshot(treeVersion, captureBeginEventSeq, captureEndEventSeq,
                    captureBeginElapsedRealtimeMs, captureEndElapsedRealtimeMs, capturedAt, reason, "", "{}", "{}", "",
                    errorCode, serviceEpoch, sensitiveUi);
        }

        boolean ok() {
            return errorCode.isEmpty();
        }

        String errorJson(boolean settled, boolean timedOut) {
            return "{\"type\":\"event\",\"event\":\"ui.tree\",\"treeVersion\":" + treeVersion +
                    ",\"eventSeq\":" + captureEndEventSeq +
                    metadataJson() +
                    ",\"capturedAt\":\"" + escape(capturedAt) +
                    "\",\"actionId\":\"" + escape(actionId) +
                    "\",\"reason\":\"" + escape(reason) +
                    "\",\"settled\":" + settled + ",\"timedOut\":" + timedOut +
                    ",\"changed\":false,\"treeDigest\":\"\",\"ok\":false,\"code\":\"" +
                    escape(errorCode) + "\"}";
        }

        String toEventJson(boolean settled, boolean timedOut, boolean changed) {
            return "{\"type\":\"event\",\"event\":\"ui.tree\",\"treeVersion\":" + treeVersion +
                    ",\"eventSeq\":" + captureEndEventSeq +
                    metadataJson() +
                    ",\"capturedAt\":\"" + escape(capturedAt) +
                    "\",\"actionId\":\"" + escape(actionId) +
                    "\",\"reason\":\"" + escape(reason) +
                    "\",\"settled\":" + settled +
                    ",\"timedOut\":" + timedOut +
                    ",\"changed\":" + changed +
                    ",\"treeDigest\":\"" + escape(treeDigest) +
                    "\",\"currentApp\":" + currentAppJson +
                    "," + treeJson.substring(1);
        }

        String toObservationTreeJson() {
            return "{\"ok\":true,\"treeVersion\":" + treeVersion + metadataJson() +
                    ",\"capturedAt\":\"" + escape(capturedAt) +
                    "\",\"treeDigest\":\"" + escape(treeDigest) +
                    "\",\"currentApp\":" + currentAppJson + "," + treeJson.substring(1);
        }

        private String metadataJson() {
            return ",\"serviceEpoch\":\"" + escape(serviceEpoch) +
                    "\",\"captureBeginEventSeq\":" + captureBeginEventSeq +
                    ",\"captureEndEventSeq\":" + captureEndEventSeq +
                    ",\"captureBeginElapsedRealtimeMs\":" + captureBeginElapsedRealtimeMs +
                    ",\"captureEndElapsedRealtimeMs\":" + captureEndElapsedRealtimeMs;
        }
    }

    private void registerWsSession(WsSession session) {
        synchronized (uiTreeStateLock) {
            session.latestRelevantEventSeq = uiEventSeq;
            session.latestRelevantEventElapsedRealtimeMs = lastRelevantUiEventAt;
            session.lastDirtySentSeq = uiEventSeq;
        }
        synchronized (wsSessionsLock) {
            wsSessions.add(session);
            activeWsSessions = wsSessions.size();
        }
        refreshConnectionStatusOverlay();
    }

    private void unregisterWsSession(WsSession session) {
        if (session == null) {
            return;
        }
        session.closeQuietly();
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay != null) overlay.releaseTaskProgress(session);
        synchronized (wsSessionsLock) {
            wsSessions.remove(session);
            activeWsSessions = wsSessions.size();
        }
        refreshConnectionStatusOverlay();
    }

    private void refreshConnectionStatusOverlay() {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay == null) {
            return;
        }
        boolean hasAuthenticatedSession = false;
        synchronized (wsSessionsLock) {
            for (WsSession candidate : wsSessions) {
                if (candidate.authenticated && !candidate.closed) {
                    hasAuthenticatedSession = true;
                    break;
                }
            }
        }
        overlay.setConnected(hasAuthenticatedSession);
    }

    private void noteControlActivity(String summary) {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay != null) {
            overlay.noteControlActivity(summary);
        }
    }

    private void noteObservation(String logicalId) {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay != null) overlay.showObservation(logicalId);
    }

    private void showPointGesture(int x, int y) {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay == null) return;
        long travelMs = overlay.showPoint(x, y);
        if (travelMs <= 0L) return;
        awaitCursorTravel(travelMs);
        overlay.commitPreparedAction();
    }

    private void showSwipeGesture(int x1, int y1, int x2, int y2, int durationMs) {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay == null) return;
        long travelMs = overlay.showSwipe(x1, y1, x2, y2, durationMs);
        if (travelMs <= 0L) return;
        awaitCursorTravel(travelMs);
        overlay.commitPreparedAction();
    }

    private void showBackGesture() {
        ConnectionStatusOverlay overlay = connectionStatusOverlay;
        if (overlay == null) return;
        Rect screen = screenBounds();
        long travelMs = overlay.showBack(screen.right, screen.centerY());
        if (travelMs <= 0L) return;
        awaitCursorTravel(travelMs);
        overlay.commitPreparedAction();
    }

    private void awaitCursorTravel(long durationMs) {
        if (durationMs <= 0L || Looper.myLooper() == Looper.getMainLooper()) return;
        sleep(Math.min(AgentVisualModel.CURSOR_MOVE_MAX_MS, durationMs));
    }

    private void removeWsSession(WsSession session) {
        unregisterWsSession(session);
    }

    private UiEventClassification classifyUiEvent(AccessibilityEvent event) {
        int eventType = event.getEventType();
        String packageName = str(event.getPackageName());
        String className = str(event.getClassName());
        if ("com.android.systemui".equals(packageName)
                && eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return UiEventClassification.noise(packageName, className);
        }
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return UiEventClassification.major(packageName, className);
        }
        return UiEventClassification.relevant(packageName, className);
    }

    private static final class UiEventClassification {
        final int kind;
        final String packageName;
        final String className;

        UiEventClassification(int kind, String packageName, String className) {
            this.kind = kind;
            this.packageName = packageName == null ? "" : packageName;
            this.className = className == null ? "" : className;
        }

        static UiEventClassification major(String packageName, String className) {
            return new UiEventClassification(UI_EVENT_MAJOR, packageName, className);
        }

        static UiEventClassification relevant(String packageName, String className) {
            return new UiEventClassification(UI_EVENT_RELEVANT, packageName, className);
        }

        static UiEventClassification noise(String packageName, String className) {
            return new UiEventClassification(UI_EVENT_NOISE, packageName, className);
        }
    }

    private static final class WsSession {
        final OutputStream out;
        final Object writeLock = new Object();
        volatile boolean authenticated;
        volatile boolean closed;
        volatile String sessionToken = "";
        volatile String sessionTokenId = "";
        volatile String lastTreeDigest = "";
        volatile long latestRelevantEventSeq;
        volatile long latestRelevantEventElapsedRealtimeMs;
        volatile long lastDirtySentSeq;
        volatile long lastControlActivityElapsedRealtimeMs;

        WsSession(OutputStream out) {
            this.out = out;
            this.lastControlActivityElapsedRealtimeMs = SystemClock.elapsedRealtime();
        }

        void touchControlActivity() {
            lastControlActivityElapsedRealtimeMs = SystemClock.elapsedRealtime();
        }

        void sendText(String json) throws IOException {
            synchronized (writeLock) {
                if (closed) {
                    return;
                }
                out.write(WebSocketProtocol.encodeTextFrame(json));
                out.flush();
            }
        }

        void sendFrame(byte[] frame) throws IOException {
            synchronized (writeLock) {
                if (closed) {
                    return;
                }
                out.write(frame);
                out.flush();
            }
        }

        void sendClose(int code, String reason) throws IOException {
            synchronized (writeLock) {
                if (closed) {
                    return;
                }
                out.write(WebSocketProtocol.encodeClose(code, reason));
                out.flush();
            }
        }

        void closeQuietly() {
            synchronized (writeLock) {
                closed = true;
                try {
                    out.flush();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class WsCallResult {
        final String id;
        final boolean ok;
        final String resultJson;
        final String code;
        final String message;
        final long durationMs;

        private WsCallResult(String id, boolean ok, String resultJson, String code, String message, long durationMs) {
            this.id = id == null ? "" : id;
            this.ok = ok;
            this.resultJson = resultJson == null || resultJson.isEmpty() ? "{}" : resultJson;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
            this.durationMs = durationMs;
        }

        static WsCallResult ok(String id, String resultJson, long durationMs) {
            return new WsCallResult(id, true, resultJson, "", "", durationMs);
        }

        static WsCallResult error(String id, String code, String message, long durationMs) {
            return new WsCallResult(id, false, "{}", code, message, durationMs);
        }

        String toFrameJson() {
            if (ok) {
                return "{\"type\":\"result\",\"id\":\"" + escape(id) + "\",\"ok\":true,\"result\":" +
                        resultJson + ",\"durationMs\":" + durationMs + "}";
            }
            return "{\"type\":\"error\",\"id\":\"" + escape(id) + "\",\"ok\":false,\"code\":\"" +
                    escape(code) + "\",\"message\":\"" + escape(message) + "\",\"durationMs\":" + durationMs + "}";
        }

        String toStepJson() {
            if (ok) {
                return "{\"id\":\"" + escape(id) + "\",\"ok\":true,\"result\":" + resultJson +
                        ",\"durationMs\":" + durationMs + "}";
            }
            return "{\"id\":\"" + escape(id) + "\",\"ok\":false,\"code\":\"" + escape(code) +
                    "\",\"message\":\"" + escape(message) + "\",\"durationMs\":" + durationMs + "}";
        }
    }

    private static final class ScreenshotCapture {
        byte[] bytes;
        String error;
        String captureId = "";
        String captureMode = "unknown";
        String boundsSource = "unavailable";
        int displayId = Display.DEFAULT_DISPLAY;
        int windowId = -1;
        int imageWidth;
        int imageHeight;
        int displayWidth;
        int displayHeight;
        boolean displayMetricsCertain;
        Rect captureBounds;
        CaptureCoordinateMapping mapping;
        long timestampElapsedRealtimeMs;
        long beginElapsedRealtimeMs;
        long endElapsedRealtimeMs;

        String coordinateJson() {
            if (mapping == null) {
                return "{\"captureId\":\"" + escape(captureId) + "\",\"mappingCertain\":false}";
            }
            return mapping.toJson();
        }

        Map<String, String> httpHeaders() {
            Map<String, String> headers = new LinkedHashMap<>();
            if (mapping == null) return headers;
            headers.put("X-Henyo-Capture-Id", mapping.captureId);
            headers.put("X-Henyo-Coordinate-Space", "screenshot");
            headers.put("X-Henyo-Image-Size", mapping.imageWidth + "x" + mapping.imageHeight);
            headers.put("X-Henyo-Display-Size", mapping.displayWidth + "x" + mapping.displayHeight);
            headers.put("X-Henyo-Capture-Bounds", mapping.bounds.left + "," + mapping.bounds.top + "," +
                    mapping.bounds.right + "," + mapping.bounds.bottom);
            headers.put("X-Henyo-Capture-Scale", mapping.scaleX + "," + mapping.scaleY);
            headers.put("X-Henyo-Mapping-Certain", Boolean.toString(mapping.certain));
            if (mapping.windowId >= 0) headers.put("X-Henyo-Window-Id", Integer.toString(mapping.windowId));
            headers.put("X-Henyo-Display-Id", Integer.toString(mapping.displayId));
            return headers;
        }
    }

    private static final class CaptureCoordinateMapping {
        final String captureId;
        final String captureMode;
        final int displayId;
        final int windowId;
        final int imageWidth;
        final int imageHeight;
        final int displayWidth;
        final int displayHeight;
        final Rect bounds;
        final double scaleX;
        final double scaleY;
        final boolean certain;
        final String boundsSource;
        final long createdElapsedRealtimeMs;

        CaptureCoordinateMapping(String captureId, String captureMode, int displayId, int windowId,
                                 int imageWidth, int imageHeight, int displayWidth, int displayHeight,
                                 Rect bounds, double scaleX, double scaleY, boolean certain,
                                 String boundsSource, long createdElapsedRealtimeMs) {
            this.captureId = captureId;
            this.captureMode = captureMode;
            this.displayId = displayId;
            this.windowId = windowId;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
            this.displayWidth = displayWidth;
            this.displayHeight = displayHeight;
            this.bounds = new Rect(bounds);
            this.scaleX = scaleX;
            this.scaleY = scaleY;
            this.certain = certain;
            this.boundsSource = boundsSource;
            this.createdElapsedRealtimeMs = createdElapsedRealtimeMs;
        }

        String toJson() {
            return "{\"captureId\":\"" + escape(captureId) + "\",\"coordinateSpace\":\"screenshot\"" +
                    ",\"captureMode\":\"" + escape(captureMode) + "\",\"displayId\":" + displayId +
                    ",\"windowId\":" + (windowId >= 0 ? Integer.toString(windowId) : "null") +
                    ",\"imageWidth\":" + imageWidth + ",\"imageHeight\":" + imageHeight +
                    ",\"displayWidth\":" + displayWidth + ",\"displayHeight\":" + displayHeight +
                    ",\"captureBoundsInScreen\":{\"left\":" + bounds.left + ",\"top\":" + bounds.top +
                    ",\"right\":" + bounds.right + ",\"bottom\":" + bounds.bottom + "}" +
                    ",\"scaleX\":" + scaleX + ",\"scaleY\":" + scaleY +
                    ",\"mappingCertain\":" + certain + ",\"boundsSource\":\"" + escape(boundsSource) + "\"}";
        }
    }

    private static final class CoordinateResolution {
        final boolean ok;
        final boolean transformed;
        final int x;
        final int y;
        final String error;

        private CoordinateResolution(boolean ok, boolean transformed, int x, int y, String error) {
            this.ok = ok;
            this.transformed = transformed;
            this.x = x;
            this.y = y;
            this.error = error;
        }

        static CoordinateResolution screen(int x, int y) {
            return new CoordinateResolution(true, false, x, y, "");
        }

        static CoordinateResolution transformed(int x, int y) {
            return new CoordinateResolution(true, true, x, y, "");
        }

        static CoordinateResolution error(String error) {
            return new CoordinateResolution(false, false, 0, 0, error);
        }

        String resultMetadata() {
            return transformed ? ",\"coordinateSpace\":\"screenshot\",\"screenX\":" + x +
                    ",\"screenY\":" + y : "";
        }
    }

    private static final class ScreenshotCallback implements TakeScreenshotCallback {
        private final ScreenshotCapture capture;
        private final CountDownLatch latch;
        private final Runnable onCaptured;

        ScreenshotCallback(ScreenshotCapture capture, CountDownLatch latch, Runnable onCaptured) {
            this.capture = capture;
            this.latch = latch;
            this.onCaptured = onCaptured;
        }

        @Override
        public void onSuccess(ScreenshotResult screenshot) {
            try {
                capture.timestampElapsedRealtimeMs = screenshot.getTimestamp();
                HardwareBuffer buffer = screenshot.getHardwareBuffer();
                Bitmap hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, screenshot.getColorSpace());
                if (hardwareBitmap == null) {
                    capture.error = "bitmap_wrap_failed";
                    return;
                }
                Bitmap bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                capture.imageWidth = bitmap.getWidth();
                capture.imageHeight = bitmap.getHeight();
                // The hardware buffer may still be backed by compositor state.
                // Keep the indicator suppressed until pixels are fixed in a
                // CPU bitmap, then restore it before the slower PNG encoding.
                onCaptured.run();
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                bitmap.recycle();
                hardwareBitmap.recycle();
                buffer.close();
                capture.bytes = out.toByteArray();
            } catch (Exception e) {
                capture.error = e.getClass().getSimpleName();
            } finally {
                onCaptured.run();
                latch.countDown();
            }
        }

        @Override
        public void onFailure(int errorCode) {
            onCaptured.run();
            capture.error = "take_screenshot_failed_" + errorCode;
            latch.countDown();
        }
    }
}
