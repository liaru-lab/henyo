package link.liaru.henyo;

/**
 * Android-free timing model for Henyo's agent-control visuals.
 *
 * <p>All clocks are supplied by the caller. This keeps rendering deterministic,
 * makes screenshot/device tests repeatable, and prevents a new control event from
 * resetting the glow pulse phase.</p>
 */
final class AgentVisualModel {
    static final long ACTIVE_LEASE_MS = 20_000L;
    static final long ACTIVATION_MS = 800L;
    static final long BACKDROP_ACTIVATION_MS = 120L;
    static final long FADE_OUT_MS = 1_800L;
    static final long PULSE_CYCLE_MS = 5_000L;
    static final float PULSE_MIN = 0.72f;
    static final float PULSE_MAX = 1f;

    static final long SCAN_DURATION_MS = 1_400L;

    static final long CURSOR_MOVE_MIN_MS = 180L;
    static final long CURSOR_MOVE_MAX_MS = 450L;
    static final long CURSOR_ACTION_MS = 120L;
    static final long CURSOR_AFTERGLOW_MS = 240L;
    static final long CURSOR_POINTER_HOLD_MS = 180L;
    static final long CURSOR_IDLE_DIM_MS = 420L;
    static final long CURSOR_RESTORE_MS = 120L;
    static final float CURSOR_IDLE_ALPHA = 0.18f;

    // Int constants avoid an enum-desugaring bug in the pinned Android D8 tool.
    static final int GLOVE_POSE_POINT = 1;
    static final int GLOVE_POSE_OPEN_PALM = 2;
    static final int GLOVE_POSE_BACK_LEFT = 3;
    static final int CURSOR_PHASE_TRAVELING = 1;
    static final int CURSOR_PHASE_ACTING = 2;
    static final int CURSOR_PHASE_AFTERGLOW = 3;
    static final int CURSOR_PHASE_POINTER_HOLD = 4;
    static final int CURSOR_PHASE_IDLE = 5;

    /** Persistent single-sprite cursor state. Position interpolation remains controller-owned. */
    static final class CursorFrame {
        final int pose;
        final int phase;
        /** Eased travel, action, or idle-dim progress for the current phase. */
        final float phaseProgress;
        /** Includes idle dimming and the shared activity activation/fade envelope. */
        final float alpha;
        /** 0..1 press/swipe beat during the acting phase. */
        final float actionAmount;
        final long travelDurationMs;

        CursorFrame(int pose, int phase, float phaseProgress, float alpha,
                float actionAmount, long travelDurationMs) {
            this.pose = pose;
            this.phase = phase;
            this.phaseProgress = phaseProgress;
            this.alpha = alpha;
            this.actionAmount = actionAmount;
            this.travelDurationMs = travelDurationMs;
        }
    }

    private boolean hasActivity;
    private long activationStartedAtMs;
    private float activationFrom;
    private float backdropActivationFrom;
    private long leaseUntilMs;

    private String lastScanId;
    private long scanStartedAtMs = Long.MIN_VALUE;

    private int cursorPose;
    private int cursorPhase;
    private long cursorPhaseStartedAtMs = Long.MIN_VALUE;
    private long cursorTravelDurationMs;
    private long cursorActionDurationMs = CURSOR_ACTION_MS;
    private float cursorTravelFromAlpha = 1f;
    private boolean cursorNeedsCenterPlacement;

    /**
     * Extends the activity lease. Activity already in its lease keeps its current
     * activation curve; reactivation during fade starts at the exact current envelope.
     */
    void noteActivity(long nowMs) {
        if (hasActivity && nowMs < leaseUntilMs) {
            leaseUntilMs = saturatingAdd(nowMs, ACTIVE_LEASE_MS);
            return;
        }
        float current = activityEnvelope(nowMs);
        float currentBackdrop = backdropEnvelope(nowMs);
        if (hasActivity && current <= 0f) clearCursor();
        hasActivity = true;
        activationStartedAtMs = nowMs;
        activationFrom = current;
        backdropActivationFrom = currentBackdrop;
        leaseUntilMs = saturatingAdd(nowMs, ACTIVE_LEASE_MS);
    }

