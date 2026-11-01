package link.liaru.henyo;

import java.util.ArrayList;
import java.util.List;

public final class SensitiveTreeInspectorTest {
    static class Node { boolean sensitive, fail; int reported = -1; List<Node> children = new ArrayList<>(); }
    static final class Access implements SensitiveTreeInspector.Access<Node> {
        int acquired, recycled;
        public boolean sensitive(Node node) { if (node.fail) throw new IllegalStateException(); return node.sensitive; }
        public int childCount(Node node) { return node.reported >= 0 ? node.reported : node.children.size(); }
        public Node child(Node node, int index) { acquired++; return index < node.children.size() ? node.children.get(index) : null; }
        public void recycle(Node node) { recycled++; }
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        Node root = new Node(), child = new Node(); root.children.add(child);
        Access access = new Access();
        check(SensitiveTreeInspector.inspect(root, access, 2, 1) == SensitiveTreeInspector.Result.CLEAR, "complete traversal proves clear");
        check(access.acquired == 1 && access.recycled == 1, "caller retains root and inspector recycles children");
        child.sensitive = true; access = new Access();
        check(SensitiveTreeInspector.inspect(root, access, 2, 1) == SensitiveTreeInspector.Result.SENSITIVE && access.recycled == 1, "sensitive child is classified and recycled");
        check(SensitiveTreeInspector.inspect(null, access, 2, 1) == SensitiveTreeInspector.Result.UNKNOWN, "missing root is unknown");
        check(SensitiveTreeInspector.inspect(root, access, 1, 1) == SensitiveTreeInspector.Result.UNKNOWN, "budget cannot prove absence");
        check(SensitiveTreeInspector.inspect(root, access, 2, 0) == SensitiveTreeInspector.Result.UNKNOWN, "depth limit cannot prove absence");
        child.sensitive = false; child.fail = true; access = new Access();
        check(SensitiveTreeInspector.inspect(root, access, 2, 1) == SensitiveTreeInspector.Result.UNKNOWN && access.recycled == 1, "failed inspection still releases child");
        root.children.clear(); root.children.add(null);
        check(SensitiveTreeInspector.inspect(root, new Access(), 3, 1) == SensitiveTreeInspector.Result.UNKNOWN, "missing child is unknown");
        child.fail = false; child.sensitive = true; root.children.add(child);
        check(SensitiveTreeInspector.inspect(root, new Access(), 3, 1) == SensitiveTreeInspector.Result.SENSITIVE, "known sensitive child dominates another unavailable child");
        root.children.clear(); root.reported = Integer.MAX_VALUE; access = new Access();
        check(SensitiveTreeInspector.inspect(root, access, 2000, 50) == SensitiveTreeInspector.Result.UNKNOWN && access.acquired < 2000, "unavailable child attempts also consume the budget");
        root.sensitive = true; access = new Access();
        check(SensitiveTreeInspector.inspect(root, access, 1, 0) == SensitiveTreeInspector.Result.SENSITIVE && access.acquired == 0, "sensitive root needs no descendant reads");
        check(BearerAuthPolicy.requiresControlScope("/v1/ui/tree") && BearerAuthPolicy.requiresControlScope("/v1/app/current")
                && BearerAuthPolicy.requiresControlScope("/ui/find-text") && BearerAuthPolicy.requiresControlScope("/v1/screen/screenshot"), "UI routes require control scope");
        check(!BearerAuthPolicy.requiresControlScope("/v1/adb/wireless-endpoint") && !BearerAuthPolicy.requiresControlScope("/v1/health"), "dedicated scopes remain independent");
        check(!SensitiveUiAccessPolicy.allows(SensitiveTreeInspector.Result.UNKNOWN,true,false), "unknown inspection is denied without scope");
        check(!SensitiveUiAccessPolicy.allows(SensitiveTreeInspector.Result.SENSITIVE,true,false), "sensitive inspection is denied without scope");
        check(SensitiveUiAccessPolicy.allows(SensitiveTreeInspector.Result.CLEAR,true,false), "complete clear inspection is allowed");
        check(SensitiveUiAccessPolicy.allows(SensitiveTreeInspector.Result.UNKNOWN,false,false), "local trust boundary remains unchanged");
        check(SensitiveUiAccessPolicy.allows(SensitiveTreeInspector.Result.UNKNOWN,true,true), "explicit protected-control scope permits unknown inspection");
        System.out.println("SensitiveTreeInspectorTest passed");
    }
}
