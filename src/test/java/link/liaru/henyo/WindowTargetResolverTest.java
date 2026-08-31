package link.liaru.henyo;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

public final class WindowTargetResolverTest {
    public static void main(String[] args) {
        excludedActiveWindowFallsBackToPreferredApplication();
        activeEligibleWindowWins();
        sameDisplayWinsBeforeOtherDisplay();
        excludedWindowNeverBecomesTarget();
        recentPackageGroupsItsWindowsBeforeOlderPackages();
        staleWindowRebindsWithinKnownPackage();
        staleWindowWithoutPackageDoesNotGuess();
        existingExplicitWindowDoesNotSearchSiblingWindow();
        System.out.println("WindowTargetResolverTest passed");
    }

    private static void existingExplicitWindowDoesNotSearchSiblingWindow() {
        MapBuilder params = new MapBuilder().put("package", "com.example.target")
                .put("windowId", "8");
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(8, 0, 1, "com.example.target", true, false, false),
                window(9, 0, 2, "com.example.target", true, true, true));
        List<Integer> ordered = WindowTargetResolver.order(windows, Collections.emptySet(),
                OperationTargetConstraint.from(params.values), Collections.emptyList());
        expect(ordered.equals(Collections.singletonList(0)),
                "an existing explicit window must remain pinned to that window");
    }

    private static void recentPackageGroupsItsWindowsBeforeOlderPackages() {
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(1, 0, 1, "com.example.recent", true, false, false),
                window(2, 0, 2, "com.example.older", true, false, false),
                window(3, 0, 3, "com.example.recent", true, false, false));
        List<TargetHistoryStore.WindowHint> history = Arrays.asList(
                hint("com.example.recent", 1), hint("com.example.older", 2),
                hint("com.example.recent", 3));
        List<Integer> ordered = WindowTargetResolver.order(windows, Collections.emptySet(),
                OperationTargetConstraint.none(), history);
        expect(ordered.equals(Arrays.asList(0, 2, 1)),
                "all windows from the most recent package should precede older packages");
    }

    private static void staleWindowRebindsWithinKnownPackage() {
        MapBuilder params = new MapBuilder().put("package", "com.example.target")
                .put("windowId", "99");
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(7, 0, 2, "com.example.other", true, true, true),
                window(8, 0, 1, "com.example.target", true, false, false));
        List<Integer> ordered = WindowTargetResolver.order(windows, Collections.emptySet(),
                OperationTargetConstraint.from(params.values),
                Collections.singletonList(hint("com.example.target", 99)));
        expect(ordered.equals(Collections.singletonList(1)),
                "a stale id should rebind only inside its known package");
    }

    private static void staleWindowWithoutPackageDoesNotGuess() {
        MapBuilder params = new MapBuilder().put("windowId", "99");
        List<Integer> ordered = WindowTargetResolver.order(
                Collections.singletonList(window(8, 0, 1, "com.example.target", true, true, true)),
                Collections.emptySet(), OperationTargetConstraint.from(params.values),
                Collections.emptyList());
        expect(ordered.isEmpty(), "a stale id without a package must not select another window");
    }

    private static TargetHistoryStore.WindowHint hint(String packageName, int windowId) {
        return new TargetHistoryStore.WindowHint(packageName, windowId, 0, 0, 0, 100, 100);
    }

    private static final class MapBuilder {
        final java.util.Map<String, String> values = new java.util.HashMap<>();
        MapBuilder put(String key, String value) { values.put(key, value); return this; }
    }

    private static void excludedActiveWindowFallsBackToPreferredApplication() {
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(10, 0, 2, "com.example.target", true, false, false),
                window(20, 0, 9, "com.example.overlay", true, true, true));
        int selected = WindowTargetResolver.select(windows,
                new HashSet<>(Collections.singletonList("com.example.overlay")), 10, 0);
        expect(selected == 0, "preferred underlying application should be retained");
    }

    private static void activeEligibleWindowWins() {
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(10, 0, 2, "com.example.first", true, false, false),
                window(11, 0, 3, "com.example.second", true, true, true));
        expect(WindowTargetResolver.select(windows, Collections.emptySet(), 10, 0) == 1,
                "active eligible application should win");
    }

    private static void sameDisplayWinsBeforeOtherDisplay() {
        List<WindowTargetResolver.Candidate> windows = Arrays.asList(
                window(10, 0, 2, "com.example.local", true, false, false),
                window(30, 2, 8, "com.example.external", true, false, false));
        expect(WindowTargetResolver.select(windows, Collections.emptySet(), -1, 0) == 0,
                "fallback should stay on the preferred display");
    }

    private static void excludedWindowNeverBecomesTarget() {
        List<WindowTargetResolver.Candidate> windows = Collections.singletonList(
                window(20, 0, 9, "com.example.overlay", true, true, true));
        expect(WindowTargetResolver.select(windows,
                new HashSet<>(Collections.singletonList("com.example.overlay")), 20, 0) == -1,
                "excluded application must not be selected");
    }

    private static WindowTargetResolver.Candidate window(int id, int display, int layer,
                                                           String packageName, boolean application,
                                                           boolean active, boolean focused) {
        return new WindowTargetResolver.Candidate(id, display, layer, packageName,
                application, true, active, focused);
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