    /** 0..1 activation/lease/fade envelope, without the global pulse. */
    float activityEnvelope(long nowMs) {
        if (!hasActivity || nowMs < activationStartedAtMs) return 0f;
        long activationEndMs = saturatingAdd(activationStartedAtMs, ACTIVATION_MS);
        if (nowMs < activationEndMs) {
            float progress = (nowMs - activationStartedAtMs) / (float) ACTIVATION_MS;
            return mix(activationFrom, 1f, smoothstep(progress));
        }
        if (nowMs <= leaseUntilMs) return 1f;
        long fadeEndMs = saturatingAdd(leaseUntilMs, FADE_OUT_MS);
        if (nowMs >= fadeEndMs) return 0f;
        float progress = (nowMs - leaseUntilMs) / (float) FADE_OUT_MS;
        return mix(1f, 0f, smoothstep(progress));
    }

    /** Faster entrance for the navy contrast field; lease and fade stay synchronized. */
    float backdropEnvelope(long nowMs) {
        if (!hasActivity || nowMs < activationStartedAtMs) return 0f;
        long activationEndMs = saturatingAdd(activationStartedAtMs, BACKDROP_ACTIVATION_MS);
        if (nowMs < activationEndMs) {
            float progress = (nowMs - activationStartedAtMs) / (float) BACKDROP_ACTIVATION_MS;
            return mix(backdropActivationFrom, 1f, smoothstep(progress));
        }
        if (nowMs <= leaseUntilMs) return 1f;
        long fadeEndMs = saturatingAdd(leaseUntilMs, FADE_OUT_MS);
        if (nowMs >= fadeEndMs) return 0f;
        float progress = (nowMs - leaseUntilMs) / (float) FADE_OUT_MS;
        return mix(1f, 0f, smoothstep(progress));
    }

    /**
     * Deterministic pulse tied only to the supplied monotonic clock. Control events
     * never alter its phase.
     */
    static float pulse(long nowMs) {
        long phaseMs = Math.floorMod(nowMs, PULSE_CYCLE_MS);
        float phase = phaseMs / (float) PULSE_CYCLE_MS;
        float triangle = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;
        return mix(PULSE_MIN, PULSE_MAX, smoothstep(triangle));
    }

    /** Final active glow intensity. */
    float activityIntensity(long nowMs) {
        return activityEnvelope(nowMs) * pulse(nowMs);
    }

    boolean isActivityVisible(long nowMs) {
        return activityEnvelope(nowMs) > 0f;
    }

    boolean isBackdropAnimating(long nowMs) {
        if (!hasActivity || nowMs < activationStartedAtMs) return false;
        if (nowMs < saturatingAdd(activationStartedAtMs, BACKDROP_ACTIVATION_MS)) return true;
        return nowMs > leaseUntilMs && nowMs < saturatingAdd(leaseUntilMs, FADE_OUT_MS);
    }

    void clearActivity() {
        hasActivity = false;
        activationFrom = 0f;
        backdropActivationFrom = 0f;
        clearCursor();
    }

    /**
     * Starts one visual scan for a logical observation. Repeated requests with the
     * same id, or any request while a scan is moving, do not restart it.
     */
    boolean beginScan(String observationId, long nowMs) {
        String id = observationId == null ? "" : observationId;
        if (scanActive(nowMs) || id.equals(lastScanId)) return false;
        lastScanId = id;
        scanStartedAtMs = nowMs;
        return true;
    }

    /** Convenience overload when the caller has already coalesced logical observations. */
    boolean beginScan(long nowMs) {
        if (scanActive(nowMs)) return false;
        lastScanId = null;
        scanStartedAtMs = nowMs;
        return true;
    }

    boolean scanActive(long nowMs) {
        if (scanStartedAtMs == Long.MIN_VALUE || nowMs < scanStartedAtMs) return false;
        return nowMs - scanStartedAtMs < SCAN_DURATION_MS;
    }

    /** Returns eased top-to-bottom position in [0,1], or -1 while no scan is visible. */
    float scanPosition(long nowMs) {
        if (!scanActive(nowMs)) return -1f;
        return smoothstep((nowMs - scanStartedAtMs) / (float) SCAN_DURATION_MS);
    }

    void clearScan() {
        scanStartedAtMs = Long.MIN_VALUE;
    }

