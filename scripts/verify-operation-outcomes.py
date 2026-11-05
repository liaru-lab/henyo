#!/usr/bin/env python3
"""Exercise request lifetimes and the production edit/wait decisions."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = (root / "src/main/java/link/liaru/henyo/HenyoAccessibilityService.java").read_text()

def section(start, end):
    return source[source.index(start):source.index(end, source.index(start))]

methods = section("    private Response waitText(", "    private Response targetConstraintError(")
options = section("    private static final class SearchOptions", "    private static final class Match")
results = section("    private static final class WsCallResult", "    private static final class ScreenshotCapture")
harness = r'''
package link.liaru.henyo;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public final class OperationOutcomesHarness {
    Match selected, focused;
    int selectedLookups, focusedLookups;
    boolean unavailable, dispatchAllowed = true, actionAccepted = true;

    public static void main(String[] args) {
        long start = 1_000_000_000L;
        OperationDeadline lifetime = new OperationDeadline(start, 10);
        check(!lifetime.expired(start + 9_999_999L), "before deadline");
        check(lifetime.expired(start + 10_000_000L), "at deadline");
        check(lifetime.limitedFrom(start, 100).expired(start + 10_000_000L), "child cannot extend lifetime");
        check(lifetime.limitedFrom(start, 2).expired(start + 2_000_000L), "child may tighten lifetime");
        long nearWrap = Long.MAX_VALUE - 100;
        OperationDeadline wrapped = new OperationDeadline(nearWrap, 1);
        check(!wrapped.expired(nearWrap), "monotonic wrap before deadline");
        check(wrapped.expired(nearWrap + 1_000_000L), "monotonic wrap at deadline");

        SearchCoverage empty = new SearchCoverage();
        check(!empty.provesAbsence(), "unavailable tree cannot prove absence");
        SearchCoverage complete = new SearchCoverage(2, 2);
        complete.visit(0); complete.visit(1);
        check(complete.provesAbsence(), "fully visited tree proves absence");
        check(!complete.hasBudget() && !complete.provesAbsence(), "unvisited remaining nodes make search unknown");
        SearchCoverage missingChild = new SearchCoverage();
        missingChild.visit(0); missingChild.unavailable();
        check(!missingChild.provesAbsence(), "missing child is unknown");
        SearchCoverage deep = new SearchCoverage(10, 2);
        deep.visit(0); deep.visit(1); deep.visit(2);
        check(!deep.visit(3) && !deep.provesAbsence(), "depth bound is not absence");

        OperationOutcomesHarness service = new OperationOutcomesHarness();
        service.focused = new Match("unchanged");
        expect("not_found", () -> service.setText(Map.of("text", "missing", "value", "synthetic")));
        check(service.focusedLookups == 0 && service.focused.node.saved.equals("unchanged"),
                "explicit selector cannot edit focused fallback");
        expect("not_found", () -> service.setText(Map.of("target", "missing", "value", "synthetic")));
        check(service.focusedLookups == 0, "legacy target alias cannot fall back");

        service.selected = new Match("selected");
        Response applied = service.setText(Map.of("text", "selected", "value", "synthetic"));
        check(applied.status == 200 && service.selected.node.saved.equals("synthetic"), "selected edit works");
        check(service.selected.recycled, "selected node released");
        int lookups = service.selectedLookups;
        service.setText(Map.of("value", "focused"));
        check(service.selectedLookups == lookups && service.focused.node.saved.equals("focused"),
                "selector-free edits use focus only");

        service.setText(Map.of("focused", "true", "value", "explicit focus"));
        check(service.focused.node.saved.equals("explicit focus"), "explicit focus works");
        expect("invalid_input_target", () -> service.setText(Map.of("focused", "true", "text", "selected")));
        expect("invalid_input_target", () -> service.setText(Map.of("focused", "true", "target", "selected")));
        expect("invalid_input_target", () -> service.setText(Map.of("focused", "invalid")));
        check(service.focused.node.saved.equals("explicit focus"), "invalid modes do not edit focused field");
        service.focused = new Match("explicit focus");
        service.setText(Map.of("focused", "true", "value", " suffix", "clear", "false"));
        check(service.focused.node.saved.equals("explicit focus suffix"), "append reads text from editable control");
        service.focused = null;
        expect("no_focused_editable", () -> service.setText(Map.of("focused", "true", "value", "synthetic")));
        service.selected = new Match("unchanged");
        service.actionAccepted = false;
        expect("set_text_rejected", () -> service.setText(Map.of("text", "selected", "value", "synthetic")));
        check(service.selected.recycled && service.selected.node.saved.equals("unchanged"), "rejected edit releases node");
        service.actionAccepted = true;
        service.selected = new Match("unchanged");
        service.dispatchAllowed = false;
        try {
            service.setText(Map.of("text", "selected", "value", "late"));
            throw new AssertionError("expired dispatch applied");
        } catch (OperationFailure expected) {
            check(service.selected.recycled && service.selected.node.saved.equals("unchanged"),
                    "expired edit releases node without mutation");
        }
        service.dispatchAllowed = true;
        service.unavailable = true;
        try {
            service.waitText(Map.of("text", "missing"), true);
            throw new AssertionError("unavailable tree counted as gone");
        } catch (OperationFailure expected) {
            check(expected.code.equals("tree_unavailable"), "unavailable outcome retained");
        }
        service.unavailable = false;
        Response gone = service.waitText(Map.of("text", "missing"), true);
        check(gone.status == 200 && gone.body.contains("\"gone\":true"), "confirmed absence succeeds");
        ConditionalWorkflow.Result partial = new ConditionalWorkflow.Result("needs_agent", "workflow_needs_agent", 2, 1, true, java.util.Collections.emptyList());
        WsCallResult workflow = WsCallResult.workflow("synthetic", partial, 1);
        check(!workflow.ok && workflow.toFrameJson().contains("\"actionsAccepted\":1"), "partial workflow result retained");
        check(workflow.toFrameJson().contains("\"outcome\":\"unknown\""), "partial workflow outcome unknown");
        WsCallResult failure = WsCallResult.failure("synthetic", "deadline_exceeded", "not_applied", 1);
        check(failure.toFrameJson().contains("\"outcome\":\"not_applied\""), "call outcome serialized");
        check(failure.toStepJson().contains("\"outcome\":\"not_applied\""), "batch outcome serialized");
        System.out.println("OperationOutcomesHarness passed");
    }

    static void expect(String code, Runnable operation) {
        try { operation.run(); throw new AssertionError("expected " + code); }
        catch (OperationFailure failure) { check(failure.code.equals(code), "failure code " + code); }
    }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
    static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
    static boolean boolParam(Map<String,String> p, String k, boolean d) {
        return p.containsKey(k) ? Boolean.parseBoolean(p.get(k)) : d;
    }
    static int intParam(Map<String,String> p, String k, int d) {
        return p.containsKey(k) ? Integer.parseInt(p.get(k)) : d;
    }
    static Map<String,String> aliasParam(Map<String,String> p, String k, String v) {
        Map<String,String> copy = new HashMap<>(p); copy.put("text", v); return copy;
    }
    Match findEditableMatch(SearchOptions o, OperationTargetConstraint c) { selectedLookups++; return selected; }
    Match findFocusedEditable(OperationTargetConstraint c) { focusedLookups++; return focused; }
    Match findMatch(SearchOptions o, OperationTargetConstraint c) {
        if (unavailable) throw new OperationFailure("tree_unavailable", 503, "not_applied");
        return null;
    }
    void checkDeadline() { checkControl(); }
    void checkControl() { if (!dispatchAllowed) throw new OperationFailure("deadline_exceeded", 408, "not_applied"); }
    void showPointGesture(int x, int y) {}
    boolean controlledNodeAction(AccessibilityNodeInfo n, int a, Bundle b) { if (actionAccepted) n.saved=b.text.toString(); return actionAccepted; }
    Response targetConstraintError(OperationTargetConstraint c) { return json(400, "{}"); }
    String visibleTextsJson(int limit, boolean redact) { return "[]"; }
    static void sleep(long ms) { SystemClock.now += ms; }
    static Response json(int status, String body) { return new Response(status, body); }
    static final class Response {
        final int status; final String body;
        Response(int status, String body) { this.status=status; this.body=body; }
    }
    static final class Bundle {
        CharSequence text;
        void putCharSequence(String key, CharSequence text) { this.text=text; }
    }
    static final class AccessibilityNodeInfo {
        static final String ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE = "text";
        static final int ACTION_SET_TEXT = 1;
        String saved;
    }
    static final class SystemClock {
        static long now;
        static long elapsedRealtime() { return now; }
    }
    static final class Target { String toJson() { return "{}"; } }
    static final class Match {
        final AccessibilityNodeInfo node = new AccessibilityNodeInfo();
        final String text;
        Target target;
        boolean recycled;
        Match(String text) { this.text=text; node.saved=text; }
        int centerX() { return 0; }
        int centerY() { return 0; }
        String toJson(boolean redact) { return "{}"; }
        void recycle() { recycled=true; }
    }
'''

with tempfile.TemporaryDirectory(prefix="henyo-outcomes-") as directory:
    path = Path(directory) / "OperationOutcomesHarness.java"
    path.write_text(harness + methods + options + results + "\n}\n")
    production = root / "src/main/java/link/liaru/henyo"
    subprocess.run(["javac", "--release", "11", "-d", directory,
                    *[str(production / name) for name in (
                        "OperationDeadline.java", "OperationFailure.java", "SearchCoverage.java", "ConditionalWorkflow.java",
                        "OperationTargetConstraint.java", "WindowTargetResolver.java",
                        "TargetHistoryStore.java")], str(path)], check=True)
    subprocess.run(["java", "-cp", directory, "link.liaru.henyo.OperationOutcomesHarness"], check=True)
