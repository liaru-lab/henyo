package link.liaru.henyo;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/** Resolves text targets without inferring relationships from screen proximity. */
final class TextInputTargetResolver {
    private final Predicate<AccessibilityNodeInfo> matches;
    private final Runnable checkDeadline;
    private final int windowId;
    private final SearchCoverage coverage;
    private final List<AccessibilityNodeInfo> candidates = new ArrayList<>();
    private boolean selectorMatched;

    TextInputTargetResolver(Predicate<AccessibilityNodeInfo> matches, Runnable checkDeadline,
            int windowId) {
        this(matches, checkDeadline, windowId, new SearchCoverage());
    }

    TextInputTargetResolver(Predicate<AccessibilityNodeInfo> matches, Runnable checkDeadline,
            int windowId, SearchCoverage coverage) {
        this.matches = matches;
        this.checkDeadline = checkDeadline;
        this.windowId = windowId;
        this.coverage = coverage;
    }

    /** Returns one owned node, or null if the selector is absent. */
    AccessibilityNodeInfo resolve(AccessibilityNodeInfo root) {
        try {
            visit(root, 0);
            if (!coverage.provesAbsence())
                throw new OperationFailure("tree_unavailable", 503, "not_applied");
            if (candidates.isEmpty()) {
                if (selectorMatched) throw new OperationFailure("not_editable", 409, "not_applied");
                return null;
            }
            return AccessibilityNodeInfo.obtain(candidates.get(0));
        } finally {
            for (AccessibilityNodeInfo candidate : candidates) candidate.recycle();
            candidates.clear();
        }
    }

    private void visit(AccessibilityNodeInfo node, int depth) {
        checkDeadline.run();
        if (!coverage.visit(depth)) return;
        if (node.getWindowId() != windowId)
            throw new OperationFailure("stale_target", 409, "not_applied");
        if (matches.test(node)) {
            selectorMatched = true;
            addOwner(node);
            AccessibilityNodeInfo labeled = node.getLabelFor();
            if (labeled != null) {
                try { addOwner(labeled); }
                finally { labeled.recycle(); }
            }
        }
        // Some accessibility providers expose only the reverse label relationship.
        if (node.isEditable()) {
            AccessibilityNodeInfo label = node.getLabeledBy();
            if (label != null) {
                try {
                    requireWindow(label);
                    if (matches.test(label)) {
                        selectorMatched = true;
                        add(node);
                    }
                } finally { label.recycle(); }
            }
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (!coverage.hasBudget()) break;
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) { coverage.unavailable(); continue; }
            try { visit(child, depth + 1); }
            finally { child.recycle(); }
        }
    }

    private void addOwner(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = AccessibilityNodeInfo.obtain(node);
        try {
            for (int depth = 0; current != null; depth++) {
                checkDeadline.run();
                if (depth > 64) { coverage.unavailable(); return; }
                requireWindow(current);
                if (current.isEditable()) { add(current); return; }
                AccessibilityNodeInfo parent = current.getParent();
                current.recycle();
                current = parent;
            }
        } finally { if (current != null) current.recycle(); }
    }

    private void requireWindow(AccessibilityNodeInfo node) {
        if (node.getWindowId() != windowId)
            throw new OperationFailure("stale_target", 409, "not_applied");
    }

    private void add(AccessibilityNodeInfo node) {
        for (AccessibilityNodeInfo candidate : candidates) if (candidate.equals(node)) return;
        candidates.add(AccessibilityNodeInfo.obtain(node));
        if (candidates.size() > 1)
            throw new OperationFailure("ambiguous_target", 409, "not_applied");
    }
}
