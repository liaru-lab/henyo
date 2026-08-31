package link.liaru.henyo;

public final class WindowRootProbeBudgetTest {
    public static void main(String[] args) {
        individualLookupsAreBounded();
        allUnavailableResolutionHasAnOverallBound();
        System.out.println("WindowRootProbeBudgetTest passed");
    }

    private static void individualLookupsAreBounded() {
        expect(WindowRootProbeBudget.nextTimeoutMs(1_000L, 1_000L) == 750L,
                "a single root lookup must use the short per-window timeout");
        expect(WindowRootProbeBudget.nextTimeoutMs(1_000L, 3_800L) == 200L,
                "the final lookup must be clipped to the remaining resolution budget");
    }

    private static void allUnavailableResolutionHasAnOverallBound() {
        expect(WindowRootProbeBudget.nextTimeoutMs(1_000L, 4_000L) == 0L,
                "unavailable candidates must not exceed the overall resolution budget");
        expect(WindowRootProbeBudget.RESOLUTION_TIMEOUT_MS < 10_000L,
                "resolution must finish before the normal client timeout");
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
