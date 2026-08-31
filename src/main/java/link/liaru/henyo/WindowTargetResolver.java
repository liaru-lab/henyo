package link.liaru.henyo;

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

        public Candidate(int windowId, int displayId, int layer, String packageName,
                         boolean application, boolean hasRoot, boolean active, boolean focused) {
            this.windowId = windowId;
            this.displayId = displayId;
            this.layer = layer;
            this.packageName = packageName;
            this.application = application;
            this.hasRoot = hasRoot;
            this.active = active;
            this.focused = focused;
        }
    }
}
