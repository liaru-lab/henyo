package link.liaru.henyo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Android-free model for one ephemeral task-level progress presentation. */
final class TaskProgressModel {
    static final int MAX_COMPLETED = 3;
    static final int MAX_STEPS = 6;
    static final int MAX_TEXT_CODE_POINTS = 72;
    static final long REVEAL_MIN_MS = 120L;
    static final long REVEAL_MAX_MS = 700L;

    static final int KIND_GOAL = 0;
    static final int KIND_PENDING = 1;
    static final int KIND_IN_PROGRESS = 2;
    static final int KIND_COMPLETED = 3;

    static final String STATUS_PENDING = "pending";
    static final String STATUS_IN_PROGRESS = "in_progress";
    static final String STATUS_COMPLETED = "completed";

    static final int UPDATE_UNAVAILABLE = -3;
    static final int UPDATE_INVALID = -2;
    static final int UPDATE_PLAN_MISMATCH = -1;
    static final int UPDATE_UNCHANGED = 0;
    static final int UPDATE_CHANGED = 1;

    static final class Step {
        final String text;
        final String status;

        Step(String text, String status) {
            this.text = text;
            this.status = status;
        }
    }

    static final class Row {
        final int kind;
        final String text;
        final long revealStartedAtMs;
        final long revealDurationMs;

        Row(int kind, String text, long nowMs) {
            this(kind, text, nowMs, revealDuration(text));
        }

        private Row(int kind, String text, long revealStartedAtMs, long revealDurationMs) {
            this.kind = kind;
            this.text = text;
            this.revealStartedAtMs = revealStartedAtMs;
            this.revealDurationMs = revealDurationMs;
        }

        private static long revealDuration(String text) {
            int codePoints = text.codePointCount(0, text.length());
            return Math.min(REVEAL_MAX_MS,
                    Math.max(REVEAL_MIN_MS, codePoints * 20L));
        }

        Row withKind(int nextKind) {
            if (kind == nextKind) return this;
            return new Row(nextKind, text, revealStartedAtMs, revealDurationMs);
        }
    }

    private String goal = "";
    private String current = "";
    private List<String> completed = Collections.emptyList();
    private List<Step> planSteps = Collections.emptyList();
    private boolean structuredPlan;
    private List<Row> rows = Collections.emptyList();

    /** Replaces the full presentation and returns true when visible state changed. */
    boolean set(String rawGoal, List<String> rawCompleted, String rawCurrent, long nowMs) {
        String nextGoal = sanitize(rawGoal);
        String nextCurrent = sanitize(rawCurrent);
        List<String> nextCompleted = sanitizeCompleted(rawCompleted);
        if (goal.equals(nextGoal) && current.equals(nextCurrent)
                && completed.equals(nextCompleted)) return false;

        goal = nextGoal;
        current = nextCurrent;
        completed = Collections.unmodifiableList(nextCompleted);
        planSteps = Collections.emptyList();
        structuredPlan = false;
        List<Row> nextRows = new ArrayList<>();
        if (!goal.isEmpty()) nextRows.add(reuseOrCreate(KIND_GOAL, goal, nowMs));
        for (String milestone : completed) {
            nextRows.add(reuseOrCreate(KIND_COMPLETED, milestone, nowMs));
        }
        if (!current.isEmpty()) nextRows.add(reuseOrCreate(KIND_IN_PROGRESS, current, nowMs));
        rows = Collections.unmodifiableList(nextRows);
        return true;
    }

    /** Installs/replans a full plan or applies statuses to an identical plan. */
    int setPlan(String rawGoal, List<Step> rawSteps, boolean replan, long nowMs) {
        String nextGoal = sanitize(rawGoal);
        List<Step> nextSteps = sanitizeSteps(rawSteps);
        if (nextGoal.isEmpty() || nextSteps == null || nextSteps.isEmpty()) {
            return UPDATE_INVALID;
        }
        if (structuredPlan && !replan && !samePlanIdentity(nextGoal, nextSteps)) {
            return UPDATE_PLAN_MISMATCH;
        }
        if (structuredPlan && goal.equals(nextGoal) && sameSteps(planSteps, nextSteps)) {
            return UPDATE_UNCHANGED;
        }

        goal = nextGoal;
        current = firstInProgress(nextSteps);
        completed = Collections.emptyList();
        planSteps = Collections.unmodifiableList(nextSteps);
        structuredPlan = true;
        List<Row> nextRows = new ArrayList<>();
        nextRows.add(reuseOrCreate(KIND_GOAL, goal, nowMs));
        for (Step step : planSteps) {
            nextRows.add(reuseOrCreate(kindForStatus(step.status), step.text, nowMs));
        }
        rows = Collections.unmodifiableList(nextRows);
        return UPDATE_CHANGED;
    }

