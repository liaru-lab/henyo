package link.liaru.henyo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

final class TermuxCommandBridge {
    static final String PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND";
    static final String TERMUX_PACKAGE = "com.termux";
    static final String TERMUX_SERVICE = "com.termux.app.RunCommandService";
    static final String ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND";
    static final String EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH";
    static final String EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS";
    static final String EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN";
    static final String EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR";
    static final String EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND";
    static final String EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT";
    static final String EXTRA_COMMAND_LABEL = "com.termux.RUN_COMMAND_COMMAND_LABEL";
    static final String EXTRA_COMMAND_DESCRIPTION = "com.termux.RUN_COMMAND_COMMAND_DESCRIPTION";
    static final String EXTRA_EXECUTION_ID = "link.liaru.henyo.termux.EXECUTION_ID";

    private static final String RESULT_BUNDLE = "result";
    private static final String RESULT_STDOUT = "stdout";
    private static final String RESULT_STDERR = "stderr";
    private static final String RESULT_STDOUT_ORIGINAL_LENGTH = "stdout_original_length";
    private static final String RESULT_STDERR_ORIGINAL_LENGTH = "stderr_original_length";
    private static final String RESULT_EXIT_CODE = "exitCode";
    private static final String RESULT_ERR = "err";
    private static final String RESULT_ERRMSG = "errmsg";

