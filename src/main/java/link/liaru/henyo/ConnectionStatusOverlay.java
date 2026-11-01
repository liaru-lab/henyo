package link.liaru.henyo;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Region;
import android.graphics.RegionIterator;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.Display;
import android.content.Context;
import android.hardware.display.DisplayManager;

import java.util.List;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A touch-through, process-local indication of authenticated Henyo control. */
final class ConnectionStatusOverlay {
    private static final long ACTIVE_HOLD_MS = 20_000L;
    private static final long GLOW_FADE_OUT_MS = 1_800L;
    private static final long MAIN_HEARTBEAT_MS = 100L;
    private static final int EDGE_TOP = 0;
    private static final int EDGE_BOTTOM = 1;
    private static final int EDGE_LEFT = 2;
    private static final int EDGE_RIGHT = 3;

    private final AccessibilityService service;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final WindowManager windowManager;
    private final int edgeDepthPx;
    private final AgentActivityModel activityModel = new AgentActivityModel();
    private final TaskProgressModel progressModel = new TaskProgressModel();
    private final CompletionMessageModel completionModel = new CompletionMessageModel();
    private final AgentVisualModel visualModel = new AgentVisualModel();
    private final PerformanceMetrics performanceMetrics;
    private final VisualCoordinates visualCoordinates = new VisualCoordinates();
    private final Runnable returnToIdle = this::returnToIdleIfDue;
    private final Runnable finishFadeOut = this::finishFadeOutOnMain;
    private final Runnable mainHeartbeat = this::mainHeartbeatOnMain;

    private IndicatorView view;
    private WindowManager.LayoutParams params;
    private ActivityView activityView;
    private WindowManager activityWindowManager;
    private final TargetVisualState targetVisualState = new TargetVisualState();
    private boolean connected;
    private boolean controlStopped;
    private boolean completedPresentation;
    private boolean presentationSuppressed;
    private long presentationGeneration;
    private Object lastCompletedTask;
    private String lastCompletionMessage = "";
    private Runnable stopControl;
    private Runnable resumeControl;
    private android.widget.Button controlButton;
    private boolean active;
    private boolean fadingOut;
    private boolean destroyed;
    private Object progressOwner;
    private int captureSuppressionDepth;
    private long activeUntilElapsedRealtimeMs;
    private long nextMainHeartbeatAtMs;

    ConnectionStatusOverlay(AccessibilityService service, PerformanceMetrics performanceMetrics) {
        this.service = service;
        this.performanceMetrics = performanceMetrics;
        this.windowManager = (WindowManager) service.getSystemService(AccessibilityService.WINDOW_SERVICE);
        visualModel.setObservationMode(HenyoAccessibilityService.observationMode(service));
        float density = service.getResources().getDisplayMetrics().density;
        this.edgeDepthPx = Math.max(3, Math.round(48f * density));
        nextMainHeartbeatAtMs = SystemClock.uptimeMillis() + MAIN_HEARTBEAT_MS;
        mainHandler.postDelayed(mainHeartbeat, MAIN_HEARTBEAT_MS);
    }

    void setConnected(boolean value) {
        mainHandler.post(() -> setConnectedOnMain(value));
    }

    void setObservationMode(ObservationEffectModel.Mode mode) {
        Runnable update = () -> {
            if (destroyed) return;
            visualModel.setObservationMode(mode);
            if (activityView != null) activityView.invalidate();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) update.run(); else mainHandler.post(update);
    }

    void setControlActions(Runnable stop, Runnable resume) {
        stopControl = stop;
        resumeControl = resume;
    }

    void setControlStopped(boolean stopped) {
        mainHandler.post(() -> {
            controlStopped = stopped;
            refreshControlButton();
        });
    }

