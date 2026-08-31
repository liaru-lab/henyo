package link.liaru.henyo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WindowTargetResolver {
    private WindowTargetResolver() {}

    public static int select(List<Candidate> candidates, Set<String> excludedPackages,
                             int preferredWindowId, int preferredDisplayId) {
        int active = best(candidates, excludedPackages, preferredDisplayId, true, false);
        if (active >= 0) return active;

        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (candidate.windowId == preferredWindowId
                    && candidate.displayId == preferredDisplayId
                    && eligible(candidate, excludedPackages)) return i;
        }

        int sameDisplay = best(candidates, excludedPackages, preferredDisplayId, false, true);
        if (sameDisplay >= 0) return sameDisplay;
        return best(candidates, excludedPackages, -1, false, false);
    }

    /**
     * Returns eligible candidate indices in operation order. A stale explicit window may be
     * rebound only when its package is also known.
     */
    public static List<Integer> order(List<Candidate> candidates, Set<String> excludedPackages,
                                      OperationTargetConstraint constraint,
                                      List<TargetHistoryStore.WindowHint> history) {
        OperationTargetConstraint target = constraint == null
                ? OperationTargetConstraint.none() : constraint;
        List<TargetHistoryStore.WindowHint> hints = history == null
                ? new ArrayList<>() : history;
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> added = new HashSet<>();

        if (!target.valid) return ordered;
        if (target.hasWindow()) {
            for (int i = 0; i < candidates.size(); i++) {
                Candidate candidate = candidates.get(i);
                if (candidate.windowId == target.windowId && target.accepts(candidate)
                        && eligible(candidate, excludedPackages)) {
                    ordered.add(i);
                    added.add(i);
                }
            }
            if (!ordered.isEmpty()) return ordered;
            if (!target.hasPackage()) return ordered;
        }

        List<String> recentPackages = new ArrayList<>();
        if (target.hasPackage()) recentPackages.add(target.packageName);
        for (TargetHistoryStore.WindowHint hint : hints) {
            if (hint == null || hint.packageName.isEmpty()) continue;
            if (target.hasPackage() && !target.packageName.equals(hint.packageName)) continue;
            if (!recentPackages.contains(hint.packageName)) recentPackages.add(hint.packageName);
        }

        for (String packageName : recentPackages) {
            for (TargetHistoryStore.WindowHint hint : hints) {
                if (hint == null || !packageName.equals(hint.packageName)) continue;
                int exact = exactWindow(candidates, excludedPackages, target, hint, added);
                if (exact >= 0) {
                    ordered.add(exact);
                    added.add(exact);
                }
            }
            addPackageCandidates(ordered, added, candidates, excludedPackages, target,
                    packageName, hints);
        }

        if (target.hasPackage()) {
            addPackageCandidates(ordered, added, candidates, excludedPackages, target,
                    target.packageName, hints);
            return ordered;
        }

        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!added.contains(i) && eligible(candidates.get(i), excludedPackages)
                    && target.accepts(candidates.get(i))) remaining.add(i);
        }
        sortByFallback(remaining, candidates);
        ordered.addAll(remaining);
        return ordered;
    }

    private static int exactWindow(List<Candidate> candidates, Set<String> excludedPackages,
                                   OperationTargetConstraint target,
                                   TargetHistoryStore.WindowHint hint, Set<Integer> added) {
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!added.contains(i) && candidate.windowId == hint.windowId
                    && candidate.displayId == hint.displayId
                    && candidate.packageName.equals(hint.packageName)
                    && target.accepts(candidate) && eligible(candidate, excludedPackages)) return i;
        }
        return -1;
    }

    private static void addPackageCandidates(List<Integer> ordered, Set<Integer> added,
                                             List<Candidate> candidates, Set<String> excludedPackages,
                                             OperationTargetConstraint target, String packageName,
                                             List<TargetHistoryStore.WindowHint> hints) {
        List<Integer> matching = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!added.contains(i) && packageName.equals(candidate.packageName)
                    && target.accepts(candidate) && eligible(candidate, excludedPackages)) matching.add(i);
        }
        matching.sort((left, right) -> Integer.compare(
                recoveryScore(candidates.get(right), packageName, hints),
                recoveryScore(candidates.get(left), packageName, hints)));
        for (int index : matching) {
            ordered.add(index);
            added.add(index);
        }
    }

    private static int recoveryScore(Candidate candidate, String packageName,
                                     List<TargetHistoryStore.WindowHint> hints) {
        int score = candidate.active ? 20_000 : 0;
        if (candidate.focused) score += 10_000;
        score += Math.max(-1000, Math.min(1000, candidate.layer));
        for (TargetHistoryStore.WindowHint hint : hints) {
            if (hint == null || !packageName.equals(hint.packageName)) continue;
            if (candidate.displayId == hint.displayId) score += 100_000;
            long delta = Math.abs((long) candidate.left - hint.left)
                    + Math.abs((long) candidate.top - hint.top)
                    + Math.abs((long) candidate.right - hint.right)
                    + Math.abs((long) candidate.bottom - hint.bottom);
            score += (int) Math.max(0L, 50_000L - Math.min(50_000L, delta));
            break;
        }
        return score;
    }

    private static void sortByFallback(List<Integer> indices, List<Candidate> candidates) {
        indices.sort((left, right) -> {
            Candidate a = candidates.get(left);
            Candidate b = candidates.get(right);
            int aState = (a.active ? 2 : 0) + (a.focused ? 1 : 0);
            int bState = (b.active ? 2 : 0) + (b.focused ? 1 : 0);
            if (aState != bState) return Integer.compare(bState, aState);
            return Integer.compare(b.layer, a.layer);
        });
    }

    private static int best(List<Candidate> candidates, Set<String> excludedPackages,
                            int displayId, boolean requireActive, boolean requireDisplay) {
        int selected = -1;
        int selectedLayer = Integer.MIN_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            Candidate candidate = candidates.get(i);
            if (!eligible(candidate, excludedPackages)) continue;
            if (requireDisplay && candidate.displayId != displayId) continue;
            if (requireActive && candidate.displayId != displayId) continue;
            if (requireActive && !candidate.active && !candidate.focused) continue;
            if (selected < 0 || candidate.layer > selectedLayer) {
                selected = i;
                selectedLayer = candidate.layer;
            }
        }
        return selected;
    }

    private static boolean eligible(Candidate candidate, Set<String> excludedPackages) {
        return candidate != null && candidate.application && candidate.hasRoot
                && candidate.packageName != null && !candidate.packageName.isEmpty()
                && !excludedPackages.contains(candidate.packageName);
    }

    public static final class Candidate {
        public final int windowId;
        public final int displayId;
        public final int layer;
        public final String packageName;
        public final boolean application;
        public final boolean hasRoot;
        public final boolean active;
        public final boolean focused;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Candidate(int windowId, int displayId, int layer, String packageName,
                         boolean application, boolean hasRoot, boolean active, boolean focused) {
            this(windowId, displayId, layer, packageName, application, hasRoot, active, focused,
                    0, 0, 0, 0);
        }

        public Candidate(int windowId, int displayId, int layer, String packageName,
                         boolean application, boolean hasRoot, boolean active, boolean focused,
                         int left, int top, int right, int bottom) {
            this.windowId = windowId;
            this.displayId = displayId;
            this.layer = layer;
            this.packageName = packageName;
            this.application = application;
            this.hasRoot = hasRoot;
            this.active = active;
            this.focused = focused;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }
}