    private static final int MAX_ARGUMENTS = 256;
    private static final int MAX_ARGUMENT_CHARS = 32_768;
    private static final int MAX_STDIN_CHARS = 65_536;
    private static final int MAX_OUTPUT_CHARS = 65_536;
    private static final long MIN_TIMEOUT_MS = 1_000L;
    private static final long MAX_TIMEOUT_MS = 120_000L;
    private static final int FLAG_MUTABLE_COMPAT = 0x02000000;
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1000);
    private static final ConcurrentHashMap<Integer, PendingResult> PENDING = new ConcurrentHashMap<>();
    private static final Semaphore CONCURRENCY = new Semaphore(2, true);

    private TermuxCommandBridge() {
    }

    static ExecutionResult execute(Context context, Request request) {
        String validation = request.validate();
        if (!validation.isEmpty()) return ExecutionResult.failure("invalid_request", validation, 0L);
        if (context.checkSelfPermission(PERMISSION_RUN_COMMAND) != PackageManager.PERMISSION_GRANTED) {
            return ExecutionResult.failure("termux_android_permission_required",
                    "Henyo is not allowed to run commands in Termux", 0L);
        }
        if (!CONCURRENCY.tryAcquire()) {
            return ExecutionResult.failure("termux_busy", "Two Termux commands are already running", 0L);
        }

        long started = System.currentTimeMillis();
        int executionId = NEXT_ID.incrementAndGet();
        PendingResult pending = new PendingResult();
        PENDING.put(executionId, pending);
        try {
            Intent callback = new Intent(context, TermuxCommandResultReceiver.class);
            callback.setAction("link.liaru.henyo.TERMUX_RESULT." + executionId);
            callback.putExtra(EXTRA_EXECUTION_ID, executionId);
            PendingIntent resultIntent = PendingIntent.getBroadcast(context, executionId, callback,
                    PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_UPDATE_CURRENT | FLAG_MUTABLE_COMPAT);

            Intent command = new Intent();
            command.setComponent(new ComponentName(TERMUX_PACKAGE, TERMUX_SERVICE));
            command.setAction(ACTION_RUN_COMMAND);
            command.putExtra(EXTRA_COMMAND_PATH, request.commandPath);
            command.putExtra(EXTRA_ARGUMENTS, request.arguments.toArray(new String[0]));
            command.putExtra(EXTRA_WORKDIR, request.workdir);
            command.putExtra(EXTRA_BACKGROUND, true);
            command.putExtra(EXTRA_PENDING_INTENT, resultIntent);
            command.putExtra(EXTRA_COMMAND_LABEL, "Henyo paired command");
            command.putExtra(EXTRA_COMMAND_DESCRIPTION,
                    "Requested by an explicitly approved Henyo pairing token.");
            if (!request.stdin.isEmpty()) command.putExtra(EXTRA_STDIN, request.stdin);

            ComponentName startedService = context.startService(command);
            if (startedService == null) {
                return ExecutionResult.failure("termux_unavailable", "Termux rejected the command intent",
                        elapsed(started));
            }
            if (!pending.latch.await(request.timeoutMs, TimeUnit.MILLISECONDS)) {
                return ExecutionResult.failure("termux_timeout",
                        "Timed out waiting for Termux; the command may still be running", elapsed(started));
            }
            if (pending.result == null) {
                return ExecutionResult.failure("termux_result_missing", "Termux returned no result",
                        elapsed(started));
            }
            return pending.result.withDuration(elapsed(started));
        } catch (SecurityException e) {
            return ExecutionResult.failure("termux_permission_denied", safeMessage(e), elapsed(started));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ExecutionResult.failure("termux_interrupted", "Interrupted while waiting for Termux",
                    elapsed(started));
        } catch (RuntimeException e) {
            return ExecutionResult.failure("termux_start_failed", safeMessage(e), elapsed(started));
        } finally {
            PENDING.remove(executionId);
            CONCURRENCY.release();
        }
    }

    static void onResult(Intent intent) {
        if (intent == null) return;
        int executionId = intent.getIntExtra(EXTRA_EXECUTION_ID, -1);
        PendingResult pending = PENDING.get(executionId);
        if (pending == null) return;
        Bundle result = intent.getBundleExtra(RESULT_BUNDLE);
        if (result == null) {
            pending.result = ExecutionResult.failure("termux_result_missing", "Termux returned no result bundle", 0L);
        } else {
            String stdout = bounded(result.getString(RESULT_STDOUT, ""));
            String stderr = bounded(result.getString(RESULT_STDERR, ""));
            String errmsg = bounded(result.getString(RESULT_ERRMSG, ""));
            int exitCode = result.getInt(RESULT_EXIT_CODE, -1);
            int err = result.getInt(RESULT_ERR, Activity.RESULT_OK);
            pending.result = new ExecutionResult(err == Activity.RESULT_OK, err == Activity.RESULT_OK ? "" : "termux_error",
                    errmsg, stdout, stderr, exitCode, err,
                    parseLength(result.get(RESULT_STDOUT_ORIGINAL_LENGTH), stdout.length()),
                    parseLength(result.get(RESULT_STDERR_ORIGINAL_LENGTH), stderr.length()), 0L);
        }
        pending.latch.countDown();
    }

    private static int parseLength(Object value, int fallback) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
            }
        }
        return fallback;
    }

    private static String bounded(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() <= MAX_OUTPUT_CHARS) return safe;
        return safe.substring(0, MAX_OUTPUT_CHARS);
    }

    private static long elapsed(long started) {
        return Math.max(0L, System.currentTimeMillis() - started);
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "" : error.getMessage();
        return message == null || message.trim().isEmpty() ? "Termux command failed" : message;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        String safe = value == null ? "" : value;
        for (int i = 0; i < safe.length(); i++) {
            char c = safe.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"': out.append("\\\""); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.toString();
    }

    static final class Request {
        final String commandPath;
        final List<String> arguments;
        final String workdir;
        final String stdin;
        final long timeoutMs;

        Request(String commandPath, List<String> arguments, String workdir, String stdin, long timeoutMs) {
            this.commandPath = commandPath == null ? "" : commandPath.trim();
            this.arguments = arguments == null ? new ArrayList<>() : new ArrayList<>(arguments);
            this.workdir = workdir == null || workdir.trim().isEmpty()
                    ? "/data/data/com.termux/files/home" : workdir.trim();
            this.stdin = stdin == null ? "" : stdin;
            this.timeoutMs = Math.max(MIN_TIMEOUT_MS, Math.min(MAX_TIMEOUT_MS, timeoutMs));
        }

        private String validate() {
            if (commandPath.isEmpty()) return "commandPath is required";
            if (!(commandPath.startsWith("/") || commandPath.startsWith("~/") || commandPath.startsWith("$PREFIX/"))) {
                return "commandPath must be absolute or start with ~/ or $PREFIX/";
            }
            if (commandPath.length() > 4096) return "commandPath is too long";
            if (workdir.length() > 4096) return "workdir is too long";
            if (arguments.size() > MAX_ARGUMENTS) return "too many arguments";
            int total = commandPath.length() + workdir.length() + stdin.length();
            if (stdin.length() > MAX_STDIN_CHARS) return "stdin is too large";
            for (String argument : arguments) {
                if (argument == null) return "arguments must be strings";
                if (argument.length() > MAX_ARGUMENT_CHARS) return "an argument is too large";
                total += argument.length();
            }
            return total > 120_000 ? "command request is too large" : "";
        }
    }

    static final class ExecutionResult {
        final boolean ok;
        final String code;
        final String message;
        final String stdout;
        final String stderr;
        final int exitCode;
        final int internalErrorCode;
        final int stdoutOriginalLength;
        final int stderrOriginalLength;
        final long durationMs;

        ExecutionResult(boolean ok, String code, String message, String stdout, String stderr,
                        int exitCode, int internalErrorCode, int stdoutOriginalLength,
                        int stderrOriginalLength, long durationMs) {
            this.ok = ok;
            this.code = code == null ? "" : code;
            this.message = message == null ? "" : message;
            this.stdout = stdout == null ? "" : stdout;
            this.stderr = stderr == null ? "" : stderr;
            this.exitCode = exitCode;
            this.internalErrorCode = internalErrorCode;
            this.stdoutOriginalLength = stdoutOriginalLength;
            this.stderrOriginalLength = stderrOriginalLength;
            this.durationMs = durationMs;
        }

        static ExecutionResult failure(String code, String message, long durationMs) {
            return new ExecutionResult(false, code, message, "", "", -1, -1, 0, 0, durationMs);
        }

        ExecutionResult withDuration(long duration) {
            return new ExecutionResult(ok, code, message, stdout, stderr, exitCode, internalErrorCode,
                    stdoutOriginalLength, stderrOriginalLength, duration);
        }

        String toJson() {
            return "{" +
                    "\"ok\":" + ok + "," +
                    "\"stdout\":\"" + escape(stdout) + "\"," +
                    "\"stderr\":\"" + escape(stderr) + "\"," +
                    "\"exitCode\":" + exitCode + "," +
                    "\"internalErrorCode\":" + internalErrorCode + "," +
                    "\"stdoutOriginalLength\":" + stdoutOriginalLength + "," +
                    "\"stderrOriginalLength\":" + stderrOriginalLength + "," +
                    "\"durationMs\":" + durationMs +
                    (code.isEmpty() ? "" : ",\"code\":\"" + escape(code) + "\"") +
                    (message.isEmpty() ? "" : ",\"message\":\"" + escape(message) + "\"") +
                    "}";
        }
    }

    private static final class PendingResult {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile ExecutionResult result;
    }
}
