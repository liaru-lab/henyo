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
import java.util.Arrays;
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
        float density = service.getResources().getDisplayMetrics().density;
        this.edgeDepthPx = Math.max(3, Math.round(48f * density));
        nextMainHeartbeatAtMs = SystemClock.uptimeMillis() + MAIN_HEARTBEAT_MS;
        mainHandler.postDelayed(mainHeartbeat, MAIN_HEARTBEAT_MS);
    }

    void setConnected(boolean value) {
        mainHandler.post(() -> setConnectedOnMain(value));
    }

    void noteControlActivity() {
        noteControlActivity("");
    }

    void noteControlActivity(String summary) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            noteControlActivityOnMain(summary);
            return;
        }
        CountDownLatch applied = new CountDownLatch(1);
        mainHandler.post(() -> {
            try {
                noteControlActivityOnMain(summary);
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

    boolean setTaskProgress(Object owner, String goal, List<String> completed, String current) {
        if (owner == null) return false;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return setTaskProgressOnMain(owner, goal, completed, current);
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        boolean[] accepted = new boolean[1];
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                accepted[0] = setTaskProgressOnMain(owner, goal, completed, current);
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
            List<TaskProgressModel.Step> steps, boolean replan) {
        if (owner == null) return TaskProgressModel.UPDATE_UNAVAILABLE;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return setTaskProgressPlanOnMain(owner, goal, steps, replan);
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        int[] result = {TaskProgressModel.UPDATE_UNAVAILABLE};
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                result[0] = setTaskProgressPlanOnMain(owner, goal, steps, replan);
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

    int showTaskCompletion(String message) {
        int validation = CompletionMessageModel.validate(message);
        if (validation != CompletionMessageModel.SHOW_ACCEPTED) return validation;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return showTaskCompletionOnMain(message);
        }
        CountDownLatch applied = new CountDownLatch(1);
        AtomicBoolean pending = new AtomicBoolean(true);
        int[] result = {CompletionMessageModel.SHOW_INVALID};
        mainHandler.post(() -> {
            try {
                if (!pending.compareAndSet(true, false)) return;
                result[0] = showTaskCompletionOnMain(message);
            } finally {
                applied.countDown();
            }
        });
        try {
            if (!applied.await(1_500L, TimeUnit.MILLISECONDS)) {
                pending.compareAndSet(true, false);
                return CompletionMessageModel.SHOW_INVALID;
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            return CompletionMessageModel.SHOW_INVALID;
        }
        return result[0];
    }

    void showObservation(String logicalId) {
        postVisual(() -> visualModel.beginScan(logicalId, SystemClock.uptimeMillis()));
    }

    void setTargetWindow(int windowId, int displayId, Rect bounds, Region actionableRegion,
                         int displayWidth, int displayHeight, float density) {
        Rect boundsCopy = bounds == null ? new Rect() : new Rect(bounds);
        Region regionCopy = actionableRegion == null ? new Region() : new Region(actionableRegion);
        mainHandler.post(() -> {
            if (destroyed) return;
            boolean displayChanged = targetVisualState.displayId != displayId;
            targetVisualState.set(windowId, displayId, boundsCopy, regionCopy,
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
            if (destroyed || !connected) return;
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
        if (!connected) {
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
        completionModel.clear();
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

    private int showTaskCompletionOnMain(String message) {
        if (destroyed || !connected) return CompletionMessageModel.SHOW_INVALID;
        if (!progressModel.isEmpty()) return CompletionMessageModel.SHOW_PROGRESS_ACTIVE;
        int result = completionModel.show(message, SystemClock.uptimeMillis());
        if (result != CompletionMessageModel.SHOW_ACCEPTED) return result;
        activityModel.clear();
        ensureActivityView();
        if (activityView != null) activityView.noteCompletionChanged();
        return result;
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
        if (view != null || windowManager == null || captureSuppressionDepth > 0) return;
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
        final Region actionableRegion = new Region();
        final Path outline = new Path();
        final Path actionableClip = new Path();
        int windowId = -1;
        int displayId = Display.DEFAULT_DISPLAY;
        int displayWidth;
        int displayHeight;
        float density = 1f;
        long generation;
        boolean valid;

        void set(int newWindowId, int newDisplayId, Rect newBounds, Region newRegion,
                 int newDisplayWidth, int newDisplayHeight, float newDensity) {
            if (valid && windowId == newWindowId && displayId == newDisplayId
                    && bounds.equals(newBounds) && actionableRegion.equals(newRegion)
                    && displayWidth == newDisplayWidth && displayHeight == newDisplayHeight
                    && Float.compare(density, Math.max(0.5f, newDensity)) == 0) return;
            windowId = newWindowId;
            displayId = newDisplayId;
            bounds.set(newBounds);
            actionableRegion.set(newRegion);
            displayWidth = newDisplayWidth;
            displayHeight = newDisplayHeight;
            density = Math.max(0.5f, newDensity);
            valid = windowId >= 0 && !bounds.isEmpty() && !actionableRegion.isEmpty();
            outline.reset();
            actionableClip.reset();
            if (valid) {
                float radius = Math.min(14f * density,
                        Math.min(bounds.width(), bounds.height()) * 0.08f);
                outline.addRoundRect(new RectF(bounds), radius, radius, Path.Direction.CW);
                actionableRegion.getBoundaryPath(actionableClip);
            }
            generation++;
        }

        void clear() {
            valid = false;
            windowId = -1;
            bounds.setEmpty();
            actionableRegion.setEmpty();
            outline.reset();
            actionableClip.reset();
            generation++;
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
        private static final int TEXT_FILL = Color.rgb(244, 251, 255);
        private static final int TEXT_GLOW = Color.rgb(0, 241, 239);
        private static final int BACKGROUND_NAVY = Color.rgb(0, 17, 40);
        private static final int BACKGROUND_NAVY_LIFTED = Color.rgb(0, 43, 72);
        private static final long STACK_TRANSITION_MS = 240L;
        private static final long CAPTION_FRAME_MS = 32L;
        private static final float COMPLETION_BACKDROP_TOP_FRACTION = 0.42f;

        private final AgentActivityModel model;
        private final TaskProgressModel progressModel;
        private final CompletionMessageModel completionModel;
        private final AgentVisualModel visualModel;
        private final VisualCoordinates visualCoordinates;
        private final int backdropHeightPx;
        private final Runnable emptyCallback;
        private final PerformanceMetrics performanceMetrics;
        private final TextPaint renderPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint activityShadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint activityGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint caretPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scanCorePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint gloveBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint gloveGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint targetPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint targetOutlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint targetGlowBitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final TextPaint progressPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final Paint progressIconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path activityGridPath = new Path();
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
        private final float[] visibleTargetY = new float[AgentActivityModel.MAX_MESSAGES];
        private final float density;
        private final float spinnerIndentPx;
        private final float caretWidthPx;
        private final float caretHeightPx;
        private final int caretSlotWidthPx;
        private int cachedTextWidth = -1;
        private Shader activityShadeShader;
        private Shader progressActivityShadeShader;
        private Shader activityGridShader;
        private Shader completionActivityShadeShader;
        private int progressShadeWidth = -1;
        private int progressShadeHeight = -1;
        private int progressShadeRowCount = -1;
        private int completionShadeWidth = -1;
        private int completionShadeHeight = -1;
        private float completionShadeTop = -1f;
        private CompletionMessageModel.Message completionLayoutMessage;
        private int completionLayoutWidth = -1;
        private StaticLayout completionLayout;
        private long stackTransitionStartedAtMs;
        private long targetShaderGeneration = -1L;
        private long targetGlowBitmapGeneration = -1L;
        private Bitmap targetGlowBitmap;
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
            this.backdropHeightPx = Math.max(1, Math.round(400f * this.density));
            this.emptyCallback = emptyCallback;
            this.performanceMetrics = performanceMetrics;
            float scaledDensity = context.getResources().getDisplayMetrics().scaledDensity;
            float textSize = 20f * scaledDensity;
            renderPaint.setTextSize(textSize);
            renderPaint.setStrokeJoin(Paint.Join.ROUND);
            renderPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            progressPaint.setTextSize(16f * scaledDensity);
            progressPaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            progressPaint.setColor(TEXT_FILL);
            progressIconPaint.setColor(TEXT_GLOW);
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
            activityGridPaint.setColor(BACKGROUND_NAVY_LIFTED);
            activityGridPaint.setStyle(Paint.Style.STROKE);
            activityGridPaint.setStrokeWidth(Math.max(1f, 0.7f * density));
            scanPaint.setColor(TEXT_GLOW);
            scanPaint.setStrokeWidth(8f * density);
            scanPaint.setStrokeCap(Paint.Cap.ROUND);
            scanPaint.setShadowLayer(10f * density, 0f, 0f, TEXT_GLOW);
            scanCorePaint.setColor(Color.rgb(174, 255, 255));
            scanCorePaint.setStrokeWidth(Math.max(1f, 1.2f * density));
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
            setWillNotDraw(false);
        }

        void noteNewMessage() {
            stackTransitionStartedAtMs = SystemClock.uptimeMillis();
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
            recycleTargetGlowBitmap();
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
            int textWidth = Math.max(1, Math.min(width - left * 2, Math.round(width * 0.88f)));
            StaticLayout fullCompletionLayout = completion == null ? null
                    : completionLayout(completion, textWidth, now);
            float completionY = fullCompletionLayout == null ? height
                    : height - 56f * density - fullCompletionLayout.getHeight();
            float dynamicShadeTop = fullCompletionLayout == null ? Float.NaN
                    : Math.max(height * COMPLETION_BACKDROP_TOP_FRACTION,
                    Math.min(height - backdropHeightPx, completionY - 32f * density));
            boolean hasPresentation = !messages.isEmpty() || !progressRows.isEmpty()
                    || completion != null;
            drawObservationScan(canvas, width, height, now, hasPresentation);
            AgentVisualModel.CursorFrame cursorFrame = visualModel.cursorFrame(now);
            drawGlove(canvas, now, cursorFrame);
            float backgroundAlpha = visualModel.backdropEnvelope(now);
            if (!progressRows.isEmpty()) backgroundAlpha = Math.max(backgroundAlpha, 0.82f);
            if (completion != null) {
                backgroundAlpha = Math.max(backgroundAlpha,
                        0.92f * CompletionMessageModel.alpha(completion, now));
            }
            if (backgroundAlpha > 0f) {
                drawActivityBackground(canvas, backgroundAlpha, progressRows.size(), dynamicShadeTop);
            }
            float planTop = height - backdropHeightPx + 32f * density;

            if (!hasPresentation) {
                if (!visualModel.isActivityVisible(now) && visualModel.scanPosition(now) < 0f
                        && cursorFrame == null && !emptyCallbackPosted) {
                    emptyCallbackPosted = true;
                    post(emptyCallback);
                }
                scheduleNextFrame(now, width, height, cursorFrame, false,
                        height - backdropHeightPx);
                performanceMetrics.recordActivityDraw(now, System.nanoTime() - drawStartedNanos);
                return;
            }
            emptyCallbackPosted = false;

            int bodyWidth = Math.max(1, textWidth - Math.round(spinnerIndentPx));
            if (cachedTextWidth != bodyWidth) {
                cachedTextWidth = bodyWidth;
                rowLayouts.clear();
            }
            rowLayouts.keySet().retainAll(messages);
            float gap = 12f * density;
            float cursor = height - 56f * density;
            float transition = Math.min(1f,
                    Math.max(0f, (now - stackTransitionStartedAtMs) / (float) STACK_TRANSITION_MS));

            if (fullCompletionLayout != null) {
                int saved = canvas.save();
                canvas.translate(left, completionY);
                drawCaption(canvas, fullCompletionLayout,
                        CompletionMessageModel.alpha(completion, now));
                canvas.restoreToCount(saved);
            }

            if (!messages.isEmpty()) {
                for (int i = messages.size() - 1; i >= 0; i--) {
                    boolean newest = i == messages.size() - 1;
                    visibleFullLayouts[i] = cacheFor(messages.get(i), bodyWidth, newest).fullLayout;
                    cursor -= visibleFullLayouts[i].getHeight();
                    visibleTargetY[i] = cursor;
                    cursor -= gap;
                }
            }
            float newRowShift = messages.isEmpty() ? 0f
                    : visibleFullLayouts[messages.size() - 1].getHeight() + gap;

            for (int i = 0; i < messages.size(); i++) {
                AgentActivityModel.Message message = messages.get(i);
                int ageRank = messages.size() - 1 - i;
                boolean newest = ageRank == 0;
                float alpha = AgentActivityModel.alpha(message, ageRank, now);
                if (alpha <= 0f) continue;
                String visible = AgentActivityModel.displayBodyText(message, now, newest);
                float y = visibleTargetY[i];
                if (ageRank > 0 && transition < 1f) y += newRowShift * (1f - transition);

                RowLayoutCache cache = cacheFor(message, bodyWidth, newest);
                StaticLayout visibleLayout = cache.visibleLayout(
                        visible, renderPaint, bodyWidth, performanceMetrics, now);
                int saved = canvas.save();
                canvas.translate(left + spinnerIndentPx, y);
                drawCaption(canvas, visibleLayout, alpha);
                if (newest) {
                    drawCaret(canvas, visibleLayout, visible.length(),
                            alpha * AgentActivityModel.caretAlpha(message, now));
                }
                canvas.restoreToCount(saved);
                if (newest) {
                    drawSpinner(canvas, AgentActivityModel.spinnerFrame(now), left,
                            y + visibleLayout.getLineBaseline(0), alpha);
                }
            }

            drawTaskProgress(canvas, progressRows, left, textWidth, planTop, now);

            int presentationTop = Float.isNaN(dynamicShadeTop)
                    ? height - backdropHeightPx : Math.round(dynamicShadeTop);
            scheduleNextFrame(now, width, height, cursorFrame, hasPresentation, presentationTop);
            performanceMetrics.recordActivityDraw(now, System.nanoTime() - drawStartedNanos);
        }

        private void drawTargetWindowGlow(Canvas canvas, long now) {
            if (!targetVisualState.valid || !visualModel.isActivityVisible(now)) return;
            Rect bounds = targetVisualState.bounds;
            if (bounds.isEmpty()) return;
            float depth = TargetGlowModel.depthPx(bounds.width(), bounds.height(),
                    targetVisualState.density);
            float breath = TargetGlowModel.breath(now);
            depth *= 0.96f + 0.08f * breath;
            float intensity = TargetGlowModel.intensity(
                    visualModel.activityEnvelope(now), breath);

            if (targetShaderGeneration != targetVisualState.generation) {
                targetShaderGeneration = targetVisualState.generation;
                Shader targetShader = new LinearGradient(
                        bounds.left, bounds.top, bounds.right, bounds.bottom,
                        new int[]{
                                Color.rgb(67, 210, 196),
                                Color.rgb(92, 154, 222),
                                Color.rgb(157, 126, 204),
                                Color.rgb(67, 210, 196)
                        },
                        new float[]{0f, 0.38f, 0.72f, 1f}, Shader.TileMode.CLAMP);
                targetOutlinePaint.setShader(targetShader);
            }

            ensureTargetGlowBitmap(depth);
            int saved = canvas.save();
            canvas.clipPath(targetVisualState.actionableClip);
            if (targetGlowBitmap != null) {
                targetGlowBitmapPaint.setAlpha(Math.round(245f * intensity));
                canvas.drawBitmap(targetGlowBitmap, bounds.left, bounds.top, targetGlowBitmapPaint);
            }
            targetOutlinePaint.setStrokeWidth(8f * targetVisualState.density);
            targetOutlinePaint.setAlpha(245);
            canvas.drawPath(targetVisualState.outline, targetOutlinePaint);
            canvas.restoreToCount(saved);
        }

        private void ensureTargetGlowBitmap(float depth) {
            Rect bounds = targetVisualState.bounds;
            if (targetGlowBitmapGeneration == targetVisualState.generation
                    && targetGlowBitmap != null
                    && targetGlowBitmap.getWidth() == bounds.width()
                    && targetGlowBitmap.getHeight() == bounds.height()) return;
            recycleTargetGlowBitmap();
            if (bounds.isEmpty()) return;
            int width = bounds.width();
            int height = bounds.height();
            int[] pixels;
            try {
                targetGlowBitmap = Bitmap.createBitmap(
                        width, height, Bitmap.Config.ARGB_8888);
                pixels = new int[Math.multiplyExact(width, height)];
            } catch (RuntimeException | OutOfMemoryError ignored) {
                recycleTargetGlowBitmap();
                targetGlowBitmap = null;
                return;
            }
            float halfWidth = width * 0.5f;
            float halfHeight = height * 0.5f;
            float radius = Math.min(14f * targetVisualState.density,
                    Math.min(width, height) * 0.08f);
            float flatHalfWidth = Math.max(0f, halfWidth - radius);
            float flatHalfHeight = Math.max(0f, halfHeight - radius);
            float glowDepth = Math.max(12f * targetVisualState.density, depth * 3.2f);
            float projectionDenominator = Math.max(1f, width * (float) width + height * (float) height);
            for (int y = 0; y < height; y++) {
                float localY = y + 0.5f;
                float qy = Math.abs(localY - halfHeight) - flatHalfHeight;
                for (int x = 0; x < width; x++) {
                    float localX = x + 0.5f;
                    float qx = Math.abs(localX - halfWidth) - flatHalfWidth;
                    float outside = (float) Math.hypot(Math.max(qx, 0f), Math.max(qy, 0f));
                    float signedDistance = outside + Math.min(Math.max(qx, qy), 0f) - radius;
                    if (signedDistance > 0f) continue;
                    float glow = TargetGlowModel.innerGlowAlpha(-signedDistance, glowDepth);
                    if (glow <= 0f) continue;
                    float colorPosition = (localX * width + localY * height) / projectionDenominator;
                    int color = TargetGlowModel.gradientColor(colorPosition);
                    int alpha = Math.round(205f * glow);
                    pixels[y * width + x] = Color.argb(alpha,
                            Color.red(color), Color.green(color), Color.blue(color));
                }
            }
            targetGlowBitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            targetGlowBitmapGeneration = targetVisualState.generation;
        }

        private void recycleTargetGlowBitmap() {
            targetGlowBitmapGeneration = -1L;
            if (targetGlowBitmap == null) return;
            targetGlowBitmap.recycle();
            targetGlowBitmap = null;
        }

        private void drawTaskProgress(Canvas canvas, List<TaskProgressModel.Row> rows,
                float left, int availableWidth, float top, long now) {
            if (rows.isEmpty()) return;
            float rowHeight = 28f * density;
            float iconCenterX = left + 9f * density;
            float textLeft = left + 29f * density;
            float textWidth = Math.max(1f, availableWidth - (textLeft - left));
            progressIconPaint.setAlpha(214);
            if (rows.size() > 1) {
                float firstY = top + rowHeight * 0.5f;
                float lastY = top + (rows.size() - 0.5f) * rowHeight;
                progressIconPaint.setAlpha(68);
                canvas.drawLine(iconCenterX, firstY, iconCenterX, lastY, progressIconPaint);
            }
            for (int i = 0; i < rows.size(); i++) {
                TaskProgressModel.Row row = rows.get(i);
                float centerY = top + (i + 0.5f) * rowHeight;
                drawProgressIcon(canvas, row.kind, iconCenterX, centerY, now);
                String visible = TaskProgressModel.visibleText(row, now);
                CharSequence fitted = TextUtils.ellipsize(
                        visible, progressPaint, textWidth, TextUtils.TruncateAt.END);
                progressPaint.setAlpha(row.kind == TaskProgressModel.KIND_COMPLETED ? 184 : 255);
                float baseline = centerY - (progressPaint.ascent() + progressPaint.descent()) * 0.5f;
                canvas.drawText(fitted, 0, fitted.length(), textLeft, baseline, progressPaint);
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
            boolean fullScreenMotion = visualModel.scanPosition(now) >= 0f
                    || visualCoordinates.isAnimating(now)
                    || cursorFrame != null && (cursorFrame.phase != AgentVisualModel.CURSOR_PHASE_IDLE
                    || cursorFrame.phaseProgress < 1f);
            if (fullScreenMotion) {
                postInvalidateOnAnimation();
                return;
            }
            if (hasMessages || visualModel.isBackdropAnimating(now)) {
                int dirtyTop = Math.max(0, Math.min(height - backdropHeightPx, presentationTop));
                postInvalidateDelayed(CAPTION_FRAME_MS, 0, dirtyTop, width, height);
            }
        }

        @Override
        protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            rebuildActivityBackground(width, height);
        }

        private void rebuildActivityBackground(int width, int height) {
            if (width <= 0 || height <= 0) return;
            float shadeTop = Math.max(0f, height - backdropHeightPx);
            activityShadeShader = new LinearGradient(0f, shadeTop, 0f, height,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb(22, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY)),
                            Color.argb(154, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY)),
                            Color.argb(232, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY))
                    }, new float[]{0f, 0.24f, 0.64f, 1f}, Shader.TileMode.CLAMP);
            progressActivityShadeShader = null;
            progressShadeWidth = -1;
            progressShadeHeight = -1;
            progressShadeRowCount = -1;
            completionActivityShadeShader = null;
            completionShadeWidth = -1;
            completionShadeHeight = -1;
            completionShadeTop = -1f;
            activityGridShader = new LinearGradient(0f, shadeTop, 0f, height,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.TRANSPARENT,
                            Color.argb(12, Color.red(BACKGROUND_NAVY_LIFTED), Color.green(BACKGROUND_NAVY_LIFTED), Color.blue(BACKGROUND_NAVY_LIFTED)),
                            Color.argb(28, Color.red(BACKGROUND_NAVY_LIFTED), Color.green(BACKGROUND_NAVY_LIFTED), Color.blue(BACKGROUND_NAVY_LIFTED))
                    }, new float[]{0f, 0.32f, 0.68f, 1f}, Shader.TileMode.CLAMP);
            activityGridPaint.setShader(activityGridShader);
            activityGridPath.reset();
            float spacing = Math.max(16f, 26f * density);
            for (float x = 0f; x <= width; x += spacing) {
                activityGridPath.moveTo(x, shadeTop);
                activityGridPath.lineTo(x, height);
            }
            for (float y = shadeTop; y <= height; y += spacing) {
                activityGridPath.moveTo(0f, y);
                activityGridPath.lineTo(width, y);
            }
        }

        private void drawActivityBackground(Canvas canvas, float rowAlpha, int progressRowCount,
                float requestedCompletionShadeTop) {
            if (activityShadeShader == null) rebuildActivityBackground(getWidth(), getHeight());
            if (progressRowCount > 0) {
                ensureProgressActivityShade(getWidth(), getHeight(), progressRowCount);
            }
            if (!Float.isNaN(requestedCompletionShadeTop)) {
                ensureCompletionActivityShade(getWidth(), getHeight(), requestedCompletionShadeTop);
            }
            int alpha = Math.max(0, Math.min(255, Math.round(rowAlpha * 255f)));
            activityShadePaint.setShader(!Float.isNaN(requestedCompletionShadeTop)
                    ? completionActivityShadeShader
                    : progressRowCount > 0 ? progressActivityShadeShader : activityShadeShader);
            activityShadePaint.setAlpha(alpha);
            canvas.drawRect(0f, 0f, getWidth(), getHeight(), activityShadePaint);
            activityGridPaint.setAlpha(alpha);
            canvas.drawPath(activityGridPath, activityGridPaint);
        }

        private void ensureCompletionActivityShade(int width, int height, float shadeTop) {
            if (completionActivityShadeShader != null && completionShadeWidth == width
                    && completionShadeHeight == height
                    && Math.abs(completionShadeTop - shadeTop) < 0.5f) return;
            completionActivityShadeShader = new LinearGradient(0f, shadeTop, 0f, height,
                    new int[]{
                            Color.TRANSPARENT,
                            Color.argb(22, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY)),
                            Color.argb(154, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY)),
                            Color.argb(232, Color.red(BACKGROUND_NAVY), Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY))
                    }, new float[]{0f, 0.24f, 0.64f, 1f}, Shader.TileMode.CLAMP);
            completionShadeWidth = width;
            completionShadeHeight = height;
            completionShadeTop = shadeTop;
        }

        private void ensureProgressActivityShade(int width, int height, int rowCount) {
            if (progressActivityShadeShader != null && progressShadeWidth == width
                    && progressShadeHeight == height && progressShadeRowCount == rowCount) return;
            float shadeTop = Math.max(0f, height - backdropHeightPx);
            float planTop = shadeTop + 32f * density;
            float planBottom = Math.min(height, planTop + rowCount * 28f * density);
            float lobeTop = Math.max(0f, planTop - 140f * density);
            float lobeBottom = Math.min(height, planBottom + 48f * density);
            float baseSpan = Math.max(1f, height - shadeTop);
            float[] breakpoints = {
                    lobeTop, shadeTop, planTop, shadeTop + 0.24f * baseSpan,
                    planBottom, lobeBottom, shadeTop + 0.64f * baseSpan, height
            };
            Arrays.sort(breakpoints);
            float[] points = new float[breakpoints.length * 2];
            int count = 0;
            for (float point : breakpoints) {
                if (count == 0 || point - points[count - 1] > 0.5f) points[count++] = point;
            }
            int breakpointCount = count;
            for (int i = 0; i + 1 < breakpointCount; i++) {
                float firstY = points[i];
                float secondY = points[i + 1];
                float firstDifference = baseShadeAlpha(firstY, shadeTop, height)
                        - progressLobeAlpha(firstY, lobeTop, planTop, planBottom, lobeBottom);
                float secondDifference = baseShadeAlpha(secondY, shadeTop, height)
                        - progressLobeAlpha(secondY, lobeTop, planTop, planBottom, lobeBottom);
                if (firstDifference * secondDifference < 0f) {
                    points[count++] = firstY + (secondY - firstY)
                            * (-firstDifference) / (secondDifference - firstDifference);
                }
            }
            Arrays.sort(points, 0, count);
            int uniqueCount = 0;
            for (int i = 0; i < count; i++) {
                if (uniqueCount == 0 || points[i] - points[uniqueCount - 1] > 0.5f) {
                    points[uniqueCount++] = points[i];
                }
            }
            count = uniqueCount;
            int[] colors = new int[count];
            float[] stops = new float[count];
            float shaderSpan = Math.max(1f, height - lobeTop);
            for (int i = 0; i < count; i++) {
                float y = points[i];
                int alpha = Math.round(Math.max(baseShadeAlpha(y, shadeTop, height),
                        progressLobeAlpha(y, lobeTop, planTop, planBottom, lobeBottom)));
                colors[i] = Color.argb(alpha, Color.red(BACKGROUND_NAVY),
                        Color.green(BACKGROUND_NAVY), Color.blue(BACKGROUND_NAVY));
                stops[i] = Math.max(0f, Math.min(1f, (y - lobeTop) / shaderSpan));
            }
            progressActivityShadeShader = new LinearGradient(0f, lobeTop, 0f, height,
                    colors, stops, Shader.TileMode.CLAMP);
            progressShadeWidth = width;
            progressShadeHeight = height;
            progressShadeRowCount = rowCount;
        }

        private static float baseShadeAlpha(float y, float shadeTop, float height) {
            float t = Math.max(0f, Math.min(1f,
                    (y - shadeTop) / Math.max(1f, height - shadeTop)));
            if (t <= 0.24f) return mix(0f, 22f, t / 0.24f);
            if (t <= 0.64f) return mix(22f, 154f, (t - 0.24f) / 0.40f);
            return mix(154f, 232f, (t - 0.64f) / 0.36f);
        }

        private static float progressLobeAlpha(float y, float lobeTop, float planTop,
                float planBottom, float lobeBottom) {
            if (y <= lobeTop || y >= lobeBottom) return 0f;
            if (y < planTop) return 94f * (y - lobeTop) / Math.max(1f, planTop - lobeTop);
            if (y <= planBottom) return 94f;
            return 94f * (lobeBottom - y) / Math.max(1f, lobeBottom - planBottom);
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
            scanPaint.setAlpha(Math.round(alpha * 0.20f));
            scanCorePaint.setAlpha(Math.round(alpha * 0.72f));
            float inset = 18f * density;
            canvas.drawLine(inset, y, width - inset, y, scanPaint);
            canvas.drawLine(inset, y, width - inset, y, scanCorePaint);
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
                int width, long nowMs) {
            if (completionLayout == null || completionLayoutMessage != message
                    || completionLayoutWidth != width) {
                long startedNanos = System.nanoTime();
                completionLayout = StaticLayout.Builder.obtain(
                                message.text, 0, message.text.length(), renderPaint, width)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.18f)
                        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                        .build();
                completionLayoutMessage = message;
                completionLayoutWidth = width;
                performanceMetrics.recordStaticLayout(
                        nowMs, System.nanoTime() - startedNanos);
            }
            return completionLayout;
        }

        private void drawCaption(Canvas canvas, StaticLayout layout, float rowAlpha) {
            int alpha = Math.max(0, Math.min(255, Math.round(rowAlpha * 255f)));

            renderPaint.setStyle(Paint.Style.FILL);
            renderPaint.setStrokeWidth(0f);
            renderPaint.setColor(TEXT_FILL);
            renderPaint.setAlpha(Math.round(alpha * (58f / 255f)));
            renderPaint.setShadowLayer(3f * density, 0f, 0f,
                    Color.argb(Math.round(rowAlpha * 72f), Color.red(TEXT_GLOW), Color.green(TEXT_GLOW), Color.blue(TEXT_GLOW)));
            layout.draw(canvas);
            renderPaint.clearShadowLayer();

            renderPaint.setStyle(Paint.Style.FILL);
            renderPaint.setStrokeWidth(0f);
            renderPaint.setColor(TEXT_FILL);
            renderPaint.setAlpha(alpha);
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