    /**
     * Begins a persistent cursor session. The controller should place its retained
     * coordinate at the display center when {@link #cursorNeedsCenterPlacement()}
     * returns true.
     */
    void beginCursorSession(long nowMs) {
        beginCursorSession(GLOVE_POSE_POINT, nowMs);
    }

    void beginCursorSession(int pose, long nowMs) {
        validatePose(pose);
        cursorPose = pose;
        cursorPhase = CURSOR_PHASE_IDLE;
        cursorPhaseStartedAtMs = nowMs;
        cursorTravelDurationMs = 0L;
        cursorActionDurationMs = CURSOR_ACTION_MS;
        cursorTravelFromAlpha = 1f;
        cursorNeedsCenterPlacement = true;
    }

    /** True once per new cursor session, until the first target movement begins. */
    boolean cursorNeedsCenterPlacement() {
        return cursorPose != 0 && cursorNeedsCenterPlacement;
    }

    /**
     * Starts travel from the retained coordinate. Distance is normalized to the
     * display diagonal: zero still gets a perceptible 180ms move and one diagonal
     * (or farther) is capped at 450ms.
     */
    long moveCursor(int pose, float distanceFraction, long nowMs) {
        validatePose(pose);
        if (cursorPose == 0) beginCursorSession(pose, nowMs);
        cursorTravelFromAlpha = cursorLocalAlpha(nowMs);
        cursorPose = GLOVE_POSE_POINT;
        cursorPhase = CURSOR_PHASE_TRAVELING;
        cursorPhaseStartedAtMs = nowMs;
        cursorTravelDurationMs = cursorMoveDuration(distanceFraction);
        cursorNeedsCenterPlacement = false;
        clearScan();
        return cursorTravelDurationMs;
    }

    /** Convenience for controllers that retain pixel coordinates. */
    long moveCursorPixels(int pose, float distancePx, float displayDiagonalPx, long nowMs) {
        float fraction = displayDiagonalPx > 0f ? distancePx / displayDiagonalPx : 0f;
        return moveCursor(pose, fraction, nowMs);
    }

    /** Starts the visible action only after the controller has reached its target. */
    void commitCursorAction(int pose, long actionDurationMs, long nowMs) {
        validatePose(pose);
        if (cursorPose == 0) throw new IllegalStateException("cursor session has not begun");
        cursorPose = pose;
        cursorPhase = CURSOR_PHASE_ACTING;
        cursorPhaseStartedAtMs = nowMs;
        cursorActionDurationMs = Math.max(1L, actionDurationMs);
    }

    static long cursorMoveDuration(float distanceFraction) {
        float distance = clamp01(distanceFraction);
        return Math.round(CURSOR_MOVE_MIN_MS
                + (CURSOR_MOVE_MAX_MS - CURSOR_MOVE_MIN_MS) * smoothstep(distance));
    }