    boolean clear() {
        if (rows.isEmpty()) return false;
        goal = "";
        current = "";
        completed = Collections.emptyList();
        planSteps = Collections.emptyList();
        structuredPlan = false;
        rows = Collections.emptyList();
        return true;
    }

    boolean isEmpty() {
        return rows.isEmpty();
    }

    String current() {
        return current;
    }

    List<Row> snapshot() {
        return rows;
    }

    static String visibleText(Row row, long nowMs) {
        int total = row.text.codePointCount(0, row.text.length());
        if (total == 0 || nowMs >= row.revealStartedAtMs + row.revealDurationMs) {
            return row.text;
        }
        float progress = Math.max(0f, Math.min(1f,
                (nowMs - row.revealStartedAtMs) / (float) row.revealDurationMs));
        int visible = Math.max(1, Math.min(total, (int) Math.ceil(total * progress)));
        return row.text.substring(0, row.text.offsetByCodePoints(0, visible));
    }

    private Row reuseOrCreate(int kind, String text, long nowMs) {
        for (Row row : rows) {
            if (row.text.equals(text)) return row.withKind(kind);
        }
        return new Row(kind, text, nowMs);
    }

    private boolean samePlanIdentity(String nextGoal, List<Step> nextSteps) {
        if (!goal.equals(nextGoal) || planSteps.size() != nextSteps.size()) return false;
        for (int i = 0; i < planSteps.size(); i++) {
            if (!planSteps.get(i).text.equals(nextSteps.get(i).text)) return false;
        }
        return true;
    }

    private static boolean sameSteps(List<Step> first, List<Step> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            Step a = first.get(i);
            Step b = second.get(i);
            if (!a.text.equals(b.text) || !a.status.equals(b.status)) return false;
        }
        return true;
    }

    private static List<Step> sanitizeSteps(List<Step> rawSteps) {
        if (rawSteps == null || rawSteps.isEmpty() || rawSteps.size() > MAX_STEPS) return null;
        List<Step> sanitized = new ArrayList<>();
        for (Step raw : rawSteps) {
            if (raw == null || !isStatus(raw.status)) return null;
            String text = sanitize(raw.text);
            if (text.isEmpty()) return null;
            sanitized.add(new Step(text, raw.status));
        }
        return sanitized;
    }

    private static boolean isStatus(String status) {
        return STATUS_PENDING.equals(status)
                || STATUS_IN_PROGRESS.equals(status)
                || STATUS_COMPLETED.equals(status);
    }

    private static int kindForStatus(String status) {
        if (STATUS_COMPLETED.equals(status)) return KIND_COMPLETED;
        if (STATUS_IN_PROGRESS.equals(status)) return KIND_IN_PROGRESS;
        return KIND_PENDING;
    }

    private static String firstInProgress(List<Step> steps) {
        for (Step step : steps) {
            if (STATUS_IN_PROGRESS.equals(step.status)) return step.text;
        }
        return "";
    }

    static String sanitize(String raw) {
        return sanitize(raw, MAX_TEXT_CODE_POINTS);
    }

    private static String sanitize(String raw, int maxCodePoints) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        int accepted = 0;
        for (int offset = 0; offset < raw.length() && accepted < maxCodePoints; ) {
            int codePoint = raw.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint) || Character.isISOControl(codePoint)) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace && accepted < maxCodePoints) {
                out.append(' ');
                accepted++;
            }
            pendingSpace = false;
            if (accepted >= maxCodePoints) break;
            out.appendCodePoint(codePoint);
            accepted++;
        }
        return out.toString();
    }

    private static List<String> sanitizeCompleted(List<String> rawCompleted) {
        if (rawCompleted == null || rawCompleted.isEmpty()) return new ArrayList<>();
        List<String> sanitized = new ArrayList<>();
        for (int i = 0; i < rawCompleted.size(); i++) {
            String value = sanitize(rawCompleted.get(i));
            if (value.isEmpty()) continue;
            if (!sanitized.isEmpty() && sanitized.get(sanitized.size() - 1).equals(value)) continue;
            sanitized.add(value);
        }
        while (sanitized.size() > MAX_COMPLETED) sanitized.remove(0);
        return sanitized;
    }
}
