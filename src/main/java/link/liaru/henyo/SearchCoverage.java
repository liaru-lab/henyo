package link.liaru.henyo;

/** An incomplete or unavailable tree cannot prove that an element is absent. */
final class SearchCoverage {
    private int remaining;
    private final int maxDepth;
    private boolean inspected;
    private boolean incomplete;

    SearchCoverage() { this(3000, 64); }
    SearchCoverage(int maxNodes, int maxDepth) {
        remaining = maxNodes;
        this.maxDepth = maxDepth;
    }

    boolean visit(int depth) {
        if (depth > maxDepth || remaining-- <= 0) {
            incomplete = true;
            return false;
        }
        inspected = true;
        return true;
    }

    void unavailable() { incomplete = true; }
    boolean hasBudget() {
        if (remaining > 0) return true;
        incomplete = true;
        return false;
    }
    boolean provesAbsence() { return inspected && !incomplete; }
}
