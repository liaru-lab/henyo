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
        System.out.println("WindowTargetResolverTest passed");
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
