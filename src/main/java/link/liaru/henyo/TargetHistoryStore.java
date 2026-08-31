package link.liaru.henyo;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Bounded in-memory target hints retained briefly across client reconnects. */
public final class TargetHistoryStore {
    public static final long DEFAULT_DISCONNECT_RETENTION_MS = 10L * 60L * 1000L;
    public static final int DEFAULT_WINDOW_LIMIT = 16;

    private final long disconnectRetentionMs;
    private final int windowLimit;
    private final Map<String, ContextHistory> contexts = new HashMap<>();

    public TargetHistoryStore() {
        this(DEFAULT_DISCONNECT_RETENTION_MS, DEFAULT_WINDOW_LIMIT);
    }

    TargetHistoryStore(long disconnectRetentionMs, int windowLimit) {
        this.disconnectRetentionMs = disconnectRetentionMs;
        this.windowLimit = windowLimit;
    }

    public synchronized void connect(String contextId, long nowMs) {
        expireLocked(nowMs);
        ContextHistory history = contexts.computeIfAbsent(normalize(contextId), ignored -> new ContextHistory());
        history.activeConnections++;
        history.disconnectedAtMs = -1L;
    }

    public synchronized void disconnect(String contextId, long nowMs) {
        expireLocked(nowMs);
        ContextHistory history = contexts.get(normalize(contextId));
        if (history == null) return;
        if (history.activeConnections > 0) history.activeConnections--;
        if (history.activeConnections == 0) history.disconnectedAtMs = nowMs;
    }

    public synchronized void record(String contextId, WindowHint hint, long nowMs) {
        if (hint == null || hint.windowId < 0 || hint.packageName.isEmpty()) return;
        expireLocked(nowMs);
        ContextHistory history = contexts.computeIfAbsent(normalize(contextId), ignored -> new ContextHistory());
        for (Iterator<WindowHint> iterator = history.windows.iterator(); iterator.hasNext();) {
            WindowHint existing = iterator.next();
            if (existing.sameWindow(hint)) iterator.remove();
        }
        history.windows.add(0, hint);
        while (history.windows.size() > windowLimit) history.windows.remove(history.windows.size() - 1);
        if (history.activeConnections == 0) history.disconnectedAtMs = nowMs;
    }

    public synchronized List<WindowHint> hints(String contextId, long nowMs) {
        expireLocked(nowMs);
        ContextHistory history = contexts.get(normalize(contextId));
        return history == null ? new ArrayList<>() : new ArrayList<>(history.windows);
    }

    public synchronized void clear(String contextId) {
        contexts.remove(normalize(contextId));
    }

    public synchronized void clearAll() {
        contexts.clear();
    }

    synchronized int contextCount(long nowMs) {
        expireLocked(nowMs);
        return contexts.size();
    }

    private void expireLocked(long nowMs) {
        for (Iterator<Map.Entry<String, ContextHistory>> iterator = contexts.entrySet().iterator(); iterator.hasNext();) {
            ContextHistory history = iterator.next().getValue();
            if (history.activeConnections == 0 && history.disconnectedAtMs >= 0
                    && nowMs - history.disconnectedAtMs >= disconnectRetentionMs) {
                iterator.remove();
            }
        }
    }

    private static String normalize(String contextId) {
        return contextId == null || contextId.isEmpty() ? "local" : contextId;
    }

    private static final class ContextHistory {
        final List<WindowHint> windows = new ArrayList<>();
        int activeConnections;
        long disconnectedAtMs = -1L;
    }

    public static final class WindowHint {
        public final String packageName;
        public final int windowId;
        public final int displayId;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public WindowHint(String packageName, int windowId, int displayId,
                          int left, int top, int right, int bottom) {
            this.packageName = packageName == null ? "" : packageName;
            this.windowId = windowId;
            this.displayId = displayId;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        boolean sameWindow(WindowHint other) {
            return other != null && windowId == other.windowId && displayId == other.displayId
                    && packageName.equals(other.packageName);
        }
    }
}
