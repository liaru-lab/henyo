package link.liaru.henyo;

public final class WindowRootFailureCacheTest {
    public static void main(String[] args) {
        failureIsRememberedTemporarily();
        changedWindowFingerprintInvalidatesFailure();
        regeneratedIdentityDoesNotReuseFailure();
        explicitClearAllowsRetry();
        System.out.println("WindowRootFailureCacheTest passed");
    }

    private static void failureIsRememberedTemporarily() {
        WindowRootFailureCache cache = new WindowRootFailureCache(100L);
        cache.record(4, 2, 1, 0, 0, 100, 100, 10L);
        expect(cache.shouldSkip(4, 2, 1, 0, 0, 100, 100, 50L),
                "a recent root failure should be skipped");
        expect(!cache.shouldSkip(4, 2, 1, 0, 0, 100, 100, 110L),
                "a root failure must expire");
    }

    private static void changedWindowFingerprintInvalidatesFailure() {
        WindowRootFailureCache cache = new WindowRootFailureCache(100L);
        cache.record(4, 2, 1, 0, 0, 100, 100, 10L);
        expect(!cache.shouldSkip(4, 2, 1, 0, 0, 200, 100, 50L),
                "changed bounds must allow a new lookup");
    }

    private static void regeneratedIdentityDoesNotReuseFailure() {
        WindowRootFailureCache cache = new WindowRootFailureCache(100L);
        cache.record(4, 2, 1, 0, 0, 100, 100, 10L);
        expect(!cache.shouldSkip(5, 2, 1, 0, 0, 100, 100, 50L),
                "a regenerated window id must not inherit a failure");
    }

    private static void explicitClearAllowsRetry() {
        WindowRootFailureCache cache = new WindowRootFailureCache(100L);
        cache.record(4, 2, 1, 0, 0, 100, 100, 10L);
        cache.clear(4, 2);
        expect(cache.size(50L) == 0, "clearing a window must remove its failure");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
