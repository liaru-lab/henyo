package link.liaru.henyo;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class WindowProbePlannerTest {
    public static void main(String[] args) {
        explicitDisplayFiltersBeforeRootLookup();
        nullRootCandidateCanFallThroughToNextProbe();
        recentWindowPrecedesFocusedUnknownWindow();
        staleWindowWithoutPackageDoesNotGuess();
        staleWindowWithPackageCanProbeForReplacement();
        reusedWindowIdWithPackageCanFallThrough();
        externalDisplayRemainsTargetable();
        System.out.println("WindowProbePlannerTest passed");
    }

    private static void explicitDisplayFiltersBeforeRootLookup() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(1, 9, 10, true, true),
                        window(2, 0, 1, false, false)),
                OperationTargetConstraint.exact("", -1, 0), Collections.emptyList(), 1, 9);
        expect(ordered.equals(Collections.singletonList(1)),
                "an explicit display must filter unrelated windows before probing roots");
    }

    private static void nullRootCandidateCanFallThroughToNextProbe() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(1, 9, 10, true, true),
                        window(2, 0, 1, false, false)),
                OperationTargetConstraint.none(), Collections.emptyList(), 1, 9);
        expect(ordered.equals(Arrays.asList(0, 1)),
                "planning must retain later candidates after the preferred window");
    }

    private static void recentWindowPrecedesFocusedUnknownWindow() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(7, 0, 1, false, false),
                        window(8, 2, 9, true, true)),
                OperationTargetConstraint.none(),
                Collections.singletonList(hint("com.example.recent", 7, 0)), 8, 2);
        expect(ordered.equals(Arrays.asList(0, 1)),
                "the most recent known window should be probed before unrelated focus");
    }

    private static void staleWindowWithoutPackageDoesNotGuess() {
        List<Integer> ordered = WindowProbePlanner.order(
                Collections.singletonList(window(8, 0, 1, true, true)),
                OperationTargetConstraint.exact("", 99, -1), Collections.emptyList(), 8, 0);
        expect(ordered.isEmpty(), "a stale explicit id without a package must not guess");
    }

    private static void staleWindowWithPackageCanProbeForReplacement() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(8, 0, 1, false, false),
                        window(9, 2, 2, true, true)),
                OperationTargetConstraint.exact("com.example.target", 99, -1),
                Collections.singletonList(hint("com.example.target", 99, 0)), 9, 2);
        expect(ordered.equals(Arrays.asList(0, 1)),
                "a package-qualified stale id may probe likely replacement windows");
    }

    private static void reusedWindowIdWithPackageCanFallThrough() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(99, 0, 3, true, true),
                        window(12, 0, 2, false, false)),
                OperationTargetConstraint.exact("com.example.target", 99, 0),
                Collections.singletonList(hint("com.example.target", 12, 0)), 99, 0);
        expect(ordered.equals(Arrays.asList(0, 1)),
                "a package-qualified id must fall through when the id was reused by another app");
    }

    private static void externalDisplayRemainsTargetable() {
        List<Integer> ordered = WindowProbePlanner.order(Arrays.asList(
                        window(1, 0, 3, true, true),
                        window(2, 116, 2, false, false)),
                OperationTargetConstraint.exact("", -1, 116),
                Collections.emptyList(), 1, 0);
        expect(ordered.equals(Collections.singletonList(1)),
                "a real application window on an external display must remain targetable");
    }

    private static WindowTargetResolver.Candidate window(int id, int display, int layer,
                                                           boolean active, boolean focused) {
        return new WindowTargetResolver.Candidate(id, display, layer, "", true, false,
                active, focused, 0, 0, 100, 100);
    }

    private static TargetHistoryStore.WindowHint hint(String packageName, int windowId,
                                                       int displayId) {
        return new TargetHistoryStore.WindowHint(packageName, windowId, displayId,
                0, 0, 100, 100);
    }

    private static void expect(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
