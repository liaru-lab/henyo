package link.liaru.henyo;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Short-lived memory of accessibility windows whose roots could not be retrieved. */
public final class WindowRootFailureCache {
    public static final long DEFAULT_TTL_MS = 30_000L;
    private static final int MAX_ENTRIES = 64;

    private final long ttlMs;
    private final Map<Key, Entry> entries = new HashMap<>();

    public WindowRootFailureCache() {
        this(DEFAULT_TTL_MS);
    }

    WindowRootFailureCache(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    public synchronized boolean shouldSkip(int windowId, int displayId, int layer,
                                           int left, int top, int right, int bottom, long nowMs) {
        pruneLocked(nowMs);
        Entry entry = entries.get(new Key(windowId, displayId));
        return entry != null && entry.matches(layer, left, top, right, bottom);
    }

    public synchronized void record(int windowId, int displayId, int layer,
                                    int left, int top, int right, int bottom, long nowMs) {
        pruneLocked(nowMs);
        if (entries.size() >= MAX_ENTRIES) {
            Iterator<Key> iterator = entries.keySet().iterator();
            if (iterator.hasNext()) entries.remove(iterator.next());
        }
        entries.put(new Key(windowId, displayId),
                new Entry(layer, left, top, right, bottom, nowMs + ttlMs));
    }

    public synchronized void clear(int windowId, int displayId) {
        entries.remove(new Key(windowId, displayId));
    }

    public synchronized void clearAll() {
        entries.clear();
    }

    synchronized int size(long nowMs) {
        pruneLocked(nowMs);
        return entries.size();
    }

    private void pruneLocked(long nowMs) {
        for (Iterator<Map.Entry<Key, Entry>> iterator = entries.entrySet().iterator();
             iterator.hasNext();) {
            if (iterator.next().getValue().expiresAtMs <= nowMs) iterator.remove();
        }
    }

    private static final class Key {
        final int windowId;
        final int displayId;

        Key(int windowId, int displayId) {
            this.windowId = windowId;
            this.displayId = displayId;
        }

        @Override
        public boolean equals(Object value) {
            if (!(value instanceof Key)) return false;
            Key other = (Key) value;
            return windowId == other.windowId && displayId == other.displayId;
        }

        @Override
        public int hashCode() {
            return 31 * windowId + displayId;
        }
    }

    private static final class Entry {
        final int layer;
        final int left;
        final int top;
        final int right;
        final int bottom;
        final long expiresAtMs;

        Entry(int layer, int left, int top, int right, int bottom, long expiresAtMs) {
            this.layer = layer;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.expiresAtMs = expiresAtMs;
        }

        boolean matches(int candidateLayer, int candidateLeft, int candidateTop,
                        int candidateRight, int candidateBottom) {
            return layer == candidateLayer && left == candidateLeft && top == candidateTop
                    && right == candidateRight && bottom == candidateBottom;
        }
    }
}
