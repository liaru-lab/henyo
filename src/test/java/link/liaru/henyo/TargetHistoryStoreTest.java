package link.liaru.henyo;

import java.util.List;

public final class TargetHistoryStoreTest {
    public static void main(String[] args) {
        reconnectWithinGraceKeepsHistory();
        disconnectedHistoryExpiresAfterGrace();
        recordingSameWindowMovesItToFront();
        System.out.println("TargetHistoryStoreTest passed");
    }

    private static void reconnectWithinGraceKeepsHistory() {
        TargetHistoryStore store = new TargetHistoryStore(600_000L, 4);
        store.connect("client", 0L);
        store.record("client", hint(1), 1L);
        store.disconnect("client", 2L);
        store.connect("client", 599_999L);
        expect(store.hints("client", 700_000L).size() == 1,
                "a reconnect inside the grace period should retain history");
    }

    private static void disconnectedHistoryExpiresAfterGrace() {
        TargetHistoryStore store = new TargetHistoryStore(600_000L, 4);
        store.connect("client", 0L);
        store.record("client", hint(1), 1L);
        store.disconnect("client", 2L);
        expect(store.hints("client", 600_002L).isEmpty(),
                "history should expire ten minutes after the final disconnect");
    }

    private static void recordingSameWindowMovesItToFront() {
        TargetHistoryStore store = new TargetHistoryStore(600_000L, 4);
        store.record("client", hint(1), 0L);
        store.record("client", hint(2), 1L);
        store.record("client", hint(1), 2L);
        List<TargetHistoryStore.WindowHint> hints = store.hints("client", 3L);
        expect(hints.size() == 2 && hints.get(0).windowId == 1,
                "a reused window should move to the front without duplication");
    }

    private static TargetHistoryStore.WindowHint hint(int windowId) {
        return new TargetHistoryStore.WindowHint("com.example.app", windowId, 0,
                0, 0, 100, 100);
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
