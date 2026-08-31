package link.liaru.henyo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Orders window metadata before any potentially blocking accessibility-root lookup. */
public final class WindowProbePlanner {
    private WindowProbePlanner() {}

    public static List<Integer> order(List<WindowTargetResolver.Candidate> candidates,
                                      OperationTargetConstraint constraint,
                                      List<TargetHistoryStore.WindowHint> history,
                                      int preferredWindowId, int preferredDisplayId) {
        OperationTargetConstraint target = constraint == null
                ? OperationTargetConstraint.none() : constraint;
        List<TargetHistoryStore.WindowHint> hints = history == null
                ? new ArrayList<>() : history;
        List<Integer> ordered = new ArrayList<>();
        Set<Integer> added = new HashSet<>();
        if (!target.valid) return ordered;

        if (target.hasWindow()) {
            for (int i = 0; i < candidates.size(); i++) {
                WindowTargetResolver.Candidate candidate = candidates.get(i);
                if (probeEligible(candidate, target)
                        && candidate.windowId == target.windowId) {
                    ordered.add(i);
                    added.add(i);
                    if (!target.hasPackage()) return ordered;
                    break;
                }
            }
            if (!target.hasPackage() && ordered.isEmpty()) return ordered;
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
                int exact = exactHint(candidates, target, hint, added);
                if (exact >= 0) {
                    ordered.add(exact);
                    added.add(exact);
                }
            }
        }

        if (!target.hasPackage()) {
            int preferred = exactPreferred(candidates, target, preferredWindowId,
                    preferredDisplayId, added);
            if (preferred >= 0) {
                ordered.add(preferred);
                added.add(preferred);
            }
        }

        List<Integer> remaining = new ArrayList<>();
        for (int i = 0; i < candidates.size(); i++) {
            if (!added.contains(i) && probeEligible(candidates.get(i), target)) remaining.add(i);
        }
        remaining.sort((left, right) -> Integer.compare(
                probeScore(candidates.get(right), target, hints, preferredDisplayId),
                probeScore(candidates.get(left), target, hints, preferredDisplayId)));
        ordered.addAll(remaining);
        return ordered;
    }

    private static int exactHint(List<WindowTargetResolver.Candidate> candidates,
                                 OperationTargetConstraint target,
                                 TargetHistoryStore.WindowHint hint, Set<Integer> added) {
        for (int i = 0; i < candidates.size(); i++) {
            WindowTargetResolver.Candidate candidate = candidates.get(i);
            if (!added.contains(i) && probeEligible(candidate, target)
                    && candidate.windowId == hint.windowId
                    && candidate.displayId == hint.displayId) return i;
        }
        return -1;
    }

    private static int exactPreferred(List<WindowTargetResolver.Candidate> candidates,
                                      OperationTargetConstraint target, int preferredWindowId,
                                      int preferredDisplayId, Set<Integer> added) {
        for (int i = 0; i < candidates.size(); i++) {
            WindowTargetResolver.Candidate candidate = candidates.get(i);
            if (!added.contains(i) && probeEligible(candidate, target)
                    && candidate.windowId == preferredWindowId
                    && candidate.displayId == preferredDisplayId) return i;
        }
        return -1;
    }

    private static boolean probeEligible(WindowTargetResolver.Candidate candidate,
                                         OperationTargetConstraint target) {
        if (candidate == null || !candidate.application) return false;
        return !target.hasDisplay() || candidate.displayId == target.displayId;
    }

    private static int probeScore(WindowTargetResolver.Candidate candidate,
                                  OperationTargetConstraint target,
                                  List<TargetHistoryStore.WindowHint> hints,
                                  int preferredDisplayId) {
        int score = candidate.active ? 20_000 : 0;
        if (candidate.focused) score += 10_000;
        if (!target.hasDisplay() && candidate.displayId == preferredDisplayId) score += 5_000;
        score += Math.max(-1000, Math.min(1000, candidate.layer));
        if (target.hasPackage()) {
            for (TargetHistoryStore.WindowHint hint : hints) {
                if (hint == null || !target.packageName.equals(hint.packageName)) continue;
                if (candidate.displayId == hint.displayId) score += 100_000;
                long delta = Math.abs((long) candidate.left - hint.left)
                        + Math.abs((long) candidate.top - hint.top)
                        + Math.abs((long) candidate.right - hint.right)
                        + Math.abs((long) candidate.bottom - hint.bottom);
                score += (int) Math.max(0L, 50_000L - Math.min(50_000L, delta));
                break;
            }
        }
        return score;
    }
}
