package link.liaru.henyo;

/** Android-free model for one transient, user-facing task completion message. */
final class CompletionMessageModel {
    static final int MAX_MESSAGE_CODE_POINTS = 250;
    static final int SHOW_INVALID = 0;
    static final int SHOW_ACCEPTED = 1;
    static final int SHOW_TOO_LONG = -1;
    static final int SHOW_PROGRESS_ACTIVE = -2;
    static final long MESSAGE_HOLD_MS = 30_000L;
    static final long MESSAGE_FADE_OUT_MS = 1_800L;
    static final long MESSAGE_FADE_IN_MS = 180L;

    static final class Message {
        final String text;
        final long shownAtMs;
        final long expiresAtMs;

        Message(String text, long nowMs) {
            this.text = text;
            this.shownAtMs = nowMs;
            this.expiresAtMs = nowMs + MESSAGE_HOLD_MS + MESSAGE_FADE_OUT_MS;
        }
    }

    private Message message;

    int show(String text, long nowMs) {
        int validation = validate(text);
        if (validation != SHOW_ACCEPTED) return validation;
        message = new Message(text, nowMs);
        return SHOW_ACCEPTED;
    }

    Message current(long nowMs) {
        if (message != null && message.expiresAtMs <= nowMs) message = null;
        return message;
    }

    boolean isEmpty(long nowMs) {
        return current(nowMs) == null;
    }

    void clear() {
        message = null;
    }

    static int validate(String text) {
        if (text == null || text.codePointCount(0, text.length()) == 0) return SHOW_INVALID;
        return text.codePointCount(0, text.length()) <= MAX_MESSAGE_CODE_POINTS
                ? SHOW_ACCEPTED : SHOW_TOO_LONG;
    }

    static float alpha(Message message, long nowMs) {
        if (message == null) return 0f;
        float fadeIn = clamp01((nowMs - message.shownAtMs) / (float) MESSAGE_FADE_IN_MS);
        float fadeOut = clamp01((message.expiresAtMs - nowMs) / (float) MESSAGE_FADE_OUT_MS);
        return Math.min(fadeIn, fadeOut);
    }

    private static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
