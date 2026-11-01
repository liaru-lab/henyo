package link.liaru.henyo;

/** Bounded inspection: only a complete traversal can establish CLEAR. */
final class SensitiveTreeInspector {
    enum Result { CLEAR, SENSITIVE, UNKNOWN }
    interface Access<N> {
        boolean sensitive(N node);
        int childCount(N node);
        N child(N node, int index);
        void recycle(N node);
    }
    private int remaining;
    private final int maxDepth;
    private SensitiveTreeInspector(int maxNodes, int maxDepth) {
        remaining = maxNodes; this.maxDepth = maxDepth;
    }
    static <N> Result inspect(N root, Access<N> access, int maxNodes, int maxDepth) {
        if (root == null || maxNodes < 1 || maxDepth < 0) return Result.UNKNOWN;
        return new SensitiveTreeInspector(maxNodes, maxDepth).visit(root, access, 0);
    }
    private <N> Result visit(N node, Access<N> access, int depth) {
        if (remaining-- <= 0 || node == null || depth > maxDepth) return Result.UNKNOWN;
        try {
            if (access.sensitive(node)) return Result.SENSITIVE;
            boolean unknown = false;
            int count = access.childCount(node);
            if (count < 0) return Result.UNKNOWN;
            for (int i = 0; i < count; i++) {
                if (remaining <= 0) return Result.UNKNOWN;
                N child = access.child(node, i);
                Result result;
                try { result = visit(child, access, depth + 1); }
                finally { if (child != null) access.recycle(child); }
                if (result == Result.SENSITIVE) return result;
                if (result == Result.UNKNOWN) unknown = true;
            }
            return unknown ? Result.UNKNOWN : Result.CLEAR;
        } catch (RuntimeException e) { return Result.UNKNOWN; }
    }
}