    private void refreshControlButton() {
        if (controlButton != null) {
            try { windowManager.removeView(controlButton); } catch (RuntimeException ignored) { }
            controlButton = null;
        }
        if (destroyed || captureSuppressionDepth > 0 || (presentationSuppressed && !controlStopped)
                || (!connected && !controlStopped)
                || windowManager == null || stopControl == null) return;
        android.widget.Button button = new android.widget.Button(service);
        button.setText(controlStopped ? "Resume control" : completedPresentation ? "Dismiss" : "Stop control");
        button.setOnClickListener(v -> {
            if (controlStopped) resumeControl.run();
            else if (completedPresentation) dismissCompletedPresentation();
            else stopControl.run();
        });
        WindowManager.LayoutParams layout = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT);
        layout.gravity = Gravity.TOP | Gravity.RIGHT;
        try { windowManager.addView(button, layout); controlButton = button; }
        catch (RuntimeException ignored) { }
    }

    void noteControlActivity() {
        noteControlActivity("");
    }

    void noteControlActivity(String summary) {
        noteControlActivity(summary, () -> true);
    }

    void noteControlActivity(String summary, java.util.function.BooleanSupplier guard) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (guard.getAsBoolean()) noteControlActivityOnMain(summary);
            return;
        }
        CountDownLatch applied = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                if (guard.getAsBoolean()) noteControlActivityOnMain(summary);
            } finally {
                applied.countDown();
            }
        });
        try {
            // Bound the protocol thread wait: the intent is attached before the
            // action whenever the UI thread is healthy, without stalling control.
            applied.await(120L, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    boolean setTaskProgress(Object owner, String goal, List<String> completed, String current,
            java.util.function.BooleanSupplier guard) {
        if (owner == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return guard.getAsBoolean() && setTaskProgressOnMain(owner, goal, completed, current);
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        boolean[] accepted = new boolean[1];
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                accepted[0] = guard.getAsBoolean() && setTaskProgressOnMain(owner, goal, completed, current);
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(1_500L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return false;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
        return accepted[0];
    }

    int setTaskProgressPlan(Object owner, String goal,
            List<TaskProgressModel.Step> steps, boolean replan, java.util.function.BooleanSupplier guard) {
        if (owner == null) return TaskProgressModel.UPDATE_UNAVAILABLE;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return guard.getAsBoolean() ? setTaskProgressPlanOnMain(owner, goal, steps, replan)
                    : TaskProgressModel.UPDATE_UNAVAILABLE;
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        int[] result = {TaskProgressModel.UPDATE_UNAVAILABLE};
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                if (guard.getAsBoolean()) result[0] = setTaskProgressPlanOnMain(owner, goal, steps, replan);
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(1_500L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return TaskProgressModel.UPDATE_UNAVAILABLE;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return TaskProgressModel.UPDATE_UNAVAILABLE;
        }
        return result[0];
    }

    boolean clearTaskProgress(Object owner, boolean force) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return clearTaskProgressOnMain(owner, force);
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        boolean[] cleared = new boolean[1];
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                cleared[0] = clearTaskProgressOnMain(owner, force);
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(1_500L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return false;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return false;
        }
        return cleared[0];
    }

    void releaseTaskProgress(Object owner) {
        if (owner == null) return;
        mainHandler.post(() -> clearTaskProgressOnMain(owner, false));
    }

    boolean completeTask(String message, Object task, java.util.function.BooleanSupplier guard) {
        if (CompletionMessageModel.validate(message) != CompletionMessageModel.SHOW_ACCEPTED) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) return guard.getAsBoolean() && completeTaskOnMain(message, task, guard);
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        boolean[] result = {false};
        mainHandler.post(() -> {
            try {
                if (pending.compareAndSet(true, false) && guard.getAsBoolean())
                    result[0] = completeTaskOnMain(message, task, guard);
            } finally { applied.countDown(); }
        });
        try {
            if (!applied.await(1_500L, TimeUnit.MILLISECONDS)) { pending.set(false); return false; }
        } catch (InterruptedException e) { pending.set(false); Thread.currentThread().interrupt(); return false; }
        return result[0];
    }

    private boolean completeTaskOnMain(String message, Object task, java.util.function.BooleanSupplier guard) {
        if (destroyed || !connected || controlStopped) return false;
        if (lastCompletedTask == task && (!completedPresentation || lastCompletionMessage.equals(message))) return true;
        lastCompletedTask = task; lastCompletionMessage = message;
        clearTaskProgressOnMain(null, true);
        activityModel.clear();
        visualModel.clearActivity(); visualModel.clearScan(); visualModel.clearCursor(); visualCoordinates.clear();
        active = false; fadingOut = false;
        mainHandler.removeCallbacks(returnToIdle); mainHandler.removeCallbacks(finishFadeOut);
        removeView();
        completionModel.show(message, SystemClock.uptimeMillis());
        completedPresentation = true; presentationSuppressed = false;
        long generation = ++presentationGeneration;
        ensureActivityView(); refreshControlButton();
        mainHandler.postDelayed(() -> {
            if (generation == presentationGeneration && guard.getAsBoolean()) dismissCompletedPresentation();
        }, CompletionMessageModel.MESSAGE_HOLD_MS + CompletionMessageModel.MESSAGE_FADE_OUT_MS);
        return true;
    }

    private void dismissCompletedPresentation() {
        if (!completedPresentation) return;
        completionModel.clear(); completedPresentation = false; presentationSuppressed = true;
        presentationGeneration++;
        removeView(); refreshControlButton();
    }

    private void beginPresentation() {
        boolean refresh = completedPresentation || presentationSuppressed;
        lastCompletedTask = null; lastCompletionMessage = "";
        completedPresentation = false; presentationSuppressed = false; presentationGeneration++;
        completionModel.clear();
        if (refresh) refreshControlButton();
    }

    void showObservation(String logicalId) {
        if (visualModel.observationMode() == ObservationEffectModel.Mode.OFF) return;
        postVisual(() -> visualModel.beginObservation(logicalId, SystemClock.uptimeMillis()));
    }

    void setTargetWindow(int windowId, int displayId, Rect bounds, Region presentationRegion, Rect focusedInput,
                         int displayWidth, int displayHeight, float density) {
        Rect boundsCopy = bounds == null ? new Rect() : new Rect(bounds);
        Region regionCopy = presentationRegion == null ? new Region() : new Region(presentationRegion);
        Rect focusedCopy = focusedInput == null ? new Rect() : new Rect(focusedInput);
        mainHandler.post(() -> {
            if (destroyed) return;
            boolean displayChanged = targetVisualState.displayId != displayId;
            targetVisualState.set(windowId, displayId, boundsCopy, regionCopy, focusedCopy,
                    displayWidth, displayHeight, density);
            if (displayChanged) {
                visualCoordinates.clear();
                if (activityView != null) removeActivityView();
            }
            ensureActivityView();
            if (activityView != null) activityView.invalidate();
        });
    }

    void clearTargetWindow() {
        mainHandler.post(() -> {
            targetVisualState.clear();
            if (activityView != null) activityView.invalidate();
        });
    }

    long showPoint(int x, int y) {
        return prepareVisual(now -> visualCoordinates.prepareAction(
                visualModel, AgentVisualModel.GLOVE_POSE_POINT,
                x, y, x, y, 0, displayWidth(), displayHeight(), displayDensity(), now));
    }

    long showSwipe(int x1, int y1, int x2, int y2, int durationMs) {
        return prepareVisual(now -> visualCoordinates.prepareAction(
                visualModel, AgentVisualModel.GLOVE_POSE_OPEN_PALM,
                x1, y1, x2, y2, Math.max(1, durationMs),
                displayWidth(), displayHeight(), displayDensity(), now));
    }

    long showBack(int screenRight, int centerY) {
        int startX = Math.round(screenRight * 0.82f);
        int endX = Math.round(screenRight * 0.22f);
        return prepareVisual(now -> visualCoordinates.prepareAction(
                visualModel, AgentVisualModel.GLOVE_POSE_BACK_LEFT,
                startX, centerY, endX, centerY, 420,
                displayWidth(), displayHeight(), displayDensity(), now));
    }

    private interface VisualPreparation {
        long apply(long nowMs);
    }

    private void postVisual(Runnable update) {
        mainHandler.post(() -> {
            if (destroyed || !connected || completedPresentation || presentationSuppressed) return;
            update.run();
            ensureActivityView();
            if (activityView != null) activityView.invalidate();
        });
    }

    private long prepareVisual(VisualPreparation preparation) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (destroyed || !connected) return 0L;
            long duration = preparation.apply(SystemClock.uptimeMillis());
            ensureActivityView();
            if (activityView != null) activityView.invalidate();
            return duration;
        }
        CountDownLatch applied = new CountDownLatch(1);
        long[] duration = new long[1];
        AtomicBoolean pending = new AtomicBoolean(true);
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                if (destroyed || !connected) return;
                duration[0] = preparation.apply(SystemClock.uptimeMillis());
                ensureActivityView();
                if (activityView != null) activityView.invalidate();
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(120L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return 0L;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return 0L;
        }
        return duration[0];
    }

    boolean commitPreparedAction() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (destroyed || !connected) return false;
            boolean committed = visualCoordinates.beginPreparedAction(
                    visualModel, SystemClock.uptimeMillis());
            if (activityView != null) activityView.invalidate();
            return committed;
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        boolean[] committed = new boolean[1];
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                if (destroyed || !connected) return;
                committed[0] = visualCoordinates.beginPreparedAction(
                        visualModel, SystemClock.uptimeMillis());
                if (activityView != null) activityView.invalidate();
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(120L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return false;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            pending.compareAndSet(true, false);
            return false;
        }
        return committed[0];
    }

    private int displayWidth() {
        return targetVisualState.valid ? targetVisualState.displayWidth
                : service.getResources().getDisplayMetrics().widthPixels;
    }

    private int displayHeight() {
        return targetVisualState.valid ? targetVisualState.displayHeight
                : service.getResources().getDisplayMetrics().heightPixels;
    }

    private float displayDensity() {
        return targetVisualState.valid ? targetVisualState.density
                : service.getResources().getDisplayMetrics().density;
    }

    boolean beginScreenshotSuppression(long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return false;
        }
        CountDownLatch ready = new CountDownLatch(1);
        mainHandler.post(() -> {
            if (destroyed) {
                ready.countDown();
                return;
            }
            captureSuppressionDepth++;
            // Android 13 and older have no window-scoped accessibility
            // screenshot API. Detach the overlay as a reliable fallback and
            // give SurfaceFlinger roughly two frames to commit its absence.
            removeView();
            refreshControlButton();
            mainHandler.postDelayed(ready::countDown, 48L);
        });
        try {
            if (ready.await(Math.max(100L, timeoutMs), TimeUnit.MILLISECONDS)) {
                return true;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        endScreenshotSuppression();
        return false;
    }

    void endScreenshotSuppression() {
        Runnable restore = () -> {
            if (captureSuppressionDepth > 0) captureSuppressionDepth--;
            if (captureSuppressionDepth == 0 && connected && !destroyed) ensureView();
            refreshControlButton();
        };
        if (Looper.myLooper() == Looper.getMainLooper()) restore.run();
        else mainHandler.post(restore);
    }

    void destroy() {
        mainHandler.post(() -> {
            destroyed = true;
            connected = false;
            active = false;
            fadingOut = false;
            captureSuppressionDepth = 0;
            mainHandler.removeCallbacks(returnToIdle);
            mainHandler.removeCallbacks(finishFadeOut);
            mainHandler.removeCallbacks(mainHeartbeat);
            removeView();
            refreshControlButton();
        });
    }

    private void mainHeartbeatOnMain() {
        if (destroyed) return;
        long now = SystemClock.uptimeMillis();
        performanceMetrics.recordMainHeartbeat(now, Math.max(0L, now - nextMainHeartbeatAtMs));
        nextMainHeartbeatAtMs = now + MAIN_HEARTBEAT_MS;
        mainHandler.postDelayed(mainHeartbeat, MAIN_HEARTBEAT_MS);
    }

    private void setConnectedOnMain(boolean value) {
        if (destroyed) return;
        connected = value;
        refreshControlButton();
        if (!connected) {
            completedPresentation = false; presentationSuppressed = false; presentationGeneration++;
            lastCompletedTask = null; lastCompletionMessage = "";
            active = false;
            fadingOut = false;
            visualModel.clearActivity();
            visualModel.clearScan();
            visualModel.clearCursor();
            visualCoordinates.clear();
            activityModel.clear();
            progressModel.clear();
            completionModel.clear();
            progressOwner = null;
            mainHandler.removeCallbacks(returnToIdle);
            mainHandler.removeCallbacks(finishFadeOut);
            removeView();
            return;
        }
        ensureView();
    }

    private void noteControlActivityOnMain(String summary) {
        if (destroyed || !connected) return;
        beginPresentation();
        ensureView();
        visualModel.noteActivity(SystemClock.uptimeMillis());
        ensureActivityView();
        boolean duplicatesCurrent = AgentActivityModel.sanitize(summary)
                .equals(progressModel.current());
        if (!duplicatesCurrent && activityModel.add(summary, SystemClock.uptimeMillis())) {
            if (activityView != null) activityView.noteNewMessage();
        } else if (activityView != null) {
            activityView.invalidate();
        }
        activeUntilElapsedRealtimeMs = SystemClock.elapsedRealtime() + ACTIVE_HOLD_MS;
        mainHandler.removeCallbacks(returnToIdle);
        mainHandler.removeCallbacks(finishFadeOut);
        mainHandler.postDelayed(returnToIdle, ACTIVE_HOLD_MS);
        if (!active) {
            active = true;
            fadingOut = false;
            if (view != null) {
                ensureActiveEdgeViews();
            }
        }
        // The first envelope sample is intentionally zero; attach unconditionally
        // after active is true so caption-free calls still get the navy backdrop.
        ensureActivityView();
    }

    private boolean setTaskProgressOnMain(Object owner, String goal,
            List<String> completed, String current) {
        if (destroyed || !connected) return false;
        beginPresentation();
        progressOwner = owner;
        long now = SystemClock.uptimeMillis();
        boolean changed = progressModel.set(goal, completed, current, now);
        activityModel.removeSummary(progressModel.current());
        ensureActivityView();
        if (activityView != null) {
            if (changed) activityView.noteProgressChanged();
            else activityView.invalidate();
        }
        return true;
    }

    private int setTaskProgressPlanOnMain(Object owner, String goal,
            List<TaskProgressModel.Step> steps, boolean replan) {
        if (destroyed || !connected) return TaskProgressModel.UPDATE_UNAVAILABLE;
        long now = SystemClock.uptimeMillis();
        int result = progressModel.setPlan(goal, steps, replan, now);
        if (result == TaskProgressModel.UPDATE_INVALID
                || result == TaskProgressModel.UPDATE_PLAN_MISMATCH) return result;
        beginPresentation();
        progressOwner = owner;
        activityModel.removeSummary(progressModel.current());
        ensureActivityView();
        if (activityView != null) {
            if (result == TaskProgressModel.UPDATE_CHANGED) activityView.noteProgressChanged();
            else activityView.invalidate();
        }
        return result;
    }

    private boolean clearTaskProgressOnMain(Object owner, boolean force) {
        if (!force && progressOwner != owner) return false;
        boolean cleared = progressModel.clear();
        progressOwner = null;
        if (activityView != null) activityView.noteProgressChanged();
        removeActivityViewIfEmpty();
        return cleared;
    }

    private void returnToIdleIfDue() {
        if (destroyed || !connected) return;
        long remaining = activeUntilElapsedRealtimeMs - SystemClock.elapsedRealtime();
        if (remaining > 0L) {
            mainHandler.postDelayed(returnToIdle, remaining);
            return;
        }
        active = false;
        fadingOut = true;
        if (view != null) {
            view.invalidate();
        }
        if (activityView != null) activityView.invalidate();
        invalidateActiveEdgeViews();
        mainHandler.removeCallbacks(finishFadeOut);
        mainHandler.postDelayed(finishFadeOut, GLOW_FADE_OUT_MS);
    }

    private void finishFadeOutOnMain() {
        if (destroyed || !connected || active || !fadingOut) return;
        fadingOut = false;
        removeActiveEdgeViews();
        removeActivityViewIfEmpty();
    }

    private void ensureView() {
        if (view != null || windowManager == null || captureSuppressionDepth > 0
                || presentationSuppressed || completedPresentation) return;
        view = new IndicatorView(service, visualModel);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        view.setContentDescription(null);
        params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                edgeDepthPx,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = 0;
        params.y = 0;
        params.setTitle("Henyo connection indicator");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            windowManager.addView(view, params);
            view.setEdge(EDGE_TOP);
            if (active || visualModel.isActivityVisible(SystemClock.uptimeMillis())) ensureActiveEdgeViews();
            if (active || fadingOut || visualModel.isActivityVisible(SystemClock.uptimeMillis())
                    || visualModel.observationActive(SystemClock.uptimeMillis())
                    || !activityModel.isEmpty(SystemClock.uptimeMillis())
                    || !progressModel.isEmpty()
                    || !completionModel.isEmpty(SystemClock.uptimeMillis())) ensureActivityView();
        } catch (RuntimeException ignored) {
            view = null;
            params = null;
        }
    }

    private void removeView() {
        IndicatorView attached = view;
        view = null;
        params = null;
        removeActiveEdgeViews();
        removeAttachedView(attached);
        removeActivityView();
    }

    private void ensureActiveEdgeViews() {
        // Active targeting is rendered as one target-window contour in ActivityView.
    }

    private void invalidateActiveEdgeViews() {
        // The target contour owns active animation invalidation.
    }

    private void removeActiveEdgeViews() {
        // Kept as a lifecycle hook; active edge windows no longer exist.
    }

    private void ensureActivityView() {
        if (activityView != null || windowManager == null || captureSuppressionDepth > 0
                || (!active && !fadingOut
                && !visualModel.isActivityVisible(SystemClock.uptimeMillis())
                && !visualModel.observationActive(SystemClock.uptimeMillis())
                && activityModel.isEmpty(SystemClock.uptimeMillis())
                && progressModel.isEmpty()
                && completionModel.isEmpty(SystemClock.uptimeMillis()))) return;
        int requestedDisplayId = targetVisualState.valid
                ? targetVisualState.displayId : Display.DEFAULT_DISPLAY;
        Context overlayContext = displayContext(requestedDisplayId);
        WindowManager targetManager = overlayContext == null ? null
                : (WindowManager) overlayContext.getSystemService(Context.WINDOW_SERVICE);
        if (targetManager == null) return;
        ActivityView candidate = new ActivityView(overlayContext, activityModel, progressModel,
                completionModel, visualModel,
                visualCoordinates, targetVisualState,
                () -> mainHandler.post(this::removeActivityViewIfEmpty), performanceMetrics);
        candidate.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        candidate.setContentDescription(null);
        WindowManager.LayoutParams activityParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        activityParams.gravity = Gravity.TOP | Gravity.START;
        activityParams.x = 0;
        activityParams.y = 0;
        activityParams.setTitle("Henyo agent activity");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            activityParams.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        try {
            targetManager.addView(candidate, activityParams);
            activityView = candidate;
            activityWindowManager = targetManager;
        } catch (RuntimeException ignored) {
            candidate.stopAnimation();
        }
    }

    private void removeActivityViewIfEmpty() {
        if (activityView == null || active || fadingOut
                || visualModel.isActivityVisible(SystemClock.uptimeMillis())
                || visualModel.observationActive(SystemClock.uptimeMillis())
                || !activityModel.isEmpty(SystemClock.uptimeMillis())
                || !progressModel.isEmpty()
                || !completionModel.isEmpty(SystemClock.uptimeMillis())) return;
        removeActivityView();
    }

    private void removeActivityView() {
        ActivityView attached = activityView;
        WindowManager manager = activityWindowManager;
        activityView = null;
        activityWindowManager = null;
        if (attached == null || manager == null) return;
        attached.stopAnimation();
        try {
            manager.removeViewImmediate(attached);
        } catch (RuntimeException ignored) {
            // The display or accessibility connection may already be gone.
        }
    }

    private Context displayContext(int displayId) {
        if (displayId == Display.DEFAULT_DISPLAY) return service;
        DisplayManager manager = (DisplayManager) service.getSystemService(Context.DISPLAY_SERVICE);
        Display display = manager == null ? null : manager.getDisplay(displayId);
        if (display == null) return service;
        Context context = service.createDisplayContext(display);
        if (Build.VERSION.SDK_INT >= 30) {
            context = context.createWindowContext(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, null);
        }
        return context;
    }

    private void removeAttachedView(IndicatorView attached) {
        if (attached == null || windowManager == null) return;
        attached.stopAnimation();
        try {
            windowManager.removeViewImmediate(attached);
        } catch (RuntimeException ignored) {
            // The accessibility connection may already have removed its windows.
        }
    }

    /** Geometry is controller-owned so screenshot detach/restore preserves motion. */
    private static final class TargetVisualState {
        final Rect bounds = new Rect();
        final Rect contourBounds = new Rect();
        final Rect presentationBounds = new Rect();
        final Rect focusedInputBounds = new Rect();
        final Region presentationRegion = new Region();
        final Path outline = new Path();
        int windowId = -1;
        int displayId = Display.DEFAULT_DISPLAY;
        int displayWidth;
        int displayHeight;
        float density = 1f;
        long generation;
        boolean valid;

        void set(int newWindowId, int newDisplayId, Rect newBounds, Region newRegion, Rect newFocusedInput,
                 int newDisplayWidth, int newDisplayHeight, float newDensity) {
            if (valid && windowId == newWindowId && displayId == newDisplayId
                    && bounds.equals(newBounds) && presentationRegion.equals(newRegion)
                    && focusedInputBounds.equals(newFocusedInput)
                    && displayWidth == newDisplayWidth && displayHeight == newDisplayHeight
                    && Float.compare(density, Math.max(0.5f, newDensity)) == 0) return;
            windowId = newWindowId;
            displayId = newDisplayId;
            bounds.set(newBounds);
            presentationRegion.set(newRegion);
            presentationBounds.set(presentationRegion.getBounds());
            focusedInputBounds.set(newFocusedInput);
            contourBounds.set(presentationBounds);
            displayWidth = newDisplayWidth;
            displayHeight = newDisplayHeight;
            density = Math.max(0.5f, newDensity);
            valid = windowId >= 0 && !bounds.isEmpty() && !contourBounds.isEmpty()
                    && !presentationRegion.isEmpty();
            outline.reset();
            if (valid) {
                if (!contourBounds.intersect(0, 0, displayWidth, displayHeight)) {
                    valid = false;
                } else {
                    long visibleArea = 0L;
                    RegionIterator iterator = new RegionIterator(presentationRegion);
                    Rect piece = new Rect();
                    while (iterator.next(piece)) {
                        if (piece.intersect(contourBounds)) visibleArea += (long) piece.width() * piece.height();
                    }
                    TargetFrameGeometry frame = new TargetFrameGeometry(contourBounds.left, contourBounds.top,
                            contourBounds.right, contourBounds.bottom, visibleArea, density);
                    buildFramePath(outline, frame);
                }
            }
            generation++;
        }

        void clear() {
            valid = false;
            windowId = -1;
            bounds.setEmpty();
            contourBounds.setEmpty();
            presentationBounds.setEmpty();
            focusedInputBounds.setEmpty();
            presentationRegion.setEmpty();
            outline.reset();
            generation++;
        }

        private static void buildFramePath(Path path, TargetFrameGeometry frame) {
            if (!frame.valid()) return;
            if (!frame.cornersOnly) {
                path.addRoundRect(new RectF(frame.left, frame.top, frame.right, frame.bottom),
                        frame.radius, frame.radius, Path.Direction.CW);
                return;
            }
            float l = frame.left, t = frame.top, r = frame.right, b = frame.bottom;
            float radius = frame.radius, length = frame.cornerLength;
            path.moveTo(l, t + length); path.lineTo(l, t + radius);
            path.quadTo(l, t, l + radius, t); path.lineTo(l + length, t);
            path.moveTo(r - length, t); path.lineTo(r - radius, t);
            path.quadTo(r, t, r, t + radius); path.lineTo(r, t + length);
            path.moveTo(r, b - length); path.lineTo(r, b - radius);
            path.quadTo(r, b, r - radius, b); path.lineTo(r - length, b);
            path.moveTo(l + length, b); path.lineTo(l + radius, b);
            path.quadTo(l, b, l, b - radius); path.lineTo(l, b - length);
        }
    }

    /** Geometry is controller-owned so screenshot detach/restore preserves motion. */
    private static final class VisualCoordinates {
        private static final float SPRITE_SOURCE_SIZE = 256f;
        private static final float[] WRIST_X = {0f, 132f, 128f, 160f};
        private static final float[] WRIST_Y = {0f, 214f, 224f, 196f};
        private static final float[] HOTSPOT_X = {0f, 109f, 128f, 27f};
        private static final float[] HOTSPOT_Y = {0f, 22f, 145f, 91f};

        float travelFromX;
        float travelFromY;
        float travelToX;
        float travelToY;
        float actionToX;
        float actionToY;
        float actionPointX;
        float actionPointY;
        long travelStartedAtMs;
        long actionStartedAtMs = Long.MAX_VALUE;
        int travelDurationMs;
        int actionDurationMs;
        int currentPose = AgentVisualModel.GLOVE_POSE_POINT;
        int targetPose = AgentVisualModel.GLOVE_POSE_POINT;
        boolean initialized;
        boolean prepared;

        long prepareAction(AgentVisualModel model, int pose,
                float fromX, float fromY, float toX, float toY, int actionDuration,
                int displayWidth, int displayHeight, float density, long nowMs) {
            if (!model.hasCursorSession()) model.beginCursorSession(currentPose, nowMs);
            if (!initialized || model.cursorNeedsCenterPlacement()) {
                travelFromX = displayWidth * 0.5f;
                travelFromY = displayHeight * 0.5f;
                initialized = true;
            } else {
                float[] current = wristAt(nowMs);
                travelFromX = current[0];
                travelFromY = current[1];
            }
            float scale = 58f * density / SPRITE_SOURCE_SIZE;
            travelToX = fromX - (HOTSPOT_X[pose] - WRIST_X[pose]) * scale;
            travelToY = fromY - (HOTSPOT_Y[pose] - WRIST_Y[pose]) * scale;
            actionToX = toX - (HOTSPOT_X[pose] - WRIST_X[pose]) * scale;
            actionToY = toY - (HOTSPOT_Y[pose] - WRIST_Y[pose]) * scale;
            actionPointX = toX;
            actionPointY = toY;
            targetPose = pose;
            prepared = true;
            actionDurationMs = Math.max(0, actionDuration);
            actionStartedAtMs = Long.MAX_VALUE;
            travelStartedAtMs = nowMs;
            float dx = travelToX - travelFromX;
            float dy = travelToY - travelFromY;
            float diagonal = (float) Math.hypot(displayWidth, displayHeight);
            travelDurationMs = (int) model.moveCursorPixels(currentPose,
                    (float) Math.hypot(dx, dy), diagonal, nowMs);
            return travelDurationMs;
        }

        boolean beginPreparedAction(AgentVisualModel model, long nowMs) {
            if (!prepared || !model.hasCursorSession()) return false;
            prepared = false;
            currentPose = targetPose;
            long visibleActionMs = actionDurationMs <= 0
                    ? AgentVisualModel.CURSOR_ACTION_MS : actionDurationMs;
            model.commitCursorAction(targetPose, visibleActionMs, nowMs);
            actionStartedAtMs = nowMs;
            return true;
        }

        float[] wristAt(long nowMs) {
            float[] result = new float[2];
            wristAt(nowMs, result);
            return result;
        }

        void wristAt(long nowMs, float[] result) {
            if (!initialized) {
                result[0] = 0f;
                result[1] = 0f;
                return;
            }
            if (nowMs < actionStartedAtMs) {
                float progress = MotionEasing.doubleSmoothstep((nowMs - travelStartedAtMs)
                        / (float) Math.max(1, travelDurationMs));
                result[0] = mix(travelFromX, travelToX, progress);
                result[1] = mix(travelFromY, travelToY, progress);
                return;
            }
            float progress = actionDurationMs <= 0 ? 1f : MotionEasing.doubleSmoothstep(
                    (nowMs - actionStartedAtMs) / (float) actionDurationMs);
            result[0] = mix(travelToX, actionToX, progress);
            result[1] = mix(travelToY, actionToY, progress);
        }

        boolean isAnimating(long nowMs) {
            if (!initialized) return false;
            if (nowMs < actionStartedAtMs) {
                return nowMs < travelStartedAtMs + travelDurationMs;
            }
            return nowMs < actionStartedAtMs + actionDurationMs;
        }

        void clear() {
            initialized = false;
            actionStartedAtMs = Long.MAX_VALUE;
            currentPose = AgentVisualModel.GLOVE_POSE_POINT;
            targetPose = AgentVisualModel.GLOVE_POSE_POINT;
            prepared = false;
        }

        private static float mix(float start, float end, float amount) {
            return start + (end - start) * amount;
        }
    }

    private static final class IndicatorView extends View {
        private static final int ICON_BACKGROUND = Color.rgb(0, 17, 40);
        private static final int ICON_FOREGROUND = Color.rgb(0, 241, 239);
        private final Paint gradientPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final AgentVisualModel visualModel;
        private int edge = EDGE_TOP;
        private Shader edgeGradient;
        private Shader cornerGradientA;
        private Shader cornerGradientB;
        private float cornerAX;
        private float cornerAY;
        private float cornerBX;
        private float cornerBY;
        private float cornerRadius;

        IndicatorView(AccessibilityService service, AgentVisualModel visualModel) {
            super(service);
            this.visualModel = visualModel;
            setWillNotDraw(false);
        }

        void setEdge(int value) {
            edge = value;
            rebuildShaders(getWidth(), getHeight());
            invalidate();
        }

        void stopAnimation() {
            // Timing lives in AgentVisualModel, so detach/restore cannot reset phase.
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            int height = getHeight();
            if (width <= 0 || height <= 0) return;

            drawInnerGlow(canvas, width, height);
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            rebuildShaders(width, height);
        }

        private void rebuildShaders(int width, int height) {
            if (width <= 0 || height <= 0) return;
            int[] edgeColors = {
                    Color.argb(150, Color.red(ICON_BACKGROUND), Color.green(ICON_BACKGROUND), Color.blue(ICON_BACKGROUND)),
                    Color.argb(178, Color.red(ICON_FOREGROUND), Color.green(ICON_FOREGROUND), Color.blue(ICON_FOREGROUND)),
                    Color.argb(56, Color.red(ICON_FOREGROUND), Color.green(ICON_FOREGROUND), Color.blue(ICON_FOREGROUND)),
                    Color.TRANSPARENT
            };
            float[] edgeStops = {0f, 0.10f, 0.42f, 1f};
            if (edge == EDGE_TOP) {
                edgeGradient = new LinearGradient(0f, 0f, 0f, height, edgeColors, edgeStops, Shader.TileMode.CLAMP);
            } else if (edge == EDGE_BOTTOM) {
                edgeGradient = new LinearGradient(0f, height, 0f, 0f, edgeColors, edgeStops, Shader.TileMode.CLAMP);
            } else if (edge == EDGE_LEFT) {
                edgeGradient = new LinearGradient(0f, 0f, width, 0f, edgeColors, edgeStops, Shader.TileMode.CLAMP);
            } else {
                edgeGradient = new LinearGradient(width, 0f, 0f, 0f, edgeColors, edgeStops, Shader.TileMode.CLAMP);
            }

            float shortSide = Math.min(width, height);
            cornerRadius = Math.max(1f, shortSide * 2.25f);
            if (edge == EDGE_TOP || edge == EDGE_BOTTOM) {
                cornerAX = 0f;
                cornerAY = edge == EDGE_TOP ? 0f : height;
                cornerBX = width;
                cornerBY = cornerAY;
            } else {
                cornerAX = edge == EDGE_LEFT ? 0f : width;
                cornerAY = height * 0.32f;
                cornerBX = cornerAX;
                cornerBY = height * 0.72f;
            }
            cornerGradientA = makeCornerGradient(cornerAX, cornerAY, cornerRadius);
            cornerGradientB = makeCornerGradient(cornerBX, cornerBY, cornerRadius);
        }

        private Shader makeCornerGradient(float x, float y, float radius) {
            return new RadialGradient(
                    x, y, radius,
                    new int[]{
                            Color.argb(150, Color.red(ICON_FOREGROUND), Color.green(ICON_FOREGROUND), Color.blue(ICON_FOREGROUND)),
                            Color.argb(52, Color.red(ICON_FOREGROUND), Color.green(ICON_FOREGROUND), Color.blue(ICON_FOREGROUND)),
                            Color.TRANSPARENT
                    },
                    new float[]{0f, 0.26f, 1f}, Shader.TileMode.CLAMP);
        }

        private void drawInnerGlow(Canvas canvas, int width, int height) {
            long now = SystemClock.uptimeMillis();
            float phase = (now % 8_000L) / 8_000f * (float) (Math.PI * 2.0);
            float idlePulse = 0.78f + 0.22f * (float) Math.sin(phase);
            float idleAmount = edge == EDGE_TOP ? 0.18f * idlePulse : 0f;
            float amount = idleAmount;
            if (edgeGradient == null) rebuildShaders(width, height);
            gradientPaint.setShader(edgeGradient);
            gradientPaint.setAlpha(Math.round(255f * amount));
            canvas.drawRect(0f, 0f, width, height, gradientPaint);

            float cornerPulseA = 0.58f + 0.42f * (float) Math.sin(phase * 1.15f + 1.3f);
            float cornerPulseB = 0.60f + 0.40f * (float) Math.sin(phase * 0.94f + 3.7f);
            // Target activity uses the window contour in ActivityView; the top strip
            // remains a quiet connection indicator without additive corner hotspots.
            if (edge == EDGE_TOP || visualModel.isActivityVisible(now)) postInvalidateOnAnimation();
        }

        private void drawCornerGlow(Canvas canvas, Shader shader, float x, float y, float amount) {
            if (shader == null || amount <= 0f) return;
            glowPaint.setShader(shader);
            glowPaint.setAlpha(Math.round(255f * Math.min(1f, amount)));
            canvas.drawCircle(x, y, cornerRadius, glowPaint);
        }
    }

    /** Bottom-left, touch-through captions describing the agent's current intent. */
    private static final class ActivityView extends View {
        private static final int TEXT_FILL = Color.rgb(242, 244, 247);
        private static final int TEXT_GLOW = Color.rgb(0, 241, 239);
        private static final int PANEL_FILL = Color.rgb(27, 30, 35);
        private static final long CAPTION_FRAME_MS = 32L;

        private final AgentActivityModel model;
        private final TaskProgressModel progressModel;
        private final CompletionMessageModel completionModel;
        private final AgentVisualModel visualModel;
        private final VisualCoordinates visualCoordinates;
        private final Runnable emptyCallback;
        private final PerformanceMetrics performanceMetrics;
        private final TextPaint renderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint activityShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint panelBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF activityPanelRect = new RectF();
        private final ActivityPanelLayout panelLayout = new ActivityPanelLayout();
        private final int[] captionHeights = new int[AgentActivityModel.MAX_MESSAGES];
        private final float progressRowHeightPx;
        private final TextPaint completionPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final float baseTextSize;
        private final Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint scanCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gloveBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint gloveGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint targetOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint observationFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final TextPaint progressPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint progressIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path progressCheckPath = new Path();
        private final RectF caretRect = new RectF();
        private final RectF progressArcRect = new RectF();
        private final RectF gloveRect = new RectF();
        private final RectF gloveGlowRect = new RectF();
        private final float[] gloveWrist = new float[2];
        private final TargetVisualState targetVisualState;
        private final Bitmap pointGloveBitmap;
        private final Bitmap swipeGloveBitmap;
        private final Bitmap backGloveBitmap;
        private final Map<AgentActivityModel.Message, RowLayoutCache> rowLayouts = new IdentityHashMap<>();
        private final StaticLayout[] visibleFullLayouts = new StaticLayout[AgentActivityModel.MAX_MESSAGES];
        private final float density;
        private final float spinnerIndentPx;
        private final float caretWidthPx;
        private final float caretHeightPx;
        private final int caretSlotWidthPx;
        private int cachedTextWidth = -1;
        private Bitmap scanGlowBitmap;
        private int scanBitmapWidth = -1;
        private int scanBitmapHeight = -1;
        private CompletionMessageModel.Message completionLayoutMessage;
        private int completionLayoutWidth = -1;
        private int completionLayoutHeight = -1;
        private StaticLayout completionLayout;
        private float animatedPanelTop = Float.NaN;
        private long panelDrawAtMs;
        private long targetShaderGeneration = -1L;
        private boolean running = true;
        private boolean emptyCallbackPosted;

        ActivityView(Context context, AgentActivityModel model,
                TaskProgressModel progressModel,
                CompletionMessageModel completionModel,
                AgentVisualModel visualModel, VisualCoordinates visualCoordinates,
                TargetVisualState targetVisualState, Runnable emptyCallback,
                PerformanceMetrics performanceMetrics) {
            super(context);
            this.model = model;
            this.progressModel = progressModel;
            this.completionModel = completionModel;
            this.visualModel = visualModel;
            this.visualCoordinates = visualCoordinates;
            this.targetVisualState = targetVisualState;
            this.density = context.getResources().getDisplayMetrics().density;
            this.emptyCallback = emptyCallback;
            this.performanceMetrics = performanceMetrics;
            float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
            float textSize = 20f * scaledDensity;
            baseTextSize = textSize;
            renderPaint.setTextSize(textSize);
            renderPaint.setStrokeJoin(Paint.Join.ROUND);
            renderPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            completionPaint.set(renderPaint);
            progressPaint.setTextSize(16f * scaledDensity);
            progressPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            progressPaint.setColor(TEXT_FILL);
            progressRowHeightPx = Math.max(28f * density,
                    progressPaint.descent() - progressPaint.ascent() + 8f * density);
            progressIconPaint.setColor(Color.rgb(171, 190, 205));
            progressIconPaint.setStyle(Paint.Style.STROKE);
            progressIconPaint.setStrokeWidth(Math.max(1.5f, 1.6f * density));
            progressIconPaint.setStrokeCap(Paint.Cap.ROUND);
            progressIconPaint.setStrokeJoin(Paint.Join.ROUND);
            spinnerIndentPx = renderPaint.measureText(AgentActivityModel.SPINNER_FRAMES[0] + " ");
            caretWidthPx = 10f * density;
            caretHeightPx = 3f * density;
            caretSlotWidthPx = Math.max(1, Math.round(caretWidthPx + 2f * density));
            caretPaint.setStyle(Paint.Style.FILL);
            caretPaint.setColor(TEXT_FILL);
            activityShadePaint.setStyle(Paint.Style.FILL);
            panelBorderPaint.setStyle(Paint.Style.STROKE);
            panelBorderPaint.setStrokeWidth(Math.max(1f, 0.7f * density));
            scanGlowPaint.setStyle(Paint.Style.FILL);
            scanCorePaint.setStrokeWidth(Math.max(1.5f, 2f * density));
            scanCorePaint.setStrokeCap(Paint.Cap.ROUND);
            pointGloveBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.agent_glove_point);
            swipeGloveBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.agent_glove_swipe);
            backGloveBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.agent_glove_back);
            gloveGlowPaint.setColorFilter(new PorterDuffColorFilter(TEXT_GLOW, PorterDuff.Mode.SRC_IN));
            targetPaint.setColor(TEXT_GLOW);
            targetPaint.setStyle(Paint.Style.STROKE);
            targetPaint.setStrokeWidth(1.25f * density);
            targetOutlinePaint.setStyle(Paint.Style.STROKE);
            targetOutlinePaint.setStrokeCap(Paint.Cap.ROUND);
            targetOutlinePaint.setStrokeJoin(Paint.Join.ROUND);
            observationFramePaint.setStyle(Paint.Style.STROKE);
            observationFramePaint.setStrokeCap(Paint.Cap.ROUND);
            observationFramePaint.setStrokeJoin(Paint.Join.ROUND);
            setWillNotDraw(false);
        }

        void noteNewMessage() {
            emptyCallbackPosted = false;
            running = true;
            invalidate();
        }

        void noteProgressChanged() {
            emptyCallbackPosted = false;
            running = true;
            invalidate();
        }

        void noteCompletionChanged() {
            completionLayoutMessage = null;
            completionLayout = null;
            emptyCallbackPosted = false;
            running = true;
            invalidate();
        }

        void stopAnimation() {
            running = false;
        }

        @Override
        protected void onDetachedFromWindow() {
            recycleScanGlowBitmap();
            super.onDetachedFromWindow();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            long drawStartedNanos = System.nanoTime();
            long now = SystemClock.uptimeMillis();
            List<AgentActivityModel.Message> messages = model.snapshot(now);
            List<TaskProgressModel.Row> progressRows = progressModel.snapshot();
            CompletionMessageModel.Message completion = completionModel.current(now);
            int width = getWidth();
            int height = getHeight();
            drawTargetWindowGlow(canvas, now);
            int left = Math.round(24f * density);
            int panelWidth = Math.max(1, Math.min(width - Math.round(24f * density), Math.round(560f * density)));
            int textWidth = Math.max(1, panelWidth - Math.round(24f * density));
            int bodyWidth = Math.max(1, textWidth - Math.round(spinnerIndentPx));
            if (cachedTextWidth != bodyWidth) {
                cachedTextWidth = bodyWidth;
                rowLayouts.clear();
            }
            rowLayouts.keySet().retainAll(messages);
            for (int i = 0; i < messages.size(); i++) {
                visibleFullLayouts[i] = cacheFor(messages.get(i), bodyWidth, i == messages.size() - 1).fullLayout;
                captionHeights[i] = visibleFullLayouts[i].getHeight();
            }
            float contentBottom = height - 56f * density;
            if (targetVisualState.valid && !targetVisualState.presentationBounds.isEmpty()
                    && targetVisualState.presentationBounds.bottom < height - 120f * density) {
                // Keep the input method and a smaller target window's bottom controls visible.
                contentBottom = Math.min(contentBottom,
                        targetVisualState.presentationBounds.bottom - 20f * density);
                if (!targetVisualState.focusedInputBounds.isEmpty()) contentBottom = Math.min(contentBottom,
                        targetVisualState.focusedInputBounds.top - 24f * density);
            }
            float minimumTop = Math.min(96f * density, height * 0.3f);
            int availableHeight = Math.max(1, Math.round(contentBottom - minimumTop));
            StaticLayout fullCompletionLayout = completion == null || availableHeight < 28f * density ? null
                    : completionLayout(completion, textWidth, availableHeight, now);
            if (fullCompletionLayout != null && fullCompletionLayout.getHeight() > availableHeight)
                fullCompletionLayout = null;
            float completionY = fullCompletionLayout == null ? contentBottom
                    : contentBottom - fullCompletionLayout.getHeight();
            int activePlanRow = 0;
            for (int i = 0; i < progressRows.size(); i++) {
                if (progressRows.get(i).kind == TaskProgressModel.KIND_IN_PROGRESS) activePlanRow = i;
            }
            panelLayout.measure(minimumTop, contentBottom, 12f * density, 12f * density,
                    captionHeights, messages.size(), progressRows.size(), activePlanRow, progressRowHeightPx,
                    fullCompletionLayout == null ? 0 : fullCompletionLayout.getHeight());
            boolean hasPresentation = !messages.isEmpty() || !progressRows.isEmpty() || completion != null;
            drawObservationEffect(canvas, width, height, now, hasPresentation);
            AgentVisualModel.CursorFrame cursorFrame = visualModel.cursorFrame(now);
            drawGlove(canvas, now, cursorFrame);
            float backgroundAlpha = visualModel.backdropEnvelope(now);
            if (!progressRows.isEmpty()) backgroundAlpha = 1f;
            if (completion != null) backgroundAlpha = CompletionMessageModel.alpha(completion, now);
            if (panelLayout.visible) {
                drawActivityBackground(canvas, backgroundAlpha, panelWidth, now);
            }
            if (!hasPresentation) {
                if (!visualModel.isActivityVisible(now) && !visualModel.observationActive(now)
                        && cursorFrame == null && !emptyCallbackPosted) {
                    emptyCallbackPosted = true;
                    post(emptyCallback);
                }
                scheduleNextFrame(now, width, height, cursorFrame, false, height);
                performanceMetrics.recordActivityDraw(now, System.nanoTime() - drawStartedNanos);
                return;
            }
            emptyCallbackPosted = false;
            if (fullCompletionLayout != null) {
                int saved = canvas.save();
                canvas.translate(left, completionY);
                drawCaption(canvas, fullCompletionLayout, CompletionMessageModel.alpha(completion, now), completionPaint);
                canvas.restoreToCount(saved);
            } else {
                for (int i = panelLayout.firstCaption; i < messages.size(); i++) {
                    AgentActivityModel.Message message = messages.get(i);
                    int ageRank = messages.size() - 1 - i;
                    boolean newest = ageRank == 0;
                    float alpha = AgentActivityModel.alpha(message, ageRank, now);
                    if (alpha <= 0f) continue;
                    String visible = AgentActivityModel.displayBodyText(message, now, newest);
                    float y = panelLayout.captionY[i];
                    RowLayoutCache cache = cacheFor(message, bodyWidth, newest);
                    StaticLayout visibleLayout = cache.visibleLayout(
                            visible, renderPaint, bodyWidth, performanceMetrics, now);
                    int saved = canvas.save();
                    canvas.translate(left + spinnerIndentPx, y);
                    drawCaption(canvas, visibleLayout, alpha);
                    if (newest) drawCaret(canvas, visibleLayout, visible.length(),
                            alpha * AgentActivityModel.caretAlpha(message, now));
                    canvas.restoreToCount(saved);
                    if (newest) drawSpinner(canvas, AgentActivityModel.spinnerFrame(now), left,
                            y + visibleLayout.getLineBaseline(0), alpha);
                }
                float planTop = panelLayout.planTop;
                if (panelLayout.visible) drawTaskProgress(canvas, progressRows, left, textWidth, planTop, now);
            }
            scheduleNextFrame(now, width, height, cursorFrame, hasPresentation,
                    Math.round(Math.min(panelLayout.top, animatedPanelTop)));
            performanceMetrics.recordActivityDraw(now, System.nanoTime() - drawStartedNanos);
        }

        private void drawTargetWindowGlow(Canvas canvas, long now) {
            if (!targetVisualState.valid || !visualModel.isActivityVisible(now)) return;
            Rect bounds = targetVisualState.contourBounds;
            if (bounds.isEmpty()) return;
            float breath = TargetGlowModel.breath(now);
            float intensity = TargetGlowModel.intensity(visualModel.activityEnvelope(now), breath);
            if (targetShaderGeneration != targetVisualState.generation) {
                targetShaderGeneration = targetVisualState.generation;
                targetOutlinePaint.setShader(new LinearGradient(
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        new int[]{Color.rgb(67, 210, 196), Color.rgb(92, 154, 222),
                                Color.rgb(157, 126, 204), Color.rgb(67, 210, 196)},
                        new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
            }
            targetOutlinePaint.setStrokeWidth(10f * targetVisualState.density);
            targetOutlinePaint.setAlpha(Math.round(10f * intensity));
            canvas.drawPath(targetVisualState.outline, targetOutlinePaint);
            targetOutlinePaint.setStrokeWidth(4f * targetVisualState.density);
            targetOutlinePaint.setAlpha(Math.round(24f * intensity));
            canvas.drawPath(targetVisualState.outline, targetOutlinePaint);
            targetOutlinePaint.setStrokeWidth(1f * targetVisualState.density);
            targetOutlinePaint.setAlpha(Math.round(64f * intensity));
            canvas.drawPath(targetVisualState.outline, targetOutlinePaint);
        }

        private void drawObservationEffect(Canvas canvas, int width, int height, long now, boolean hasCaptions) {
            float alpha = visualModel.observationFrameAlpha(now);
            if (alpha > 0f && targetVisualState.valid) {
                observationFramePaint.setColor(Color.rgb(186, 234, 242));
                observationFramePaint.setStrokeWidth(12f * targetVisualState.density);
                observationFramePaint.setAlpha(Math.round(24f * alpha));
                canvas.drawPath(targetVisualState.outline, observationFramePaint);
                observationFramePaint.setStrokeWidth(6f * targetVisualState.density);
                observationFramePaint.setAlpha(Math.round(60f * alpha));
                canvas.drawPath(targetVisualState.outline, observationFramePaint);
                observationFramePaint.setColor(Color.rgb(76, 205, 224));
                observationFramePaint.setStrokeWidth(1.6f * targetVisualState.density);
                observationFramePaint.setAlpha(Math.round(220f * alpha));
                canvas.drawPath(targetVisualState.outline, observationFramePaint);
            }
            drawObservationScan(canvas, width, height, now, hasCaptions);
        }

        private void drawTaskProgress(Canvas canvas, List<TaskProgressModel.Row> rows,
                float left, int availableWidth, float top, long now) {
            if (rows.isEmpty()) return;
            float rowHeight = progressRowHeightPx;
            float iconCenterX = left + 9f * density;
            float textLeft = left + 29f * density;
            float textWidth = Math.max(1f, availableWidth - (textLeft - left));
            progressIconPaint.setAlpha(214);
            if (panelLayout.planCount > 1) {
                float firstY = top + rowHeight * 0.5f;
                float lastY = top + (panelLayout.planCount - 0.5f) * rowHeight;
                progressIconPaint.setAlpha(68);
                canvas.drawLine(iconCenterX, firstY, iconCenterX, lastY, progressIconPaint);
            }
            for (int i = 0; i < panelLayout.planCount; i++) {
                TaskProgressModel.Row row = rows.get(panelLayout.planIndices[i]);
                float centerY = top + (i + 0.5f) * rowHeight;
                drawProgressIcon(canvas, row.kind, iconCenterX, centerY, now);
                String visible = TaskProgressModel.visibleText(row, now);
                String overflow = panelLayout.hiddenPlanRows > 0 && !panelLayout.showPlanSummary
                        ? " +" + panelLayout.hiddenPlanRows : "";
                float overflowWidth = progressPaint.measureText(overflow);
                CharSequence fitted = TextUtils.ellipsize(
                        visible, progressPaint, Math.max(1f, textWidth - overflowWidth), TextUtils.TruncateAt.END);
                progressPaint.setAlpha(row.kind == TaskProgressModel.KIND_COMPLETED ? 184 : 255);
                float baseline = centerY - (progressPaint.ascent() + progressPaint.descent()) * 0.5f;
                canvas.drawText(fitted, 0, fitted.length(), textLeft, baseline, progressPaint);
                if (!overflow.isEmpty()) canvas.drawText(overflow, textLeft + textWidth - overflowWidth, baseline, progressPaint);
            }
            if (panelLayout.showPlanSummary) {
                progressPaint.setAlpha(184);
                float centerY = panelLayout.summaryY + rowHeight * 0.5f;
                float baseline = centerY - (progressPaint.ascent() + progressPaint.descent()) * 0.5f;
                boolean goalVisible = panelLayout.planCount > 0 && panelLayout.planIndices[0] == 0;
                String label = goalVisible
                        ? (panelLayout.hiddenPlanRows == 1 ? " more step" : " more steps")
                        : (panelLayout.hiddenPlanRows == 1 ? " more item" : " more items");
                CharSequence summary = TextUtils.ellipsize(panelLayout.hiddenPlanRows + label,
                        progressPaint, textWidth, TextUtils.TruncateAt.END);
                canvas.drawText(summary, 0, summary.length(), textLeft, baseline, progressPaint);
            }
        }

        private void drawProgressIcon(Canvas canvas, int kind, float x, float y, long now) {
            float radius = 6.5f * density;
            progressIconPaint.setAlpha(kind == TaskProgressModel.KIND_COMPLETED ? 184 : 255);
            progressIconPaint.setStyle(Paint.Style.STROKE);
            if (kind == TaskProgressModel.KIND_GOAL) {
                canvas.drawCircle(x, y, radius, progressIconPaint);
                progressIconPaint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(x, y, 1.8f * density, progressIconPaint);
            } else if (kind == TaskProgressModel.KIND_COMPLETED) {
                canvas.drawCircle(x, y, radius, progressIconPaint);
                progressCheckPath.reset();
                progressCheckPath.moveTo(x - 3.2f * density, y);
                progressCheckPath.lineTo(x - 0.7f * density, y + 2.7f * density);
                progressCheckPath.lineTo(x + 4f * density, y - 3f * density);
                canvas.drawPath(progressCheckPath, progressIconPaint);
            } else if (kind == TaskProgressModel.KIND_IN_PROGRESS) {
                progressArcRect.set(x - radius, y - radius, x + radius, y + radius);
                float rotation = (now % 1_200L) / 1_200f * 360f;
                canvas.drawArc(progressArcRect, rotation, 245f, false, progressIconPaint);
                progressIconPaint.setStyle(Paint.Style.FILL);
                double radians = Math.toRadians(rotation + 245f);
                canvas.drawCircle(x + (float) Math.cos(radians) * radius,
                        y + (float) Math.sin(radians) * radius,
                        1.7f * density, progressIconPaint);
            } else if (kind == TaskProgressModel.KIND_PENDING) {
                progressIconPaint.setAlpha(112);
                canvas.drawCircle(x, y, radius, progressIconPaint);
            }
        }

        private void scheduleNextFrame(long now, int width, int height,
                                       AgentVisualModel.CursorFrame cursorFrame,
                                       boolean hasMessages, int presentationTop) {
            if (!running) return;
            boolean fullScreenMotion = visualModel.observationActive(now)
                    || visualCoordinates.isAnimating(now)
                    || cursorFrame != null && (cursorFrame.phase != AgentVisualModel.CURSOR_PHASE_IDLE
                    || cursorFrame.phaseProgress < 1f);
            if (fullScreenMotion) {
                postInvalidateOnAnimation();
                return;
            }
            if (visualModel.isActivityVisible(now)) {
                postInvalidateDelayed(CAPTION_FRAME_MS);
            } else if (hasMessages || visualModel.isBackdropAnimating(now)) {
                int dirtyTop = Math.max(0, presentationTop - Math.round(8f * density));
                postInvalidateDelayed(CAPTION_FRAME_MS, 0, dirtyTop, width, height);
            }
        }

        private void drawActivityBackground(Canvas canvas, float rowAlpha, int panelWidth, long now) {
            float targetTop = panelLayout.top;
            if (Float.isNaN(animatedPanelTop) || targetTop < animatedPanelTop) animatedPanelTop = targetTop;
            else animatedPanelTop += (targetTop - animatedPanelTop)
                    * Math.min(1f, Math.max(0L, now - panelDrawAtMs) / 120f);
            panelDrawAtMs = now;
            float radius = 18f * density;
            float left = 12f * density;
            float right = left + panelWidth;
            activityPanelRect.set(left, animatedPanelTop + 3f * density, right, panelLayout.bottom + 3f * density);
            activityShadePaint.setColor(Color.BLACK);
            activityShadePaint.setAlpha(Math.round(22f * rowAlpha));
            canvas.drawRoundRect(activityPanelRect, radius, radius, activityShadePaint);
            activityPanelRect.set(left, animatedPanelTop, right, panelLayout.bottom);
            activityShadePaint.setColor(PANEL_FILL);
            activityShadePaint.setAlpha(Math.round(240f * rowAlpha));
            canvas.drawRoundRect(activityPanelRect, radius, radius, activityShadePaint);
            panelBorderPaint.setColor(Color.WHITE);
            panelBorderPaint.setAlpha(Math.round(30f * rowAlpha));
            canvas.drawRoundRect(activityPanelRect, radius, radius, panelBorderPaint);
        }

        private void drawObservationScan(Canvas canvas, int width, int height, long now,
                boolean hasCaptions) {
            float position = visualModel.scanPosition(now);
            if (position < 0f) return;
            float y = position * height;
            float safeTop = height - (hasCaptions ? 300f : 150f) * density;
            float fadeStart = safeTop - 72f * density;
            float attenuation = y <= fadeStart ? 1f
                    : Math.max(0f, (safeTop - y) / Math.max(1f, safeTop - fadeStart));
            if (attenuation <= 0f) return;
            float edgeFade = Math.min(1f, Math.min(position * 7f, (1f - position) * 7f));
            int alpha = Math.round(attenuation * edgeFade * 255f);
            ensureScanGlowBitmap(width);
            scanGlowPaint.setAlpha(Math.round(alpha * 0.58f));
            scanCorePaint.setAlpha(Math.round(alpha * 0.92f));
            float inset = 18f * density;
            if (scanGlowBitmap != null) {
                canvas.drawBitmap(scanGlowBitmap, inset,
                        y - scanGlowBitmap.getHeight() * 0.5f, scanGlowPaint);
            }
            canvas.drawLine(inset, y, width - inset, y, scanCorePaint);
        }

        private void ensureScanGlowBitmap(int viewWidth) {
            int inset = Math.max(0, Math.round(18f * density));
            int width = Math.max(1, viewWidth - inset * 2);
            int height = Math.max(3, Math.round(36f * density));
            if (scanGlowBitmap != null && scanBitmapWidth == width
                    && scanBitmapHeight == height) return;
            recycleScanGlowBitmap();
            int[] pixels;
            try {
                scanGlowBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                pixels = new int[Math.multiplyExact(width, height)];
            } catch (RuntimeException | OutOfMemoryError ignored) {
                recycleScanGlowBitmap();
                return;
            }
            for (int row = 0; row < height; row++) {
                int alpha = Math.round(255f * TargetGlowModel.scanFadeAlpha(
                        (row + 0.5f) / height));
                for (int column = 0; column < width; column++) {
                    int color = TargetGlowModel.gradientColor(
                            width <= 1 ? 0f : column / (float) (width - 1));
                    pixels[row * width + column] = (alpha << 24) | (color & 0x00ffffff);
                }
            }
            scanGlowBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            scanBitmapWidth = width;
            scanBitmapHeight = height;
            float left = 18f * density;
            scanCorePaint.setShader(new LinearGradient(left, 0f, viewWidth - left, 0f,
                    new int[]{
                            Color.rgb(67, 210, 196),
                            Color.rgb(92, 154, 222),
                            Color.rgb(157, 126, 204),
                            Color.rgb(67, 210, 196)
                    }, new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP));
        }

        private void recycleScanGlowBitmap() {
            Bitmap bitmap = scanGlowBitmap;
            scanGlowBitmap = null;
            scanBitmapWidth = -1;
            scanBitmapHeight = -1;
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }

        private void drawGlove(Canvas canvas, long now, AgentVisualModel.CursorFrame frame) {
            if (frame == null) return;
            visualCoordinates.wristAt(now, gloveWrist);
            int alpha = Math.max(0, Math.min(255, Math.round(frame.alpha * 255f)));
            drawGloveBitmap(canvas, frame.pose, gloveWrist[0], gloveWrist[1], alpha);

            if (frame.pose == AgentVisualModel.GLOVE_POSE_POINT && frame.actionAmount > 0f) {
                targetPaint.setAlpha(Math.round(alpha * frame.actionAmount * 0.75f));
                float radius = (5.5f + 6f * frame.actionAmount) * density;
                canvas.drawCircle(visualCoordinates.actionPointX,
                        visualCoordinates.actionPointY, radius, targetPaint);
            }
        }

        private void drawGloveBitmap(Canvas canvas, int pose, float wristX, float wristY, int alpha) {
            if (alpha <= 0) return;
            Bitmap bitmap = pose == AgentVisualModel.GLOVE_POSE_OPEN_PALM
                    ? swipeGloveBitmap
                    : pose == AgentVisualModel.GLOVE_POSE_BACK_LEFT
                    ? backGloveBitmap : pointGloveBitmap;
            if (bitmap == null) return;
            float size = 58f * density;
            float sourceSize = VisualCoordinates.SPRITE_SOURCE_SIZE;
            float left = wristX - VisualCoordinates.WRIST_X[pose] / sourceSize * size;
            float top = wristY - VisualCoordinates.WRIST_Y[pose] / sourceSize * size;
            gloveRect.set(left, top, left + size, top + size);
            float glowOutset = 2.25f * density;
            gloveGlowRect.set(gloveRect.left - glowOutset, gloveRect.top - glowOutset,
                    gloveRect.right + glowOutset, gloveRect.bottom + glowOutset);
            gloveGlowPaint.setAlpha(Math.round(alpha * 0.22f));
            gloveBitmapPaint.setAlpha(alpha);
            canvas.drawBitmap(bitmap, null, gloveGlowRect, gloveGlowPaint);
            canvas.drawBitmap(bitmap, null, gloveRect, gloveBitmapPaint);
        }

        private static float smoothstep(float value) {
            float t = Math.max(0f, Math.min(1f, value));
            return t * t * (3f - 2f * t);
        }

        private static float mix(float start, float end, float amount) {
            return start + (end - start) * amount;
        }

        private void drawSpinner(Canvas canvas, String frame, float x, float baseline, float rowAlpha) {
            int alpha = Math.max(0, Math.min(255, Math.round(rowAlpha * 255f)));
            renderPaint.setStyle(Paint.Style.FILL);
            renderPaint.setStrokeWidth(0f);
            renderPaint.setColor(TEXT_FILL);
            renderPaint.setAlpha(Math.round(alpha * (58f / 255f)));
            renderPaint.setShadowLayer(3f * density, 0f, 0f,
                    Color.argb(Math.round(rowAlpha * 72f), Color.red(TEXT_GLOW), Color.green(TEXT_GLOW), Color.blue(TEXT_GLOW)));
            canvas.drawText(frame, x, baseline, renderPaint);
            renderPaint.clearShadowLayer();
            renderPaint.setAlpha(alpha);
            canvas.drawText(frame, x, baseline, renderPaint);
        }

        private RowLayoutCache cacheFor(AgentActivityModel.Message message, int width, boolean newest) {
            RowLayoutCache cached = rowLayouts.get(message);
            if (cached == null || cached.newest != newest) {
                cached = new RowLayoutCache(
                        message.summary, renderPaint, width, newest, caretSlotWidthPx,
                        performanceMetrics, SystemClock.uptimeMillis());
                rowLayouts.put(message, cached);
            }
            return cached;
        }

        private StaticLayout completionLayout(CompletionMessageModel.Message message,
                int width, int availableHeight, long nowMs) {
            if (completionLayout == null || completionLayoutMessage != message
                    || completionLayoutWidth != width || completionLayoutHeight != availableHeight) {
                long startedNanos = System.nanoTime();
                completionPaint.setTextSize(baseTextSize);
                for (int attempt = 0; attempt < 6; attempt++) {
                    completionLayout = StaticLayout.Builder.obtain(
                                    message.text, 0, message.text.length(), completionPaint, width)
                            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                            .setIncludePad(false)
                            .setLineSpacing(0f, 1.18f)
                            .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                            .build();
                    if (completionLayout.getHeight() <= availableHeight || attempt == 5) break;
                    completionPaint.setTextSize(Math.max(8f * density,
                            completionPaint.getTextSize() * (float) Math.sqrt(availableHeight
                                    / (double) completionLayout.getHeight()) * 0.94f));
                }
                completionLayoutMessage = message;
                completionLayoutWidth = width;
                completionLayoutHeight = availableHeight;
                performanceMetrics.recordStaticLayout(
                        nowMs, System.nanoTime() - startedNanos);
            }
            return completionLayout;
        }

        private void drawCaption(Canvas canvas, StaticLayout layout, float rowAlpha) {
            drawCaption(canvas, layout, rowAlpha, renderPaint);
        }

        private void drawCaption(Canvas canvas, StaticLayout layout, float rowAlpha, TextPaint paint) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(TEXT_FILL);
            paint.setAlpha(Math.max(0, Math.min(255, Math.round(rowAlpha * 255f))));
            paint.clearShadowLayer();
            layout.draw(canvas);
        }

        private void drawCaret(Canvas canvas, StaticLayout layout, int caretOffset, float caretAlpha) {
            if (caretAlpha <= 0f || layout.getLineCount() == 0) return;
            int safeOffset = Math.max(0, Math.min(caretOffset, layout.getText().length()));
            int line = Math.min(layout.getLineCount() - 1, layout.getLineForOffset(safeOffset));
            float left = Math.min(layout.getWidth() - caretWidthPx,
                    Math.max(0f, layout.getPrimaryHorizontal(safeOffset)));
            float bottom = layout.getLineBottom(line) - density;
            caretRect.set(left, bottom - caretHeightPx, left + caretWidthPx, bottom);

            int alpha = Math.max(0, Math.min(255, Math.round(caretAlpha * 255f)));
            caretPaint.setAlpha(alpha);
            caretPaint.setShadowLayer(2.5f * density, 0f, 0f,
                    Color.argb(Math.round(caretAlpha * 92f), Color.red(TEXT_GLOW),
                            Color.green(TEXT_GLOW), Color.blue(TEXT_GLOW)));
            canvas.drawRoundRect(caretRect, caretHeightPx / 2f, caretHeightPx / 2f, caretPaint);
            caretPaint.clearShadowLayer();
        }

        private static StaticLayout layout(CharSequence text, TextPaint paint, int width,
                                           PerformanceMetrics performanceMetrics, long nowMs) {
            long startedNanos = System.nanoTime();
            StaticLayout result = StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(0f, 1.18f)
                    .setEllipsize(TextUtils.TruncateAt.END)
                    .setEllipsizedWidth(width)
                    .setMaxLines(2)
                    .build();
            performanceMetrics.recordStaticLayout(nowMs, System.nanoTime() - startedNanos);
            return result;
        }

        private static final class RowLayoutCache {
            final StaticLayout fullLayout;
            final boolean newest;
            private final CaretSlotSpan caretSlot;
            private String visibleText = "";
            private StaticLayout visibleLayout;

            RowLayoutCache(String fullText, TextPaint paint, int width, boolean newest,
                           int caretSlotWidthPx, PerformanceMetrics performanceMetrics, long nowMs) {
                this.newest = newest;
                caretSlot = newest ? new CaretSlotSpan(caretSlotWidthPx) : null;
                this.fullLayout = layout(decoratedText(fullText), paint, width,
                        performanceMetrics, nowMs);
            }

            StaticLayout visibleLayout(String value, TextPaint paint, int width,
                                       PerformanceMetrics performanceMetrics, long nowMs) {
                if (visibleLayout == null || !visibleText.equals(value)) {
                    visibleText = value;
                    visibleLayout = layout(decoratedText(value), paint, width,
                            performanceMetrics, nowMs);
                }
                return visibleLayout;
            }

            private CharSequence decoratedText(String value) {
                if (!newest) return value;
                SpannableString decorated = new SpannableString(value + '\uFFFC');
                decorated.setSpan(caretSlot, value.length(), decorated.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                return decorated;
            }
        }

        private static final class CaretSlotSpan extends ReplacementSpan {
            private final int widthPx;

            CaretSlotSpan(int widthPx) {
                this.widthPx = widthPx;
            }

            @Override
            public int getSize(Paint paint, CharSequence text, int start, int end,
                               Paint.FontMetricsInt fontMetrics) {
                return widthPx;
            }

            @Override
            public void draw(Canvas canvas, CharSequence text, int start, int end, float x,
                             int top, int y, int bottom, Paint paint) {
                // The slot only reserves layout space; ActivityView draws the eased caret.
            }
        }
    }
}
