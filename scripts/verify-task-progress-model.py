#!/usr/bin/env python3
import subprocess
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODEL = ROOT / "src/main/java/link/liaru/henyo/TaskProgressModel.java"
TEST_SOURCE = r'''
package link.liaru.henyo;

import java.util.Arrays;
import java.util.List;

public final class TaskProgressModelTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static TaskProgressModel.Step step(String text, String status) {
        return new TaskProgressModel.Step(text, status);
    }

    public static void main(String[] args) {
        check("one two three".equals(TaskProgressModel.sanitize("  one\n\ttwo\u0000three  ")),
                "whitespace and controls must collapse safely");
        String emoji = "\ud83d\ude80";
        String bounded = TaskProgressModel.sanitize(emoji.repeat(100));
        check(bounded.codePointCount(0, bounded.length()) == TaskProgressModel.MAX_TEXT_CODE_POINTS,
                "progress bound must count code points");
        check(!Character.isHighSurrogate(bounded.charAt(bounded.length() - 1)),
                "progress bound must not split surrogate pairs");

        TaskProgressModel model = new TaskProgressModel();
        List<TaskProgressModel.Step> plan = Arrays.asList(
                step("one", TaskProgressModel.STATUS_COMPLETED),
                step("two", TaskProgressModel.STATUS_IN_PROGRESS),
                step("three", TaskProgressModel.STATUS_PENDING));
        check(model.setPlan("goal", plan, false, 500L) == TaskProgressModel.UPDATE_CHANGED,
                "first structured snapshot must install the plan");
        List<TaskProgressModel.Row> planRows = model.snapshot();
        check(planRows.size() == 4, "goal and every structured step must be visible");
        check(planRows.get(0).kind == TaskProgressModel.KIND_GOAL, "goal must be first");
        check(planRows.get(1).kind == TaskProgressModel.KIND_COMPLETED,
                "completed status must preserve caller order");
        check(planRows.get(2).kind == TaskProgressModel.KIND_IN_PROGRESS,
                "in-progress status must preserve caller order");
        check(planRows.get(3).kind == TaskProgressModel.KIND_PENDING,
                "pending status must preserve caller order");
        check("two".equals(model.current()), "in-progress text must drive intent deduplication");
        long revealStarted = planRows.get(2).revealStartedAtMs;

        List<TaskProgressModel.Step> statusUpdate = Arrays.asList(
                step("one", TaskProgressModel.STATUS_COMPLETED),
                step("two", TaskProgressModel.STATUS_COMPLETED),
                step("three", TaskProgressModel.STATUS_IN_PROGRESS));
        check(model.setPlan("goal", statusUpdate, false, 1_000L)
                        == TaskProgressModel.UPDATE_CHANGED,
                "matching plan identity must accept status-only updates");
        check(model.snapshot().get(2).revealStartedAtMs == revealStarted,
                "status updates must not restart unchanged step text reveal");
        check("three".equals(model.current()), "updated in-progress step must become current");
        List<TaskProgressModel.Row> beforeMismatch = model.snapshot();
        check(model.setPlan("goal", Arrays.asList(
                        step("one", TaskProgressModel.STATUS_COMPLETED),
                        step("changed", TaskProgressModel.STATUS_IN_PROGRESS)), false, 1_100L)
                        == TaskProgressModel.UPDATE_PLAN_MISMATCH,
                "text/count changes must require explicit replan");
        check(model.snapshot() == beforeMismatch, "rejected mismatch must not partially mutate state");
        check(model.setPlan("goal", Arrays.asList(step("bad", "running")), true, 1_200L)
                        == TaskProgressModel.UPDATE_INVALID,
                "unknown status must fail closed");
        check(model.snapshot() == beforeMismatch, "invalid replan must not mutate state");
        check(model.setPlan("goal", Arrays.asList(
                        step("1", TaskProgressModel.STATUS_PENDING),
                        step("2", TaskProgressModel.STATUS_PENDING),
                        step("3", TaskProgressModel.STATUS_PENDING),
                        step("4", TaskProgressModel.STATUS_PENDING),
                        step("5", TaskProgressModel.STATUS_PENDING),
                        step("6", TaskProgressModel.STATUS_PENDING),
                        step("7", TaskProgressModel.STATUS_PENDING)), true, 1_250L)
                        == TaskProgressModel.UPDATE_INVALID,
                "structured plan must reject overflow instead of silently hiding steps");
        check(model.setPlan("new goal", Arrays.asList(
                        step("replacement", TaskProgressModel.STATUS_IN_PROGRESS)), true, 1_300L)
                        == TaskProgressModel.UPDATE_CHANGED,
                "explicit replan must replace structure");
        check(model.snapshot().size() == 2 && "replacement".equals(model.current()),
                "replan must expose only the replacement plan");

        List<String> completed = Arrays.asList("old", "one", "two", "three", "three");
        check(model.set("legacy goal", completed, "current", 2_000L),
                "legacy snapshot must remain accepted after structured mode");
        List<TaskProgressModel.Row> rows = model.snapshot();
        check(rows.size() == 5, "goal, three completed, and current must fit the bound");
        check(rows.get(0).kind == TaskProgressModel.KIND_GOAL, "goal must be first");
        check("one".equals(rows.get(1).text), "oldest overflow milestone must be evicted");
        check(rows.get(4).kind == TaskProgressModel.KIND_IN_PROGRESS,
                "exactly one legacy current row must be last");
        TaskProgressModel.Row goalRow = rows.get(0);
        TaskProgressModel.Row typed = rows.get(4);
        String prefix = TaskProgressModel.visibleText(typed, 2_001L);
        check(prefix.codePointCount(0, prefix.length()) == 1, "typewriter must reveal whole code points");

        check(model.set("legacy goal", Arrays.asList("two", "three", "four"), "next", 3_000L),
                "updated completed/current snapshot must apply");
        check(model.snapshot().get(0) == goalRow, "unchanged goal must not restart its reveal");
        check("next".equals(model.current()), "only the new current action may remain");
        check(!model.set("legacy goal", Arrays.asList("two", "three", "four"), "next", 4_000L),
                "identical full snapshots must coalesce");
        check(model.clear(), "explicit finish must clear visible state");
        check(model.isEmpty() && model.current().isEmpty(), "clear must remove stale progress");
        check(!model.clear(), "clearing an empty model must be idempotent");
    }
}
'''

with tempfile.TemporaryDirectory(prefix="henyo-task-progress-") as raw_dir:
    directory = Path(raw_dir)
    package_dir = directory / "link/liaru/henyo"
    package_dir.mkdir(parents=True)
    test_file = package_dir / "TaskProgressModelTest.java"
    test_file.write_text(TEST_SOURCE, encoding="utf-8")
    subprocess.run(["javac", "-encoding", "UTF-8", "-d", str(directory),
                    str(MODEL), str(test_file)], check=True)
    subprocess.run(["java", "-cp", str(directory),
                    "link.liaru.henyo.TaskProgressModelTest"], check=True)

print("task progress model verifier passed")
