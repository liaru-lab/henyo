package link.liaru.henyo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WsOperation {
    public static final String TYPE_CALL = "call";
    public static final String TYPE_BATCH = "batch";
    public static final String TYPE_RESULT = "result";
    public static final String TYPE_ERROR = "error";

    public static final String OP_UI_TREE = "ui.tree";
    public static final String OP_UI_OBSERVE = "ui.observe";
    public static final String OP_UI_FIND = "ui.find";
    public static final String OP_UI_CLICK = "ui.click";
    public static final String OP_UI_SET_TEXT = "ui.setText";
    public static final String OP_UI_TAP = "ui.tap";
    public static final String OP_UI_SWIPE = "ui.swipe";
    public static final String OP_UI_SCROLL = "ui.scroll";
    public static final String OP_UI_SCROLL_UNTIL = "ui.scrollUntil";
    public static final String OP_UI_WAIT = "ui.wait";
    public static final String OP_APP_CURRENT = "app.current";
    public static final String OP_APP_LIST = "app.list";
    public static final String OP_APP_LAUNCH = "app.launch";
    public static final String OP_APP_START = "app.start";
    public static final String OP_GLOBAL_BACK = "global.back";
    public static final String OP_GLOBAL_HOME = "global.home";
    public static final String OP_SCREEN_SCREENSHOT = "screen.screenshot";
    public static final String OP_TERMUX_EXEC = "termux.exec";
    public static final String OP_TASK_PROGRESS_SET = "task.progress.set";
    public static final String OP_TASK_PROGRESS_FINISH = "task.progress.finish";
    public static final String OP_TASK_COMPLETION_SHOW = "task.completion.show";

    public static final OperationSpec SPEC_UI_TREE = spec(OP_UI_TREE, "GET", "/v1/ui/tree", false);
    public static final OperationSpec SPEC_UI_OBSERVE = spec(OP_UI_OBSERVE, "WS", "ui.observe", false);
    public static final OperationSpec SPEC_UI_FIND = spec(OP_UI_FIND, "POST", "/v1/ui/find", false);
    public static final OperationSpec SPEC_UI_CLICK = spec(OP_UI_CLICK, "POST", "/v1/ui/click", false);
    public static final OperationSpec SPEC_UI_SET_TEXT = spec(OP_UI_SET_TEXT, "POST", "/v1/ui/set-text", false);
    public static final OperationSpec SPEC_UI_TAP = spec(OP_UI_TAP, "POST", "/v1/ui/tap", false);
    public static final OperationSpec SPEC_UI_SWIPE = spec(OP_UI_SWIPE, "POST", "/v1/ui/swipe", false);
    public static final OperationSpec SPEC_UI_SCROLL = spec(OP_UI_SCROLL, "POST", "/v1/ui/scroll", false);
    public static final OperationSpec SPEC_UI_SCROLL_UNTIL = spec(OP_UI_SCROLL_UNTIL, "POST", "/v1/ui/scroll-until", false);
    public static final OperationSpec SPEC_UI_WAIT = spec(OP_UI_WAIT, "POST", "/v1/ui/wait", false);
    public static final OperationSpec SPEC_APP_CURRENT = spec(OP_APP_CURRENT, "GET", "/v1/app/current", false);
    public static final OperationSpec SPEC_APP_LIST = spec(OP_APP_LIST, "WS", "app.list", false);
    public static final OperationSpec SPEC_APP_LAUNCH = spec(OP_APP_LAUNCH, "POST", "/v1/app/launch", false);
    public static final OperationSpec SPEC_APP_START = spec(OP_APP_START, "POST", "/v1/app/start", false);
    public static final OperationSpec SPEC_GLOBAL_BACK = spec(OP_GLOBAL_BACK, "POST", "/v1/global/back", false);
    public static final OperationSpec SPEC_GLOBAL_HOME = spec(OP_GLOBAL_HOME, "POST", "/v1/global/home", false);
    public static final OperationSpec SPEC_SCREEN_SCREENSHOT = spec(OP_SCREEN_SCREENSHOT, "GET", "/v1/screen/screenshot", true);
    public static final OperationSpec SPEC_TERMUX_EXEC = spec(OP_TERMUX_EXEC, "POST", "/v1/termux/exec", false);
    public static final OperationSpec SPEC_TASK_PROGRESS_SET = spec(OP_TASK_PROGRESS_SET, "WS", OP_TASK_PROGRESS_SET, false);
    public static final OperationSpec SPEC_TASK_PROGRESS_FINISH = spec(OP_TASK_PROGRESS_FINISH, "WS", OP_TASK_PROGRESS_FINISH, false);
    public static final OperationSpec SPEC_TASK_COMPLETION_SHOW = spec(OP_TASK_COMPLETION_SHOW, "WS", OP_TASK_COMPLETION_SHOW, false);

    public static final List<String> SUPPORTED_OPS;
    public static final Map<String, OperationSpec> SPECS_BY_OP;

    static {
        List<String> ops = new ArrayList<>();
        ops.add(OP_UI_TREE);
        ops.add(OP_UI_OBSERVE);
        ops.add(OP_UI_FIND);
        ops.add(OP_UI_CLICK);
        ops.add(OP_UI_SET_TEXT);
        ops.add(OP_UI_TAP);
        ops.add(OP_UI_SWIPE);
        ops.add(OP_UI_SCROLL);
        ops.add(OP_UI_SCROLL_UNTIL);
        ops.add(OP_UI_WAIT);
        ops.add(OP_APP_CURRENT);
        ops.add(OP_APP_LIST);
        ops.add(OP_APP_LAUNCH);
        ops.add(OP_APP_START);
        ops.add(OP_GLOBAL_BACK);
        ops.add(OP_GLOBAL_HOME);
        ops.add(OP_SCREEN_SCREENSHOT);
        ops.add(OP_TERMUX_EXEC);
        ops.add(OP_TASK_PROGRESS_SET);
        ops.add(OP_TASK_PROGRESS_FINISH);
        ops.add(OP_TASK_COMPLETION_SHOW);
        SUPPORTED_OPS = Collections.unmodifiableList(ops);

        Map<String, OperationSpec> specs = new LinkedHashMap<>();
        addSpec(specs, SPEC_UI_TREE);
        addSpec(specs, SPEC_UI_OBSERVE);
        addSpec(specs, SPEC_UI_FIND);
        addSpec(specs, SPEC_UI_CLICK);
        addSpec(specs, SPEC_UI_SET_TEXT);
        addSpec(specs, SPEC_UI_TAP);
        addSpec(specs, SPEC_UI_SWIPE);
        addSpec(specs, SPEC_UI_SCROLL);
        addSpec(specs, SPEC_UI_SCROLL_UNTIL);
        addSpec(specs, SPEC_UI_WAIT);
        addSpec(specs, SPEC_APP_CURRENT);
        addSpec(specs, SPEC_APP_LIST);
        addSpec(specs, SPEC_APP_LAUNCH);
        addSpec(specs, SPEC_APP_START);
        addSpec(specs, SPEC_GLOBAL_BACK);
        addSpec(specs, SPEC_GLOBAL_HOME);
        addSpec(specs, SPEC_SCREEN_SCREENSHOT);
        addSpec(specs, SPEC_TERMUX_EXEC);
        addSpec(specs, SPEC_TASK_PROGRESS_SET);
        addSpec(specs, SPEC_TASK_PROGRESS_FINISH);
        addSpec(specs, SPEC_TASK_COMPLETION_SHOW);
        SPECS_BY_OP = Collections.unmodifiableMap(specs);
    }

    private WsOperation() {
    }

    public static String normalizeOp(String op) {
        if (op == null) return null;
        String value = op.trim();
        return value.isEmpty() ? null : value;
    }

    public static boolean isSupported(String op) {
        String value = normalizeOp(op);
        return value != null && SPECS_BY_OP.containsKey(value);
    }

    public static OperationSpec specFor(String op) {
        String value = normalizeOp(op);
        return value == null ? null : SPECS_BY_OP.get(value);
    }

    public static OperationSpec requireSpec(String op) {
        OperationSpec spec = specFor(op);
        if (spec == null) {
            throw new IllegalArgumentException("unsupported op: " + op);
        }
        return spec;
    }

    public static CallRequest call(String id, String op, Map<String, ?> params) {
        return new CallRequest(id, op, params, null);
    }

    public static CallRequest call(String id, String op, Map<String, ?> params, Long timeoutMs) {
        return new CallRequest(id, op, params, timeoutMs);
    }

    public static CallResult result(String id, Map<String, ?> result, Long durationMs) {
        return new CallResult(id, true, copyMap(result), null, null, durationMs);
    }

    public static CallResult failure(String id, String code, String message, Long durationMs) {
        return new CallResult(id, false, null, requireText(code, "code"), requireText(message, "message"), durationMs);
    }

    public static CallError error(String id, String code, String message) {
        return new CallError(id, requireText(code, "code"), requireText(message, "message"), Collections.<String, Object>emptyMap());
    }

    public static CallError error(String id, String code, String message, Map<String, ?> details) {
        return new CallError(id, requireText(code, "code"), requireText(message, "message"), copyMap(details));
    }

    public static BatchRequest batch(String id, List<CallRequest> steps, boolean stopOnError, Long timeoutMs, boolean returnTree) {
        return new BatchRequest(id, steps, stopOnError, timeoutMs, returnTree);
    }

    public static BatchResult batchResult(String id, List<CallResult> steps, boolean stoppedOnError, Long durationMs) {
        return new BatchResult(id, steps, stoppedOnError, durationMs);
    }

    public static OperationSpec spec(String op, String httpMethod, String httpPath, boolean binaryResult) {
        return new OperationSpec(requireText(op, "op"), requireText(httpMethod, "httpMethod"), requireText(httpPath, "httpPath"), binaryResult);
    }

    private static void addSpec(Map<String, OperationSpec> specs, OperationSpec spec) {
        specs.put(spec.op, spec);
    }

    private static String requireText(String value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " must not be null");
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be empty");
        }
        return trimmed;
    }

    private static Map<String, Object> copyMap(Map<String, ?> input) {
        if (input == null || input.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : input.entrySet()) {
            String key = entry.getKey();
            if (key == null) {
                continue;
            }
            copy.put(key, entry.getValue());
        }
        return Collections.unmodifiableMap(copy);
    }

    private static List<CallRequest> copyRequests(List<CallRequest> steps) {
        if (steps == null || steps.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    private static List<CallResult> copyResults(List<CallResult> steps) {
        if (steps == null || steps.isEmpty()) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(new ArrayList<>(steps));
    }

    public static final class OperationSpec {
        public final String op;
        public final String httpMethod;
        public final String httpPath;
        public final boolean binaryResult;

        private OperationSpec(String op, String httpMethod, String httpPath, boolean binaryResult) {
            this.op = op;
            this.httpMethod = httpMethod;
            this.httpPath = httpPath;
            this.binaryResult = binaryResult;
        }
    }

    public static final class CallRequest {
        public final String id;
        public final String op;
        public final Map<String, Object> params;
        public final Long timeoutMs;

        private CallRequest(String id, String op, Map<String, ?> params, Long timeoutMs) {
            this.id = requireText(id, "id");
            this.op = requireSpec(op).op;
            this.params = copyMap(params);
            if (timeoutMs != null && timeoutMs.longValue() < 0L) {
                throw new IllegalArgumentException("timeoutMs must not be negative");
            }
            this.timeoutMs = timeoutMs;
        }
    }

    public static final class CallResult {
        public final String id;
        public final boolean ok;
        public final Map<String, Object> result;
        public final String code;
        public final String message;
        public final Long durationMs;

        private CallResult(String id, boolean ok, Map<String, Object> result, String code, String message, Long durationMs) {
            this.id = requireText(id, "id");
            this.ok = ok;
            this.result = result == null ? Collections.<String, Object>emptyMap() : result;
            this.code = code;
            this.message = message;
            if (durationMs != null && durationMs.longValue() < 0L) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            this.durationMs = durationMs;
        }
    }

    public static final class CallError {
        public final String id;
        public final String code;
        public final String message;
        public final Map<String, Object> details;

        private CallError(String id, String code, String message, Map<String, Object> details) {
            this.id = requireText(id, "id");
            this.code = requireText(code, "code");
            this.message = requireText(message, "message");
            this.details = details;
        }
    }

    public static final class BatchRequest {
        public final String id;
        public final List<CallRequest> steps;
        public final boolean stopOnError;
        public final Long timeoutMs;
        public final boolean returnTree;

        private BatchRequest(String id, List<CallRequest> steps, boolean stopOnError, Long timeoutMs, boolean returnTree) {
            this.id = requireText(id, "id");
            this.steps = copyRequests(steps);
            this.stopOnError = stopOnError;
            if (timeoutMs != null && timeoutMs.longValue() < 0L) {
                throw new IllegalArgumentException("timeoutMs must not be negative");
            }
            this.timeoutMs = timeoutMs;
            this.returnTree = returnTree;
        }
    }

    public static final class BatchResult {
        public final String id;
        public final List<CallResult> steps;
        public final boolean stoppedOnError;
        public final Long durationMs;

        private BatchResult(String id, List<CallResult> steps, boolean stoppedOnError, Long durationMs) {
            this.id = requireText(id, "id");
            this.steps = copyResults(steps);
            this.stoppedOnError = stoppedOnError;
            if (durationMs != null && durationMs.longValue() < 0L) {
                throw new IllegalArgumentException("durationMs must not be negative");
            }
            this.durationMs = durationMs;
        }
    }
}