    /**
     * Returns pointer travel, action pose, pointer return, then persistent dim-idle
     * state. Exactly one pose is exposed per frame. It returns null while scanning
     * and after the shared activity fade completes.
     */
    CursorFrame cursorFrame(long nowMs) {
        if (cursorPose == 0 || nowMs < cursorPhaseStartedAtMs) return null;
        if (!isActivityVisible(nowMs)) {
            if (hasActivity && nowMs >= saturatingAdd(leaseUntilMs, FADE_OUT_MS)) {
                clearCursor();
            }
            return null;
        }
        if (scanActive(nowMs)) return null;

        long elapsed = nowMs - cursorPhaseStartedAtMs;
        if (cursorPhase == CURSOR_PHASE_TRAVELING) {
            if (elapsed < cursorTravelDurationMs) {
                float restore = smoothstep(elapsed / (float) CURSOR_RESTORE_MS);
                return cursorFrame(CURSOR_PHASE_TRAVELING,
                        smoothstep(elapsed / (float) cursorTravelDurationMs),
                        mix(cursorTravelFromAlpha, 1f, restore), 0f, nowMs);
            }
            return cursorFrame(CURSOR_PHASE_TRAVELING, 1f, 1f, 0f, nowMs);
        }
        if (cursorPhase == CURSOR_PHASE_ACTING) {
            if (elapsed < cursorActionDurationMs) {
                float progress = smoothstep(elapsed / (float) cursorActionDurationMs);
                float beat = Math.min(1f, elapsed / (float) CURSOR_ACTION_MS);
                float amount = beat <= 0.5f
                        ? smoothstep(beat * 2f)
                        : smoothstep((1f - beat) * 2f);
                return cursorFrame(CURSOR_PHASE_ACTING, progress, 1f, amount, nowMs);
            }
            cursorPhase = CURSOR_PHASE_AFTERGLOW;
            cursorPhaseStartedAtMs = saturatingAdd(cursorPhaseStartedAtMs, cursorActionDurationMs);
            elapsed -= cursorActionDurationMs;
        }

        if (cursorPhase == CURSOR_PHASE_AFTERGLOW) {
            if (elapsed < CURSOR_AFTERGLOW_MS) {
                return cursorFrame(CURSOR_PHASE_AFTERGLOW,
                        smoothstep(elapsed / (float) CURSOR_AFTERGLOW_MS), 1f, 0f, nowMs);
            }
            cursorPose = GLOVE_POSE_POINT;
            cursorPhase = CURSOR_PHASE_POINTER_HOLD;
            cursorPhaseStartedAtMs = saturatingAdd(cursorPhaseStartedAtMs, CURSOR_AFTERGLOW_MS);
            elapsed -= CURSOR_AFTERGLOW_MS;
        }

        if (cursorPhase == CURSOR_PHASE_POINTER_HOLD) {
            if (elapsed < CURSOR_POINTER_HOLD_MS) {
                return cursorFrame(CURSOR_PHASE_POINTER_HOLD,
                        smoothstep(elapsed / (float) CURSOR_POINTER_HOLD_MS), 1f, 0f, nowMs);
            }
            cursorPhase = CURSOR_PHASE_IDLE;
            cursorPhaseStartedAtMs = saturatingAdd(cursorPhaseStartedAtMs, CURSOR_POINTER_HOLD_MS);
            elapsed -= CURSOR_POINTER_HOLD_MS;
        }

        float idleProgress = smoothstep(elapsed / (float) CURSOR_IDLE_DIM_MS);
        float idleAlpha = mix(1f, CURSOR_IDLE_ALPHA, idleProgress);
        return cursorFrame(CURSOR_PHASE_IDLE, idleProgress, idleAlpha, 0f, nowMs);
    }

    boolean cursorSessionVisible(long nowMs) {
        return cursorFrame(nowMs) != null;
    }

    boolean hasCursorSession() {
        return cursorPose != 0;
    }

    void clearCursor() {
        cursorPose = 0;
        cursorPhase = 0;
        cursorPhaseStartedAtMs = Long.MIN_VALUE;
        cursorTravelDurationMs = 0L;
        cursorActionDurationMs = CURSOR_ACTION_MS;
        cursorTravelFromAlpha = 1f;
        cursorNeedsCenterPlacement = false;
    }

    private CursorFrame cursorFrame(int phase, float progress, float stateAlpha,
            float actionAmount, long nowMs) {
        // The 120ms backdrop envelope gives the cursor immediate feedback while
        // retaining the same 1.8s exit as the perimeter and caption backdrop.
        float alpha = stateAlpha * backdropEnvelope(nowMs);
        return new CursorFrame(cursorPose, phase, progress, alpha,
                actionAmount, cursorTravelDurationMs);
    }

    private float cursorLocalAlpha(long nowMs) {
        if (cursorPhase != CURSOR_PHASE_IDLE || nowMs <= cursorPhaseStartedAtMs) return 1f;
        float progress = smoothstep((nowMs - cursorPhaseStartedAtMs) / (float) CURSOR_IDLE_DIM_MS);
        return mix(1f, CURSOR_IDLE_ALPHA, progress);
    }

    private static void validatePose(int pose) {
        if (pose < GLOVE_POSE_POINT || pose > GLOVE_POSE_BACK_LEFT) {
            throw new IllegalArgumentException("unknown glove pose");
        }
    }

    private static float smoothstep(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    private static float mix(float start, float end, float amount) {
        return start + (end - start) * amount;
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static long saturatingAdd(long value, long amount) {
        if (amount > 0L && value > Long.MAX_VALUE - amount) return Long.MAX_VALUE;
        return value + amount;
    }
}
