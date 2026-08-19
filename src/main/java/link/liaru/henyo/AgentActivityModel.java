package link.liaru.henyo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/** Small, Android-free model for the transient, user-facing activity captions. */
final class AgentActivityModel {
    static final int MAX_MESSAGES = 3;
    static final int MAX_SUMMARY_CODE_POINTS = 72;
    static final long MESSAGE_HOLD_MS = 10_000L;
    static final long MESSAGE_FADE_OUT_MS = 1_800L;
    static final long SPINNER_FRAME_MS = 90L;
    static final long CARET_CYCLE_MS = 1_000L;
    static final long CARET_HOLD_ON_MS = 330L;
    static final long CARET_FADE_OUT_MS = 180L;
    static final long CARET_HOLD_OFF_MS = 350L;
    static final long CARET_FADE_IN_MS = 140L;
    static final float CARET_DIM_ALPHA = 0.20f;
    static final String[] SPINNER_FRAMES = {"⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"};

    static final class Message {
        final String summary;
        final long revealStartedAtMs;
        final long revealDurationMs;
        long expiresAtMs;

        Message(String summary, long nowMs) {
            this.summary = summary;
            this.revealStartedAtMs = nowMs;
            int codePoints = summary.codePointCount(0, summary.length());
            this.revealDurationMs = Math.min(700L, Math.max(120L, codePoints * 20L));
            this.expiresAtMs = nowMs + MESSAGE_HOLD_MS + MESSAGE_FADE_OUT_MS;
        }
    }

    private final List<Message> messages = new ArrayList<>();
    private List<Message> cachedSnapshot = Collections.emptyList();
    private boolean snapshotDirty;

    /** Returns true when a new row was added, false for empty or coalesced input. */
    boolean add(String rawSummary, long nowMs) {
        String summary = sanitize(rawSummary);
        if (summary.isEmpty()) return false;
        prune(nowMs);
        if (!messages.isEmpty()) {
            Message newest = messages.get(messages.size() - 1);
            if (newest.summary.equals(summary)) {
                newest.expiresAtMs = nowMs + MESSAGE_HOLD_MS + MESSAGE_FADE_OUT_MS;
                return false;
            }
        }
        messages.add(new Message(summary, nowMs));
        while (messages.size() > MAX_MESSAGES) messages.remove(0);
        snapshotDirty = true;
        return true;
    }

    void prune(long nowMs) {
        Iterator<Message> iterator = messages.iterator();
        boolean removed = false;
        while (iterator.hasNext()) {
            if (iterator.next().expiresAtMs <= nowMs) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) snapshotDirty = true;
    }

    boolean isEmpty(long nowMs) {
        prune(nowMs);
        return messages.isEmpty();
    }

    void clear() {
        if (messages.isEmpty()) return;
        messages.clear();
        snapshotDirty = true;
    }

    boolean removeSummary(String rawSummary) {
        String summary = sanitize(rawSummary);
        if (summary.isEmpty()) return false;
        boolean removed = false;
        Iterator<Message> iterator = messages.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().summary.equals(summary)) {
                iterator.remove();
                removed = true;
            }
        }
        if (removed) snapshotDirty = true;
        return removed;
    }

    List<Message> snapshot(long nowMs) {
        prune(nowMs);
        if (snapshotDirty) {
            cachedSnapshot = Collections.unmodifiableList(new ArrayList<>(messages));
            snapshotDirty = false;
        }
        return cachedSnapshot;
    }

    static String visibleText(Message message, long nowMs) {
        int total = message.summary.codePointCount(0, message.summary.length());
        if (total == 0 || nowMs >= message.revealStartedAtMs + message.revealDurationMs) {
            return message.summary;
        }
        float progress = clamp01((nowMs - message.revealStartedAtMs) / (float) message.revealDurationMs);
        int visible = Math.max(1, Math.min(total, (int) Math.ceil(total * progress)));
        int end = message.summary.offsetByCodePoints(0, visible);
        return message.summary.substring(0, end);
    }

    static String displayText(Message message, long nowMs, boolean newest) {
        if (!newest) return visibleText(message, nowMs);
        StringBuilder text = new StringBuilder(message.summary.length() + 4);
        text.append(spinnerFrame(nowMs)).append(' ').append(displayBodyText(message, nowMs, true));
        return text.toString();
    }

    static String displayBodyText(Message message, long nowMs, boolean newest) {
        return visibleText(message, nowMs);
    }

    static String spinnerFrame(long nowMs) {
        int index = (int) ((Math.max(0L, nowMs) / SPINNER_FRAME_MS) % SPINNER_FRAMES.length);
        return SPINNER_FRAMES[index];
    }

    static float caretAlpha(Message message, long nowMs) {
        if (nowMs < message.revealStartedAtMs + message.revealDurationMs) return 1f;
        long elapsedAfterReveal = Math.max(0L, nowMs - message.revealStartedAtMs - message.revealDurationMs);
        long phase = elapsedAfterReveal % CARET_CYCLE_MS;
        if (phase < CARET_HOLD_ON_MS) return 1f;
        phase -= CARET_HOLD_ON_MS;
        if (phase < CARET_FADE_OUT_MS) {
            return mix(1f, CARET_DIM_ALPHA, smoothstep(phase / (float) CARET_FADE_OUT_MS));
        }
        phase -= CARET_FADE_OUT_MS;
        if (phase < CARET_HOLD_OFF_MS) return CARET_DIM_ALPHA;
        phase -= CARET_HOLD_OFF_MS;
        return mix(CARET_DIM_ALPHA, 1f, smoothstep(phase / (float) CARET_FADE_IN_MS));
    }

    static float alpha(Message message, int ageRank, long nowMs) {
        float rankAlpha = ageRank <= 0 ? 1f : ageRank == 1 ? 0.70f : 0.48f;
        float fadeIn = clamp01((nowMs - message.revealStartedAtMs) / 180f);
        float fadeOut = clamp01((message.expiresAtMs - nowMs) / (float) MESSAGE_FADE_OUT_MS);
        return rankAlpha * Math.min(fadeIn, fadeOut);
    }

    static String sanitize(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        boolean pendingSpace = false;
        int accepted = 0;
        for (int offset = 0; offset < raw.length() && accepted < MAX_SUMMARY_CODE_POINTS; ) {
            int codePoint = raw.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean space = Character.isWhitespace(codePoint) || Character.isISOControl(codePoint);
            if (space) {
                pendingSpace = out.length() > 0;
                continue;
            }
            if (pendingSpace && accepted < MAX_SUMMARY_CODE_POINTS) {
                out.append(' ');
                accepted++;
            }
            pendingSpace = false;
            if (accepted >= MAX_SUMMARY_CODE_POINTS) break;
            out.appendCodePoint(codePoint);
            accepted++;
        }
        return out.toString();
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static float smoothstep(float value) {
        float t = clamp01(value);
        return t * t * (3f - 2f * t);
    }

    private static float mix(float start, float end, float amount) {
        return start + (end - start) * amount;
    }
}
